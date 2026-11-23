package br.geracaoai.baixartg.core

import android.content.Context
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.tg.Td
import br.geracaoai.baixartg.tg.Uploader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fila de envio persistente (disco) do navegador: cada item passa por
 * abrir a página (resolver) → baixar → enviar, um por vez em cada etapa, sem esperar os outros.
 * Sobrevive a fechar o app, à morte do processo, a reiniciar o celular e a atualizar o app.
 */
object Pipeline {
    enum class PS { WAIT, RESOLVING, READY, DOWN, UP, DONE, FAIL, SKIP, ANALYZE, ANALYZING, CANCEL }
    /** Terminou (enviado, falhou, pulado ou cancelado pelo usuário). Em análise continua à parte. */
    fun isFinal(s: PS) = s == PS.DONE || s == PS.FAIL || s == PS.SKIP || s == PS.CANCEL
    data class PItem(
        val id: Long, val page: String, val title: String, val extra: String, val keys: List<String>,
        val f: Found?, val st: PS, val msg: String = "", val prog: Float = 0f, val photo: Boolean = false,
        val speed: Long = 0, val stalls: Int = 0, val force: Boolean = false, val tried: String = "",
    )

    val items = MutableStateFlow<List<PItem>>(emptyList())
    val paused = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    private lateinit var app: Context
    private var file: File? = null
    /** Comparação por imagem: 1 thread só, em segundo plano (nunca 2 decodificadores de vídeo ao mesmo tempo). */
    val hashDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "pipe-hash").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    // 1.8.9 punha a fila toda em prioridade de segundo plano: com o navegador pesado ela quase não recebia CPU
    // e os itens ficavam parados em "abrindo página". Volta ao normal (rede/disco, não disputa a tela).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Job? = null
    private val lock = Any()
    private var nextId = 1L
    private val ACTIVE = setOf(PS.WAIT, PS.RESOLVING, PS.READY, PS.DOWN, PS.UP)
    private const val RESOLVERS = 5
    private const val DOWNLOADERS = 4
    private const val UPLOADERS = 3
    private const val RESOLVE_TIMEOUT = 90_000L   // tudo junto, inclusive esperar vaga: estático + página + servidor
    private const val RESOLVE_STALL = 100_000L
    private const val OPEN_GAP = 350L
    private const val STALL_MS = 45_000L
    private const val JOB_GIVE_UP = 2_000L

    fun init(c: Context) {
        app = c.applicationContext
        VisualHash.init(app)
        file = File(app.filesDir, "send_queue.json")
        load()
        migrate183()
        // processo morreu no meio: volta o que estava em andamento para a etapa anterior
        synchronized(lock) {
            items.value = items.value.map {
                when (it.st) {
                    PS.RESOLVING -> it.copy(st = PS.WAIT, msg = "", prog = 0f)
                    PS.DOWN, PS.UP -> it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f)
                    else -> it
                }
            }
        }
        saveAsync()
    }

    /** Há trabalho pendente (para retomar depois de reiniciar/atualizar). */
    private val WORK get() = ACTIVE + PS.ANALYZE + PS.ANALYZING
    fun hasWork() = !paused.value && items.value.any { it.st in WORK }

    // ---------------- persistência ----------------
    private fun fToJ(f: Found) = JSONObject().put("u", f.url).put("p", f.page).put("t", f.title).put("k", f.kind.name).put("r", f.ref ?: "").put("x", f.extra).put("q", f.quality)
    private fun jToF(j: JSONObject) = Found(j.getString("u"), j.optString("p"), j.optString("t"), Kind.valueOf(j.optString("k", "SERVER")), j.optString("r").ifBlank { null }, j.optString("x"), j.optInt("q"))

    private fun load() {
        val fl = file ?: return
        if (!fl.exists()) return
        try {
            val o = JSONObject(fl.readText())
            paused.value = o.optBoolean("paused", false)
            val a = o.getJSONArray("items")
            val l = ArrayList<PItem>()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val ks = j.optJSONArray("keys")?.let { ka -> (0 until ka.length()).map { ka.getString(it) } } ?: emptyList()
                l += PItem(j.getLong("id"), j.getString("page"), j.optString("title"), j.optString("extra"), ks,
                    j.optJSONObject("f")?.let { jToF(it) }, try { PS.valueOf(j.optString("st", "WAIT")) } catch (_: Exception) { PS.WAIT }, j.optString("msg"), 0f, j.optBoolean("photo"), force = j.optBoolean("force"), tried = j.optString("tried"))
            }
            items.value = l
            nextId = (l.maxOfOrNull { it.id } ?: 0L) + 1
        } catch (_: Exception) {}
    }

    @Volatile private var saveJob: Job? = null
    private fun save() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch { delay(1500); saveNow() }
    }
    private val saveLock = Any()
    /** Grava sempre fora da thread principal. */
    private fun saveAsync() { scope.launch { saveNow() } }
    fun saveNow(): Unit = synchronized(saveLock) {
        val fl = file ?: return
        try {
            val a = JSONArray()
            for (it in items.value) {
                val j = JSONObject().put("id", it.id).put("page", it.page).put("title", it.title).put("extra", it.extra.take(600))
                    .put("keys", JSONArray(it.keys)).put("st", it.st.name).put("msg", it.msg).put("photo", it.photo).put("force", it.force).put("tried", it.tried)
                it.f?.let { f -> j.put("f", fToJ(f)) }
                a.put(j)
            }
            val tmp = File(fl.parentFile, fl.name + ".tmp")
            tmp.writeText(JSONObject().put("paused", paused.value).put("items", a).toString())
            tmp.renameTo(fl)
        } catch (_: Exception) {}
    }

    /**
     * Atualização a partir da 1.8.3: a fila dela ficava só na memória; o que ficou no disco foram as chaves
     * "já pedido" (queued_keys.txt) e "já enviado" (sent_keys.txt). Páginas pedidas e não enviadas voltam para a fila.
     */
    private fun migrate183() {
        if (Prefs.pipelineMigrated) return
        Prefs.pipelineMigrated = true
        try {
            val q = File(app.filesDir, "queued_keys.txt")
            if (!q.exists()) return
            val have = items.value.map { normPage(it.page) }.toHashSet()
            val add = ArrayList<PItem>()
            q.forEachLine { k0 ->
                val k = k0.trim()
                if (!k.startsWith("ip:http")) return@forEachLine
                val page = k.removePrefix("ip:")
                if (Sent.has(k) || Sent.has("p:$page") || !have.add(page)) return@forEachLine
                if (Extract.isPhotoUrl(page) && !Extract.isVideoUrl(page)) return@forEachLine
                add += PItem(nextId++, page, Extract.fileName(page).replace('-', ' ').replace('_', ' ').ifBlank { "vídeo" }, "", listOf(k, "p:$page"), null, PS.WAIT)
            }
            if (add.isNotEmpty()) synchronized(lock) { items.value = items.value + add }
        } catch (_: Exception) {}
    }

    private fun normPage(u: String) = PageKey.norm(u)

    // ---------------- API ----------------
    data class In(val page: String, val title: String, val extra: String, val keys: List<String>, val f: Found?, val photo: Boolean = false, val uniq: String = "")

    /** Entra na fila na hora (resolvido ou não) e a fila começa a andar. Devolve quantos entraram. */
    fun add(list: List<In>): Int {
        if (list.isEmpty()) return 0
        val n: Int
        synchronized(lock) {
            val have = items.value.filter { it.st != PS.FAIL && it.st != PS.ANALYZE && it.st != PS.CANCEL }.map { uniqueKey(it.page, it.title, it.keys, it.f) }.toHashSet()
            val add = list.filter { have.add(uniqueKey(it.page, it.title, it.keys, it.f, it.uniq)) }.map {
                PItem(nextId++, it.page, it.title, it.extra, expandKeys(it.page, it.keys + listOfNotNull(it.uniq.trim().takeIf { k -> k.isNotBlank() }?.let { k -> "id:$k" }), it.f), it.f, if (it.f != null) PS.READY else PS.WAIT, photo = it.photo)
            }
            items.value = items.value + add
            n = add.size
        }
        saveAsync()   // nada de arquivo na thread principal (tocar em Baixar não trava a tela)
        start()
        return n
    }

    fun pause() { paused.value = true; engine?.cancel(); saveAsync() }
    fun resume() { paused.value = false; saveAsync(); start() }
    fun cancelAll() {
        engine?.cancel()
        val gone: List<PItem>
        synchronized(lock) {
            val keep = setOf(PS.DONE, PS.FAIL, PS.SKIP, PS.CANCEL)
            gone = items.value.filter { it.st !in keep }
            items.value = items.value.filter { it.st in keep }
        }
        gone.forEach { releaseQueue(it) }
        paused.value = false; saveAsync()
    }
    fun retry(id: Long) { userCancel.remove(id); upd(id) { it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f, tried = "") }; start() }
    fun retryFailed() { synchronized(lock) { items.value = items.value.map { if (it.st == PS.FAIL || it.st == PS.ANALYZE || it.st == PS.ANALYZING) { userCancel.remove(it.id); it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", tried = "") } else it } }; save(); start() }
    /** Item pulado como repetido pela imagem: manda mesmo assim (sem a checagem visual). */
    fun forceSend(id: Long) { userCancel.remove(id); upd(id) { it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f, force = true, stalls = 0) }; start() }
    fun clearDone() { synchronized(lock) { items.value = items.value.filter { it.st != PS.DONE } }; saveAsync() }
    fun clearFailed() {
        val gone: List<PItem>
        synchronized(lock) { val rm = items.value.filter { it.st == PS.FAIL || it.st == PS.ANALYZE }; gone = rm; items.value = items.value.filter { it.st != PS.FAIL && it.st != PS.ANALYZE } }
        gone.forEach { releaseQueue(it) }; saveAsync()
    }
    fun clearQueue() {
        engine?.cancel()
        val gone: List<PItem>
        synchronized(lock) { gone = items.value.filter { it.st in WORK }; items.value = items.value.filter { it.st !in WORK } }
        gone.forEach { releaseQueue(it) }; paused.value = false; saveAsync()
    }
    fun clearAll(alsoSentMemory: Boolean = false) {
        engine?.cancel()
        val gone: List<PItem>
        synchronized(lock) { gone = items.value; items.value = emptyList() }
        gone.forEach { releaseQueue(it) }
        History.clear()
        if (alsoSentMemory) { Sent.clear(); Prefs.synced = false }
        paused.value = false; saveAsync()
    }
    /** Identidade única do item na fila: nunca junta dois vídeos só porque estão na mesma página. */
    fun uniqueKey(page: String, title: String, keys: List<String>, f: Found?, uniq: String = ""): String {
        uniq.trim().takeIf { it.isNotBlank() }?.let { return "id:$it" }
        keys.firstOrNull { k -> k.startsWith("id:") }?.let { return it }
        keys.firstOrNull { k -> k.startsWith("ip:") || k.startsWith("p:") || k.startsWith("card:") }?.let { return "k:$it" }
        val u = f?.url?.let { normPage(it) }.orEmpty()
        if (u.isNotBlank()) return "u:$u"
        return "p:" + normPage(page) + "|" + title
    }

    private fun expandKeys(page: String, keys: List<String>, f: Found?): List<String> {
        val k = ArrayList(keys)
        k += "pg:" + normPage(page)
        if (f != null) {
            k += Sent.keysOf(f)
            k += "pg:" + normPage(f.url)
            if (f.page.isNotBlank()) k += "pg:" + normPage(f.page)
        }
        return k.filter { it.isNotBlank() }.distinct()
    }
    private fun keysToUnqueue(t: PItem): List<String> {
        val k = ArrayList(t.keys)
        k += "pg:" + normPage(t.page)
        t.f?.let { f ->
            k += Sent.keysOf(f)
            k += "pg:" + normPage(f.url)
            if (f.page.isNotBlank()) k += "pg:" + normPage(f.page)
        }
        return k.distinct()
    }
    private fun releaseQueue(t: PItem) { try { Sent.unqueue(keysToUnqueue(t)) } catch (_: Exception) {} }

    private class UserCancelException : CancellationException("cancelado")
    private val userCancel = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Cancela este item na hora, libera a vaga e não tenta de novo sozinho. Volta a aparecer no navegador. */
    fun cancel(id: Long) {
        userCancel.add(id)
        itemJobs[id]?.cancel(UserCancelException())
        var gone: PItem? = null
        upd(id) { cur ->
            if (isFinal(cur.st) && cur.st != PS.ANALYZE && cur.st != PS.ANALYZING) cur
            else cur.copy(st = PS.CANCEL, msg = "cancelado", prog = 0f, speed = 0).also { gone = it }
        }
        gone?.let {
            try { synchronized(claimed) { claimed.removeAll(claimKeys(it)) } } catch (_: Exception) {}
            releaseQueue(it)
        }
    }

    /**
     * Chaves só do arquivo/vídeo, SEM a página. Na 1.9.9 a chave "pg:página" ia para o índice de
     * "já na fila/já enviado": o primeiro vídeo da página segurava todos os outros (só 1 andava).
     */
    fun mediaKeysOf(t: PItem): Set<String> = mediaKeys(t.keys, t.f, isPhoto(t))
    fun mediaKeys(keys: List<String>, f: Found?, photo: Boolean): Set<String> {
        val out = LinkedHashSet<String>()
        for (k in keys) if (k.isNotBlank() && !isPageKey(k)) out += k
        if (f != null) {
            if (photo) out += PhotoGrab.key(f.url)
            out += Sent.keysOf(f)
            out += MediaIdentity.keys(f)
        }
        return out.filter { !isPageKey(it) }.toHashSet()
    }
    private fun isPageKey(k: String) = k.startsWith("pg:") || k.startsWith("ip:") || k.startsWith("p:")
    private fun claimKeys(t: PItem) = mediaKeysOf(t).filter { !it.startsWith("d:") && !it.startsWith("ps:") }.toHashSet()
    private fun sentKeys(t: PItem) = mediaKeysOf(t)


    /**
     * persist=false = progresso: não regrava o arquivo e é juntado num lote aplicado no máximo ~4x/s
     * (evita uma tempestade de recomposições/notificações com 1000+ itens).
     */
    private fun upd(id: Long, persist: Boolean = true, fn: (PItem) -> PItem) {
        if (!persist) {
            pendingProg.merge(id, fn) { old, new -> { p: PItem -> new(old(p)) } }
            if (flusher?.isActive != true) flusher = scope.launch { delay(250); flushProg() }
            return
        }
        synchronized(lock) {
            val pf = pendingProg.remove(id)
            items.value = items.value.map { if (it.id == id) fn(if (pf != null) pf(it) else it) else it }
        }
        save()
    }
    private val pendingProg = java.util.concurrent.ConcurrentHashMap<Long, (PItem) -> PItem>()
    @Volatile private var flusher: Job? = null
    private fun flushProg() {
        if (pendingProg.isEmpty()) return
        synchronized(lock) {
            val m = HashMap<Long, (PItem) -> PItem>()
            for (k in pendingProg.keys.toList()) pendingProg.remove(k)?.let { m[k] = it }
            if (m.isNotEmpty()) items.value = items.value.map { t -> m[t.id]?.invoke(t) ?: t }
        }
    }
    private fun claim(pred: (PItem) -> Boolean, st: PS): PItem? = synchronized(lock) {
        val l = items.value; val i = l.indexOfFirst(pred)
        if (i < 0) null else { val n = l[i].copy(st = st, msg = "", prog = 0f, speed = 0); items.value = l.toMutableList().also { it[i] = n }; n }
    }.also { if (it != null) save() }

    // ---------------- motor ----------------
    /** Último sinal de vida de cada item em download/envio (para o vigia de travamento). */
    private val beat = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val itemJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private fun alive(id: Long) { beat[id] = System.currentTimeMillis() }

    @Volatile var testMode = false
    @Volatile var testParDown = 10
    @Volatile var testParUp = 5
    @Volatile var testResolve: (suspend (PItem) -> Found?)? = null
    @Volatile var testDownload: (suspend (PItem, File) -> File?)? = null
    @Volatile var testUpload: (suspend (PItem, File) -> Unit)? = null

    fun resetForTest() {
        engine?.cancel()
        engine = null
        synchronized(lock) { items.value = emptyList(); nextId = 1L }
        synchronized(claimed) { claimed.clear() }
        userCancel.clear(); beat.clear(); itemJobs.clear()
        paused.value = false; running.value = false
        testResolve = null; testDownload = null; testUpload = null
    }

    @Synchronized fun start() {
        if (paused.value) return
        if (!::app.isInitialized && !testMode) return
        if (engine?.isActive == true) return
        if (items.value.none { it.st in WORK }) return
        // nenhum motor rodando: o que ficou "abrindo página"/baixando/enviando volta para a etapa anterior
        synchronized(lock) {
            items.value = items.value.map {
                when (it.st) {
                    PS.RESOLVING -> it.copy(st = PS.WAIT, msg = "", prog = 0f, speed = 0)
                    PS.DOWN, PS.UP -> it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f, speed = 0)
                    PS.ANALYZING -> it.copy(st = PS.ANALYZE, msg = it.msg, prog = 0f, speed = 0)
                    else -> it
                }
            }
        }
        if (!testMode) PipeService.start(app)
        running.value = true
        engine = scope.launch {
            val dir = if (testMode) File(System.getProperty("java.io.tmpdir"), "pdl-test-" + System.nanoTime()).apply { mkdirs() }
                else File(app.cacheDir, "pdl").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
            synchronized(claimed) { claimed.clear() }
            beat.clear(); itemJobs.clear()
            try {
                coroutineScope {
                    // baixados esperando envio: no máximo nDown (protege o armazenamento); downloads correm na frente do envio
                    val nDown = if (testMode) testParDown.coerceIn(1, 10) else try { Prefs.parDown.coerceIn(1, 10) } catch (_: Exception) { 10 }
                    val nUp = if (testMode) testParUp.coerceIn(1, 5) else try { Prefs.parUp.coerceIn(1, 5) } catch (_: Exception) { 5 }
                    val ready = Channel<Pair<PItem, File>>(nDown)
                    val resolvers = List(nDown) { n -> launch { if (!testMode) delay(n * OPEN_GAP); resolverLoop() } }
                    val downs = List(nDown) { launch { downloaderLoop(dir, ready) } }
                    // Em análise: pool próprio (2), nunca ocupa vaga de download/envio da fila principal
                    val analyzers = List(2) { launch { analyzeLoop(dir, ready) } }
                    launch { downs.joinAll(); analyzers.joinAll(); ready.close() }
                    repeat(nUp) { launch { for ((t, f) in ready) uploadOne(t, f) } }
                    launch { watchdog() }.also { w -> launch { resolvers.joinAll(); downs.joinAll(); analyzers.joinAll(); w.cancel() } }
                }
            } catch (e: Throwable) {   // qualquer erro (não só cancelar): nada fica preso em "abrindo página"
                synchronized(lock) {
                    items.value = items.value.map {
                        when (it.st) { PS.RESOLVING -> it.copy(st = PS.WAIT, prog = 0f, msg = "", speed = 0); PS.DOWN, PS.UP -> it.copy(st = if (it.f != null) PS.READY else PS.WAIT, prog = 0f, msg = "", speed = 0); PS.ANALYZING -> it.copy(st = PS.ANALYZE, prog = 0f, speed = 0); else -> it }
                    }
                }
            } finally {
                itemJobs.values.forEach { it.cancel() }; itemJobs.clear()
                withContext(NonCancellable) { delay(300); dir.listFiles()?.forEach { it.deleteRecursively() }; saveNow(); running.value = false }
                if (hasWork()) scope.launch { delay(800); start() }   // algo entrou (Reenviar) enquanto terminava
            }
        }
    }

    /** Vigia: item sem progresso por 60 s em download (ou envio ainda não concluído) é reiniciado; nunca trava a fila. */
    private suspend fun watchdog() {
        while (true) {
            delay(10_000)
            val now = System.currentTimeMillis()
            for (t in items.value) {
                if (t.st != PS.DOWN && t.st != PS.UP && t.st != PS.RESOLVING && t.st != PS.ANALYZING) continue
                // DOWN/UP: o relógio de "sem progresso" só começa depois do primeiro byte (não cancela quem ainda está abrindo a conexão)
                val last = beat[t.id] ?: continue
                val limit = if (t.st == PS.RESOLVING || t.st == PS.ANALYZING) RESOLVE_STALL else if (t.st == PS.UP && t.prog >= 0.99f) 5 * 60_000L else STALL_MS
                if (now - last > limit) { beat[t.id] = now; itemJobs[t.id]?.cancel(StallException()) }
            }
        }
    }
    private class StallException : CancellationException("travou")

    private suspend fun resolverLoop() {
        while (true) {
            val t = claim({ it.st == PS.WAIT && it.f == null }, PS.RESOLVING)
            if (t == null) {
                // fica de prontidão enquanto a fila tiver qualquer coisa ativa: itens novos (Baixar de novo) entram sem reiniciar nada
                if (items.value.none { it.st in WORK }) return
                delay(1_000); continue
            }
            // foto (ou link que já é a imagem): baixa direto do endereço, nunca abre página
            if (t.photo) {
                upd(t.id) { it.copy(f = Found(t.page, t.page, it.title, Kind.PHOTO, ref = t.page, extra = it.extra), photo = true, st = PS.READY, msg = "") }
                beat.remove(t.id); continue
            }
            var f: Found? = null
            var stalledOut = false
            for (attempt in 0..1) {
                if (attempt > 0) { upd(t.id) { it.copy(msg = "Tentando de novo…") }; delay(2_000) }
                alive(t.id)
                // prazo duro: roda à parte e o resolvedor não espera além do prazo (mesmo se algo lá dentro não parar)
                val job = scope.async { testResolve?.invoke(t) ?: PageResolver.resolveFull(t.page, t.title, t.extra) }
                itemJobs[t.id] = job
                f = try { withTimeoutOrNull(RESOLVE_TIMEOUT) { job.await() } }
                    catch (e: CancellationException) { currentCoroutineContext().ensureActive(); stalledOut = true; null }
                    catch (e: Exception) { null }
                    finally { itemJobs.remove(t.id); if (job.isActive) job.cancel() }
                if (f != null || stalledOut) break
            }
            beat.remove(t.id)
            if (f == null && stalledOut) {
                var toRelease: PItem? = null
                upd(t.id) { cur ->
                    val n = cur.stalls + 1
                    if (n >= 3) cur.copy(st = PS.ANALYZE, msg = "Não abriu a página (tempo esgotado). Tentando de outro jeito…", stalls = n, tried = markTried(cur.tried, "resolve")).also { toRelease = it }
                    else cur.copy(st = PS.WAIT, msg = "Travou ao abrir a página, tentando de novo…", stalls = n)
                }
                toRelease?.let { releaseQueue(it) }
            } else if (f == null) {
                var toRelease: PItem? = null
                upd(t.id) { it.copy(st = PS.ANALYZE, msg = "Não consegui carregar o vídeo desta página. Tentando de outro jeito…", tried = markTried(it.tried, "resolve")).also { n -> toRelease = n } }
                toRelease?.let { releaseQueue(it) }
            } else upd(t.id) { it.copy(f = f, title = TitleFix.pick(f.title, it.title).ifBlank { it.title }, st = PS.READY, msg = "") }
            delay(OPEN_GAP)   // devagar com o site
        }
    }

    private val claimed = HashSet<String>()
    private fun isPhoto(t: PItem) = t.photo || t.f?.kind == Kind.PHOTO
    /** Fotos: só a chave exata da foto (antes as chaves de vídeo — pasta/nome do arquivo — marcavam fotos diferentes como "repetido"). */
    private fun keysOf(t: PItem): Set<String> = if (isPhoto(t)) (t.keys + PhotoGrab.key(t.f!!.url)).toHashSet()
        else (Jobs.sendKeys(t.f!!, t.keys) + MediaIdentity.keys(t.f)).toHashSet()
    private fun sentBefore(t: PItem): Boolean = if (isPhoto(t)) Sent.anySent(sentKeys(t)) else Jobs.alreadySent(t.f!!, sentKeys(t).toList())

    private suspend fun downloaderLoop(dir: File, ready: Channel<Pair<PItem, File>>) {
        while (true) {
            val t = claim({ it.st == PS.READY && it.f != null }, PS.DOWN)
            if (t == null) {
                if (items.value.none { it.st in WORK }) return
                delay(500); continue
            }
            if (t.id in userCancel || t.st == PS.CANCEL) { markCancelled(t); continue }
            val f = t.f!!
            if (!t.force && sentBefore(t)) { upd(t.id) { it.copy(st = PS.SKIP, msg = "já enviado antes (pulado)", prog = 0f) }; continue }
            val ks = claimKeys(t)
            val dup = synchronized(claimed) { if (ks.any { it in claimed }) true else { claimed.addAll(ks); false } }
            if (dup) { upd(t.id) { it.copy(st = PS.SKIP, msg = "mesmo item já está na fila (pulado)", prog = 0f) }; continue }
            val job = scope.async { downloadOne(t, dir) }
            itemJobs[t.id] = job
            val file = try { job.await() } catch (e: CancellationException) {
                if (job.isActive) job.cancel()
                withTimeoutOrNull(JOB_GIVE_UP) { try { job.join() } catch (_: Exception) {} }
                when {
                    t.id in userCancel || e is UserCancelException -> { markCancelled(t, ks); null }
                    e is StallException || e.cause is StallException -> { toAnalyze(t, ks, "download travado (sem progresso). Foi pra análise."); null }
                    else -> { currentCoroutineContext().ensureActive(); markCancelled(t, ks); throw e }
                }
            } finally { itemJobs.remove(t.id); beat.remove(t.id) }
            if (file == null) { synchronized(claimed) { claimed.removeAll(ks) }; continue }
            ready.send(t to file)
        }
    }

    /** Item travado ou que falhou: sai da vaga principal e vai pra Em análise (os outros seguem). */
    private fun toAnalyze(t: PItem, ks: Set<String>, msg: String) {
        synchronized(claimed) { claimed.removeAll(ks) }
        var toRelease: PItem? = null
        upd(t.id) { cur ->
            if (cur.st == PS.CANCEL || cur.id in userCancel) cur
            else cur.copy(st = PS.ANALYZE, msg = msg.take(180), prog = 0f, speed = 0, stalls = cur.stalls + 1).also { toRelease = it }
        }
        toRelease?.let { releaseQueue(it) }
    }
    private fun markCancelled(t: PItem, ks: Set<String> = emptySet()) {
        userCancel.add(t.id)
        if (ks.isNotEmpty()) synchronized(claimed) { claimed.removeAll(ks) }
        var gone: PItem? = null
        upd(t.id) { it.copy(st = PS.CANCEL, msg = "cancelado", prog = 0f, speed = 0).also { n -> gone = n } }
        gone?.let { releaseQueue(it) }
    }
    private fun stalled(t: PItem, ks: Set<String>, stage: String) {
        if (t.id in userCancel) { markCancelled(t, ks); return }
        toAnalyze(t, ks, "Travou no $stage (sem progresso). Foi pra análise, a fila segue.")
    }

    private fun markTried(prev: String, step: String): String =
        (prev.split(',').filter { it.isNotBlank() } + step).distinct().joinToString(",")

    private fun hasTried(tried: String, step: String) = tried.split(',').any { it == step }

    private suspend fun downloadOne(t: PItem, dir: File, fromAnalyze: Boolean = false): File? {
        testDownload?.let { fn -> return fn(t, dir) }
        var f = t.f!!
        var file: File? = null
        var tried = t.tried
        var lastErr: Exception? = null
        fun prog(): (Long, Long) -> Unit {
            var last = 0L; var lastB = 0L; var lastT = System.currentTimeMillis(); var spd = 0L
            return { got, tot ->
                alive(t.id)
                val now = System.currentTimeMillis()
                if (now - lastT >= 1000) { spd = (got - lastB) * 1000 / (now - lastT); lastB = got; lastT = now }
                if (now - last > 700) { last = now; upd(t.id, persist = false) { it.copy(prog = if (tot > 0) got.toFloat() / tot else 0f, speed = spd, msg = fmt(got) + (if (tot > 0) " de " + fmt(tot) else "")) } }
            }
        }
        suspend fun finish(src0: File): File {
            var src = src0
            if (Downloader.isHlsOutput(src)) {
                upd(t.id, persist = false) { it.copy(msg = "Convertendo para MP4…") }
                src = try { withContext(Dispatchers.IO) { Remux.finish(src, Downloader.audioOf(src)) } } catch (e: Exception) { if (e is CancellationException) throw e; src }
            }
            val size = src.length()
            if (size > 2000L * 1024 * 1024 && !Td.isPremium.value) throw DlException("Maior que 2 GB (limite do Telegram)")
            if (f.kind == Kind.PHOTO) {
                val long = Downloader.photoLongSide(src)
                if (long in 1 until 200) throw DlException("Imagem pequena (miniatura/ícone), ignorada.")
                if (long in 200 until 400) upd(t.id, persist = false) { it.copy(msg = "qualidade baixa (${long}px) — baixada a maior versão que achei") }
            } else {
                val k = Downloader.sniffFile(src)
                if (k == "image" || src.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) throw DlException("O arquivo não é um vídeo (veio uma imagem (capa)).")
            }
            return src
        }
        try {
            val steps = ArrayList<Pair<String, suspend () -> File>>()
            val playHit = PlayCapture.bestFor(f)
            if (playHit != null && (!fromAnalyze || !hasTried(tried, "play"))) steps += "play" to {
                Downloader.download(PlayCapture.toFound(f, playHit), dir, Prefs.server, 0, fast = true, prog())
            }
            if (!fromAnalyze || !hasTried(tried, "direct")) steps += "direct" to { Downloader.download(f, dir, Prefs.server, 0, fast = true, prog()) }
            if ((!fromAnalyze || !hasTried(tried, "server")) && f.page.isNotBlank() && !Extract.isVideoUrl(f.page))
                steps += "server" to { Downloader.viaServer(f.page, dir, Prefs.server, prog()) }
            if ((!fromAnalyze || !hasTried(tried, "hls")) && (f.kind == Kind.DIRECT || f.url.contains(".m3u8", true) || f.url.contains(".mpd", true) || f.url.contains("video.twimg.com", true)))
                steps += "hls" to {
                    if (f.url.contains(".m3u8", true) || f.kind == Kind.HLS) Downloader.hls(f.url, f.ref ?: f.page, dir, t.title, prog(), maxHeight = 0)
                    else Downloader.download(f.copy(kind = Kind.HLS), dir, Prefs.server, 0, fast = true, prog())
                }
            for ((name, fn) in steps) {
                currentCoroutineContext().ensureActive()
                tried = markTried(tried, name)
                upd(t.id, persist = false) { it.copy(tried = tried, msg = "tentando $name…") }
                try { file = finish(fn()); lastErr = null; break } catch (e: CancellationException) { throw e }
                catch (e: Exception) { lastErr = e; file?.delete(); file = null }
            }
            if (file == null && f.page.isNotBlank() && !Extract.isVideoUrl(f.page) && !hasTried(tried, "resolve")) {
                tried = markTried(tried, "resolve")
                upd(t.id, persist = false) { it.copy(tried = tried, msg = "tentando abrir a página de outro jeito…") }
                val nf = withTimeoutOrNull(90_000) { PageResolver.resolveFull(f.page, t.title, t.extra) }
                if (nf != null) {
                    f = nf
                    upd(t.id) { it.copy(f = nf, title = TitleFix.pick(nf.title, it.title).ifBlank { it.title }, tried = tried) }
                    try { file = finish(Downloader.download(nf, dir, Prefs.server, 0, fast = true, prog())) } catch (e: CancellationException) { throw e } catch (e: Exception) { lastErr = e; file?.delete(); file = null }
                }
            }
            if (file == null && fromAnalyze && (hasTried(tried, "direct") && hasTried(tried, "server") || hasTried(tried, "resolve")) && hasTried(tried, "direct")) {
                tried = markTried(tried, "done")
                val msg = (lastErr?.message?.take(160) ?: "Erro ao baixar") + " — em análise: nenhum jeito funcionou ainda. Toque em Reenviar."
                var toRelease: PItem? = null
                upd(t.id) { it.copy(st = PS.ANALYZE, msg = msg, speed = 0, tried = tried).also { n -> toRelease = n } }
                toRelease?.let { releaseQueue(it) }
                return null
            }
            if (file == null) {
                val msg = (lastErr?.message?.take(160) ?: "Erro ao baixar") + " — em análise, tentando outro jeito"
                var toRelease: PItem? = null
                upd(t.id) { it.copy(st = PS.ANALYZE, msg = msg, speed = 0, tried = tried).also { n -> toRelease = n } }
                toRelease?.let { releaseQueue(it) }
                return null
            }
            if (f.kind == Kind.PHOTO && Downloader.photoLongSide(file!!) in 200 until 400)
                upd(t.id) { it.copy(prog = 1f, speed = 0, msg = "Baixado em qualidade baixa, esperando envio…", tried = tried) }
            else
                upd(t.id) { it.copy(prog = 1f, speed = 0, msg = "Baixado, esperando envio…", tried = tried) }
            return file
        } catch (e: CancellationException) { file?.delete(); throw e
        } catch (e: Exception) {
            file?.delete()
            var toRelease: PItem? = null
            upd(t.id) { it.copy(st = PS.ANALYZE, msg = (e.message?.take(160) ?: "Erro ao baixar") + " — em análise", speed = 0, tried = tried).also { n -> toRelease = n } }
            toRelease?.let { releaseQueue(it) }
            return null
        }
    }

    /** Pool próprio (2): não usa vaga de download. Estado ANALYZING, não DOWN. */
    private suspend fun analyzeLoop(dir: File, ready: Channel<Pair<PItem, File>>) {
        while (true) {
            val t = claim({ it.st == PS.ANALYZE && it.id !in userCancel && !hasTried(it.tried, "done") }, PS.ANALYZING)
            if (t == null) {
                if (items.value.none { it.st in WORK }) return
                delay(2_000); continue
            }
            if (t.id in userCancel) { markCancelled(t); continue }
            delay(1_000)
            alive(t.id)
            val job = scope.async {
                if (t.f == null) {
                    val f = withTimeoutOrNull(RESOLVE_TIMEOUT) { PageResolver.resolveFull(t.page, t.title, t.extra) }
                    if (f != null) {
                        upd(t.id) { it.copy(f = f, title = TitleFix.pick(f.title, it.title).ifBlank { it.title }) }
                        downloadOne(t.copy(f = f, st = PS.ANALYZING), dir, fromAnalyze = true)
                    } else null
                } else downloadOne(t, dir, fromAnalyze = true)
            }
            itemJobs[t.id] = job
            val file = try { job.await() } catch (e: CancellationException) {
                if (job.isActive) job.cancel()
                withTimeoutOrNull(JOB_GIVE_UP) { try { job.join() } catch (_: Exception) {} }
                if (t.id in userCancel || e is UserCancelException) { markCancelled(t); null } else null
            } finally { itemJobs.remove(t.id); beat.remove(t.id) }
            if (file != null) ready.send(t.copy(f = items.value.firstOrNull { it.id == t.id }?.f ?: t.f) to file)
        }
    }

    private suspend fun uploadOne(t0: PItem, file: File) {
        val t = items.value.firstOrNull { it.id == t0.id } ?: t0
        val f = t.f!!
        val ks = claimKeys(t)
        if (t.id in userCancel) { markCancelled(t, ks); return }
        testUpload?.let { fn ->
            fn(t, file)
            synchronized(claimed) { claimed.removeAll(ks) }
            upd(t.id) { it.copy(st = PS.DONE, prog = 1f, msg = "", speed = 0) }
            return
        }
        try {
            upd(t.id) { it.copy(st = PS.UP, prog = 0f, speed = 0, msg = "Enviando…") }
            while (!Td.ready) { upd(t.id, persist = false) { it.copy(msg = "Esperando o Telegram conectar…") }; delay(3_000) }
            Uploader.savedChat()
            // Idempotência: confere de novo logo antes de enviar
            val fk = Jobs.fileKey(file)
            if (!t.force && ((fk != null && Sent.has(fk)) || sentBefore(t))) {
                upd(t.id) { it.copy(st = PS.SKIP, prog = 0f, msg = "mesmo arquivo já enviado antes (pulado)") }
                return
            }
            // Repetido pela imagem (muito rigoroso; na dúvida envia). Nunca apaga nada: só pula e deixa "Enviar mesmo assim".
            var vfp: VisualHash.Fp? = null
            // Leve e nunca trava: 1 por vez no app todo, 20 s no máximo; se ocupado, demorar ou o navegador estiver
            // aberto (decodificador de vídeo é do navegador), envia sem essa checagem (a de arquivo igual continua).
            if (Prefs.visualDedupe && f.kind != Kind.PHOTO && !t.force && !JsRender.browserVisible && VisualHash.busy.compareAndSet(false, true)) {
                upd(t.id, persist = false) { it.copy(msg = "Comparando com os já enviados…") }
                val hj = scope.async(hashDispatcher) { try { VisualHash.fingerprint(file, t.title) } finally { VisualHash.busy.set(false) } }
                vfp = try { withTimeoutOrNull(20_000) { hj.await() } } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; null }
                if (vfp != null) {
                    val m = VisualHash.claim(t.id, vfp)
                    if (m != null) {
                        if (Prefs.replaceHigher && vfp.pixels > m.pixels) {
                            VisualHash.release(t.id)   // qualidade maior: envia esta também
                        } else {
                            synchronized(claimed) { claimed.removeAll(ks) }
                            upd(t.id) { it.copy(st = PS.SKIP, prog = 0f, speed = 0, msg = "igual a: " + m.title.ifBlank { "vídeo já enviado" }.take(90)) }
                            return
                        }
                    }
                }
            }
            val cap = f.title + "\n" + f.page + (if (f.url != f.page && !Bunkr.isCdn(f.url) && f.kind != Kind.MEGA) "\n" + f.url else "")
            alive(t.id)
            var lastP = -1f; var lastT = System.currentTimeMillis(); var lastU = 0f
            val size = file.length()
            val job = scope.async {
                // envia o arquivo local já baixado (TDLib lê do disco; nada é baixado de novo)
                Uploader.upload(file, cap, asVideo = f.kind != Kind.PHOTO) { p ->
                    if (p != lastP) { lastP = p; alive(t.id) }
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 1000) {
                        val spd = ((p - lastU) * size * 1000 / (now - lastT)).toLong().coerceAtLeast(0); lastU = p; lastT = now
                        upd(t.id, persist = false) { it.copy(prog = p, speed = spd) }
                    }
                }
            }
            itemJobs[t.id] = job
            try { job.await() } catch (e: CancellationException) {
                if (job.isActive) job.cancel()
                withTimeoutOrNull(JOB_GIVE_UP) { try { job.join() } catch (_: Exception) {} }
                if (t.id in userCancel || e is UserCancelException) { markCancelled(t, ks); return }
                stalled(t, ks, "envio"); return
            } finally { itemJobs.remove(t.id); beat.remove(t.id) }
            if (isPhoto(t)) Sent.markPhotoSent(sentKeys(t) + listOfNotNull(fk)) else Sent.markSent(f, sentKeys(t) + listOfNotNull(fk)); History.add(f, size)
            synchronized(claimed) { claimed.removeAll(claimKeys(t)) }
            if (vfp != null) VisualHash.markSent(t.id, vfp)
            upd(t.id) { it.copy(st = PS.DONE, prog = 1f, msg = "", speed = 0) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            synchronized(claimed) { claimed.removeAll(ks) }
            var toRelease: PItem? = null
            upd(t.id) { it.copy(st = PS.ANALYZE, msg = (e.message?.take(160) ?: "Erro ao enviar") + " — em análise", speed = 0).also { n -> toRelease = n } }
            toRelease?.let { releaseQueue(it) }
        } finally { VisualHash.release(t.id); withContext(NonCancellable) { file.delete(); Downloader.audioOf(file).delete() } }   // apaga o temporário sempre
    }

    fun fmt(b: Long): String = if (b >= 1 shl 30) "%.1f GB".format(b / 1073741824.0) else if (b >= 1 shl 20) "%.0f MB".format(b / 1048576.0) else "%.0f KB".format(b / 1024.0)
}
