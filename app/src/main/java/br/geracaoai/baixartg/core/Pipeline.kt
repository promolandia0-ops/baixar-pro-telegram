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
    enum class PS { WAIT, RESOLVING, READY, DOWN, UP, DONE, FAIL, SKIP }
    /** Terminou (enviado, falhou ou pulado como repetido). */
    fun isFinal(s: PS) = s == PS.DONE || s == PS.FAIL || s == PS.SKIP
    data class PItem(
        val id: Long, val page: String, val title: String, val extra: String, val keys: List<String>,
        val f: Found?, val st: PS, val msg: String = "", val prog: Float = 0f, val photo: Boolean = false,
        val speed: Long = 0, val stalls: Int = 0, val force: Boolean = false,
    )

    val items = MutableStateFlow<List<PItem>>(emptyList())
    val paused = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    private lateinit var app: Context
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Job? = null
    private val lock = Any()
    private var nextId = 1L
    private val ACTIVE = setOf(PS.WAIT, PS.RESOLVING, PS.READY, PS.DOWN, PS.UP)
    private const val RESOLVERS = 4
    private const val DOWNLOADERS = 3
    private const val UPLOADERS = 2
    private const val RESOLVE_TIMEOUT = 100_000L   // estático + ~30 s de página + servidor
    private const val OPEN_GAP = 350L
    private const val STALL_MS = 60_000L

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
    fun hasWork() = !paused.value && items.value.any { it.st in ACTIVE }

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
                    j.optJSONObject("f")?.let { jToF(it) }, PS.valueOf(j.optString("st", "WAIT")), j.optString("msg"), 0f, j.optBoolean("photo"), force = j.optBoolean("force"))
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
                    .put("keys", JSONArray(it.keys)).put("st", it.st.name).put("msg", it.msg).put("photo", it.photo).put("force", it.force)
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

    private fun normPage(u: String) = u.substringBefore('#').substringBefore('?').lowercase().replace("://www.", "://").replace("://m.", "://").trimEnd('/')

    // ---------------- API ----------------
    data class In(val page: String, val title: String, val extra: String, val keys: List<String>, val f: Found?, val photo: Boolean = false)

    /** Entra na fila na hora (resolvido ou não) e a fila começa a andar. Devolve quantos entraram. */
    fun add(list: List<In>): Int {
        if (list.isEmpty()) return 0
        val n: Int
        synchronized(lock) {
            val have = items.value.filter { it.st != PS.FAIL }.map { normPage(it.page) + "|" + (it.f?.url ?: "") }.toHashSet()
            val add = list.filter { have.add(normPage(it.page) + "|" + (it.f?.url ?: "")) }.map {
                PItem(nextId++, it.page, it.title, it.extra, it.keys, it.f, if (it.f != null) PS.READY else PS.WAIT, photo = it.photo)
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
        synchronized(lock) { items.value = items.value.filter { it.st !in ACTIVE } }
        paused.value = false; saveAsync()
    }
    fun retry(id: Long) { upd(id) { it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f) }; start() }
    fun retryFailed() { synchronized(lock) { items.value = items.value.map { if (it.st == PS.FAIL) it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "") else it } }; save(); start() }
    /** Item pulado como repetido pela imagem: manda mesmo assim (sem a checagem visual). */
    fun forceSend(id: Long) { upd(id) { it.copy(st = if (it.f != null) PS.READY else PS.WAIT, msg = "", prog = 0f, force = true, stalls = 0) }; start() }
    fun clearDone() { synchronized(lock) { items.value = items.value.filter { it.st != PS.DONE } }; saveAsync() }

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

    @Synchronized fun start() {
        if (!::app.isInitialized || paused.value) return
        if (engine?.isActive == true) return
        if (items.value.none { it.st in ACTIVE }) return
        PipeService.start(app)
        running.value = true
        engine = scope.launch {
            val dir = File(app.cacheDir, "pdl").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
            synchronized(claimed) { claimed.clear() }
            beat.clear(); itemJobs.clear()
            try {
                coroutineScope {
                    // baixados esperando envio: no máximo 3 (protege o armazenamento); downloads correm na frente do envio
                    val ready = Channel<Pair<PItem, File>>(DOWNLOADERS)
                    val resolvers = List(RESOLVERS) { n -> launch { delay(n * OPEN_GAP); resolverLoop() } }
                    val downs = List(DOWNLOADERS) { launch { downloaderLoop(dir, ready) } }
                    launch { downs.joinAll(); ready.close() }
                    repeat(UPLOADERS) { launch { for ((t, f) in ready) uploadOne(t, f) } }
                    launch { watchdog() }.also { w -> launch { resolvers.joinAll(); downs.joinAll(); w.cancel() } }
                }
            } catch (e: CancellationException) {
                synchronized(lock) {
                    items.value = items.value.map {
                        when (it.st) { PS.RESOLVING -> it.copy(st = PS.WAIT, prog = 0f, msg = "", speed = 0); PS.DOWN, PS.UP -> it.copy(st = if (it.f != null) PS.READY else PS.WAIT, prog = 0f, msg = "", speed = 0); else -> it }
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
                if (t.st != PS.DOWN && t.st != PS.UP) continue
                val last = beat[t.id] ?: continue
                val limit = if (t.st == PS.UP && t.prog >= 0.99f) 5 * 60_000L else STALL_MS   // envio já subiu: espera a confirmação do Telegram
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
                if (items.value.none { it.st in ACTIVE }) return
                delay(1_000); continue
            }
            var f: Found? = null
            for (attempt in 0..1) {
                if (attempt > 0) { upd(t.id) { it.copy(msg = "Tentando de novo…") }; delay(2_000) }
                f = try { withTimeoutOrNull(RESOLVE_TIMEOUT) { PageResolver.resolveFull(t.page, t.title, t.extra) } }
                    catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; null }
                if (f != null) break
            }
            if (f == null) upd(t.id) { it.copy(st = PS.FAIL, msg = "Não consegui carregar o vídeo desta página.") }
            else upd(t.id) { it.copy(f = f, title = TitleFix.pick(f.title, it.title).ifBlank { it.title }, st = PS.READY, msg = "") }
            delay(OPEN_GAP)   // devagar com o site
        }
    }

    private val claimed = HashSet<String>()
    private fun keysOf(t: PItem): Set<String> = (Jobs.sendKeys(t.f!!, t.keys) + MediaIdentity.keys(t.f)).toHashSet()

    private suspend fun downloaderLoop(dir: File, ready: Channel<Pair<PItem, File>>) {
        while (true) {
            val t = claim({ it.st == PS.READY && it.f != null }, PS.DOWN)
            if (t == null) {
                if (items.value.none { it.st in ACTIVE }) return
                delay(500); continue
            }
            val f = t.f!!
            if (Jobs.alreadySent(f, t.keys)) { upd(t.id) { it.copy(st = PS.DONE, msg = "Já enviado antes (pulado)", prog = 1f) }; continue }
            val ks = keysOf(t)
            val dup = synchronized(claimed) { if (ks.any { it in claimed }) true else { claimed.addAll(ks); false } }
            if (dup) { upd(t.id) { it.copy(st = PS.DONE, msg = "Repetido (mesmo vídeo, pulado)", prog = 1f) }; continue }
            alive(t.id)
            val job = scope.async { downloadOne(t, dir) }
            itemJobs[t.id] = job
            val file = try { job.await() } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()   // pausa/cancelar de verdade: propaga
                null.also { stalled(t, ks, "download") }
            } finally { itemJobs.remove(t.id); beat.remove(t.id) }
            if (file == null) { synchronized(claimed) { claimed.removeAll(ks) }; continue }
            ready.send(t to file)
        }
    }

    /** Item travado: volta para a fila (até 3 vezes), depois falha — os outros seguem. */
    private fun stalled(t: PItem, ks: Set<String>, stage: String) {
        synchronized(claimed) { claimed.removeAll(ks) }
        upd(t.id) { cur ->
            val n = cur.stalls + 1
            if (n >= 3) cur.copy(st = PS.FAIL, msg = "Travou no $stage 3 vezes (site/servidor sem resposta).", prog = 0f, speed = 0, stalls = n)
            else cur.copy(st = PS.READY, msg = "Travou no $stage, tentando de novo…", prog = 0f, speed = 0, stalls = n)
        }
    }

    private suspend fun downloadOne(t: PItem, dir: File): File? {
        val f = t.f!!
        var file: File? = null
        try {
            var last = 0L; var lastB = 0L; var lastT = System.currentTimeMillis(); var spd = 0L
            file = Downloader.download(f, dir, Prefs.server, Prefs.quality, fast = true) { got, tot ->
                alive(t.id)
                val now = System.currentTimeMillis()
                if (now - lastT >= 1000) { spd = (got - lastB) * 1000 / (now - lastT); lastB = got; lastT = now }
                if (now - last > 700) { last = now; upd(t.id, persist = false) { it.copy(prog = if (tot > 0) got.toFloat() / tot else 0f, speed = spd, msg = fmt(got) + (if (tot > 0) " de " + fmt(tot) else "")) } }
            }
            alive(t.id)
            if (Downloader.isHlsOutput(file)) {
                upd(t.id, persist = false) { it.copy(msg = "Convertendo para MP4…") }
                val src = file
                file = try { withContext(Dispatchers.IO) { Remux.finish(src, Downloader.audioOf(src)) } } catch (e: Exception) { if (e is CancellationException) throw e; src }
            }
            val size = file!!.length()
            if (size > 2000L * 1024 * 1024 && !Td.isPremium.value) throw DlException("Maior que 2 GB (limite do Telegram)")
            if (f.kind == Kind.PHOTO) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
                if (o.outWidth <= 0 || minOf(o.outWidth, o.outHeight) < Photos.MIN_SIDE) throw DlException("Imagem pequena (miniatura/ícone), ignorada.")
            } else {
                val k = Downloader.sniffFile(file)
                if (k == "image" || file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) throw DlException("O arquivo não é um vídeo (veio uma imagem (capa)).")
            }
            upd(t.id) { it.copy(prog = 1f, speed = 0, msg = "Baixado, esperando envio…") }
            return file
        } catch (e: CancellationException) { file?.delete(); throw e
        } catch (e: Exception) {
            file?.delete()
            upd(t.id) { it.copy(st = PS.FAIL, msg = e.message?.take(160) ?: "Erro ao baixar", speed = 0) }
            return null
        }
    }

    private suspend fun uploadOne(t0: PItem, file: File) {
        val t = items.value.firstOrNull { it.id == t0.id } ?: t0
        val f = t.f!!
        val ks = keysOf(t)
        try {
            upd(t.id) { it.copy(st = PS.UP, prog = 0f, speed = 0, msg = "Enviando…") }
            while (!Td.ready) { upd(t.id, persist = false) { it.copy(msg = "Esperando o Telegram conectar…") }; delay(3_000) }
            Uploader.savedChat()
            // Idempotência: confere de novo logo antes de enviar
            val fk = Jobs.fileKey(file)
            if ((fk != null && Sent.has(fk)) || Jobs.alreadySent(f, t.keys)) {
                upd(t.id) { it.copy(st = PS.DONE, prog = 1f, msg = "Repetido (mesmo arquivo já enviado, pulado)") }
                return
            }
            // Repetido pela imagem (muito rigoroso; na dúvida envia). Nunca apaga nada: só pula e deixa "Enviar mesmo assim".
            var vfp: VisualHash.Fp? = null
            if (Prefs.visualDedupe && f.kind != Kind.PHOTO && !t.force) {
                upd(t.id, persist = false) { it.copy(msg = "Comparando com os já enviados…") }
                vfp = try { withTimeoutOrNull(45_000) { withContext(Dispatchers.Default) { VisualHash.fingerprint(file, t.title) } } } catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e; null }
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
                currentCoroutineContext().ensureActive()
                stalled(t, ks, "envio"); return
            } finally { itemJobs.remove(t.id); beat.remove(t.id) }
            Sent.markSent(f, t.keys + listOfNotNull(fk)); History.add(f, size)
            if (vfp != null) VisualHash.markSent(t.id, vfp)
            else if (f.kind != Kind.PHOTO && Prefs.visualDedupe) {
                // enviado sem checagem (Enviar mesmo assim): guarda a impressão para os próximos
                try { withContext(Dispatchers.Default) { VisualHash.fingerprint(file, t.title) }?.let { VisualHash.markSent(t.id, it) } } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            upd(t.id) { it.copy(st = PS.DONE, prog = 1f, msg = "", speed = 0) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            synchronized(claimed) { claimed.removeAll(ks) }
            upd(t.id) { it.copy(st = PS.FAIL, msg = e.message?.take(160) ?: "Erro ao enviar", speed = 0) }
        } finally { VisualHash.release(t.id); withContext(NonCancellable) { file.delete(); Downloader.audioOf(file).delete() } }   // apaga o temporário sempre
    }

    fun fmt(b: Long): String = if (b >= 1 shl 30) "%.1f GB".format(b / 1073741824.0) else if (b >= 1 shl 20) "%.0f MB".format(b / 1048576.0) else "%.0f KB".format(b / 1024.0)
}
