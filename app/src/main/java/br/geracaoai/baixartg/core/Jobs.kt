package br.geracaoai.baixartg.core

import android.content.Context
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.tg.Td
import br.geracaoai.baixartg.tg.Uploader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class St { WAIT, DOWN, READY, UP, DONE, ERR, STOPPED }
data class Item(val id: Int, val f: Found, val sel: Boolean = true, val st: St = St.WAIT, val prog: Float = 0f, val bytes: Long = 0, val total: Long = -1, val msg: String = "", val speed: Long = 0, val sent: Boolean = false)
enum class Phase { IDLE, CRAWLING, LIST, RUNNING }

object Jobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    val phase = MutableStateFlow(Phase.IDLE)
    val crawl = MutableStateFlow(CrawlProgress(0, 0, 0, ""))
    val items = MutableStateFlow<List<Item>>(emptyList())
    val status = MutableStateFlow("")
    val stopping = MutableStateFlow(false)
    @Volatile private var job: Job? = null

    fun init(c: Context) { app = c.applicationContext; Sent.init(app.filesDir); History.init(app.filesDir) }

    /** id == posição na lista, então a atualização é O(1) por índice (rápido com 5000 itens). */
    private fun set(id: Int, f: (Item) -> Item) = items.update { l -> if (id !in l.indices) l else l.toMutableList().also { it[id] = f(it[id]) } }
    fun toggle(id: Int) = set(id) { it.copy(sel = !it.sel) }
    /** "Marcar" nunca pega os já enviados (dá pra marcar um por um na mão). */
    private fun canAuto(it: Item) = !it.sent && it.st != St.DONE
    fun selectAll(v: Boolean) = items.update { l -> l.map { val nv = v && canAuto(it); if (it.sel == nv) it else it.copy(sel = nv) } }
    /** Marca/desmarca só os ids informados (ex.: resultado do filtro por nome). */
    fun setSel(ids: Set<Int>, v: Boolean) = items.update { l -> l.map { if (it.id in ids && it.sel != v && (!v || canAuto(it))) it.copy(sel = v) else it } }
    /** Deixa marcados SOMENTE esses ids. */
    fun onlySel(ids: Set<Int>) = items.update { l -> l.map { val v = it.id in ids && canAuto(it); if (it.sel != v) it.copy(sel = v) else it } }
    /** Marca os próximos N ainda não marcados (e não enviados). */
    fun markNext(n: Int) = items.update { l -> var left = n; l.map { if (left > 0 && !it.sel && canAuto(it)) { left--; it.copy(sel = true) } else it } }
    fun clear() { if (job?.isActive == true) return; items.value = emptyList(); phase.value = Phase.IDLE; status.value = "" }

    /** SITE = varre o site inteiro (mesmo domínio); PAGE = só a página informada; LINKS = vários links colados (cada um só a própria página). */
    enum class Mode { SITE, PAGE, LINKS }
    val mode = MutableStateFlow(Mode.SITE)
    /** Último pedido: começar a baixar sozinho quando a busca terminar. */
    @Volatile private var autoAfter = false

    fun parseLinks(text: String): List<String> = text.split(Regex("""[\s,;]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (it.startsWith("http", true)) it else "https://$it" }.filter { Extract.abs(it, it) != null }.distinct()

    fun startCrawl(url: String) = startScan(listOf(url), Mode.SITE, false)

    /** Só roda quando o usuário toca no botão (nunca ao digitar). */
    fun startScan(urls0: List<String>, m: Mode, auto: Boolean) {
        if (job?.isActive == true) return
        val urls = urls0.map { u -> u.trim().let { if (it.startsWith("http", true)) it else "https://$it" } }.filter { it.length > 8 }.distinct()
        if (urls.isEmpty()) return
        Prefs.lastUrl = urls.first()
        mode.value = m; autoAfter = auto
        items.value = emptyList(); phase.value = Phase.CRAWLING; status.value = ""; stopping.value = false
        crawl.value = CrawlProgress(0, urls.size, 0, urls.first())
        WorkService.start(app)
        job = scope.launch {
            val server = Prefs.server
            val opts = if (m == Mode.SITE) CrawlOpts(Prefs.depth.coerceIn(0, 20), Prefs.maxPages.coerceIn(1, 5000), Prefs.probe, 6) else CrawlOpts(0, 1, false, 6)
            val buf = ArrayList<Item>(); var n = 0; var lastFlush = 0L; var pagesBefore = 0
            val seen = HashSet<String>()
            fun flush() { if (buf.isNotEmpty()) { val add = buf.toList(); buf.clear(); items.update { it + add } } }
            var stopped = false
            try {
                for (u in urls) {
                    if (Mega.isMegaLink(u)) { // MEGA: arquivos criptografados, lista via API oficial
                        crawl.value = crawl.value.copy(current = u)
                        Mega.resolvePublic(u).forEach { (f, sz) -> if (seen.add(f.url)) { val s = Sent.isSent(f); buf += Item(n++, f, sel = !s, sent = s, total = sz) } }
                        pagesBefore++; flush(); continue
                    }
                    val c = Crawler(opts) { p -> Downloader.probe(server, p) }
                    c.run(u, { p -> crawl.value = p.copy(pagesDone = pagesBefore + p.pagesDone, queued = p.queued + (urls.size - urls.indexOf(u) - 1))
                        val now = System.currentTimeMillis(); if (now - lastFlush > 400) { lastFlush = now; flush() } }) { f ->
                        if (seen.add(f.url.substringBefore('#'))) { val s = Sent.isSent(f); buf += Item(n++, f, sel = !s, sent = s) } }
                    pagesBefore = crawl.value.pagesDone
                    flush()
                }
                if (m != Mode.SITE || items.value.size <= 300) {
                    status.value = "Verificando o tamanho dos arquivos…"
                    fetchSizes()
                }
            } catch (e: CancellationException) { stopped = true; flush(); status.value = "Busca parada. ${items.value.size} vídeos encontrados até agora."
            } catch (e: Exception) { flush(); status.value = "Erro na busca: ${e.message}" }
            val total = items.value.size
            if (!stopped) status.value = when {
                total == 0 -> if (m == Mode.SITE) "Nenhum vídeo encontrado. Tente ativar “Usar servidor (yt-dlp) em todas as páginas” em Ajustes." else "Nenhum vídeo encontrado nesta página."
                m == Mode.SITE -> "Varredura concluída: ${crawl.value.pagesDone} páginas lidas, $total vídeos."
                else -> "$total vídeo(s) encontrado(s)" + (if (urls.size > 1) " em ${urls.size} links." else " nesta página.")
            }
            if (!stopped && autoAfter && items.value.any { it.sel }) { job = null; startTransfer() } else phase.value = Phase.LIST
        }
    }

    /** Tamanho antes de baixar (até 6 consultas por vez). */
    private suspend fun fetchSizes() = coroutineScope {
        val sem = kotlinx.coroutines.sync.Semaphore(6)
        items.value.filter { it.f.kind == Kind.DIRECT }.map { t -> launch { sem.acquire(); try { val sz = Downloader.sizeOf(t.f); if (sz > 0) set(t.id) { it.copy(total = sz) } } finally { sem.release() } } }.joinAll()
    }

    /** Itens vindos de fora da varredura (MEGA): entram na lista com o tamanho já conhecido. */
    fun addExternal(list: List<Pair<Found, Long>>, start: Boolean): Boolean {
        if (job?.isActive == true || list.isEmpty()) return false
        mode.value = Mode.LINKS
        val base = if (phase.value == Phase.LIST || phase.value == Phase.IDLE) items.value else emptyList()
        val have = base.map { it.f.url }.toHashSet()
        val add = list.filter { it.first.url !in have }.mapIndexed { i, (f, sz) -> val s = Sent.isSent(f); Item(base.size + i, f, sel = true, sent = s, total = sz) }
        items.value = base + add
        phase.value = Phase.LIST; status.value = "${add.size} vídeo(s) do MEGA adicionados."
        if (start) startTransfer(add.map { it.id }.toSet())
        return true
    }

    /** Reenvia um vídeo do histórico (baixa de novo do site e manda pro Telegram). */
    fun resend(f: Found): Boolean {
        if (job?.isActive == true) return false
        val id = items.value.size
        items.update { it + Item(id, f, sel = true, sent = true) }
        if (phase.value == Phase.IDLE) phase.value = Phase.LIST
        startTransfer(setOf(id)); return true
    }

    /** Testa o Telegram (conta conectada) e, se informado, o token do bot. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        try {
            if (!Td.ready) sb.append("✗ Conta do Telegram não conectada.")
            else { val me = Td.send(org.drinkless.tdlib.TdApi.GetMe()); Uploader.savedChat()
                sb.append("✓ Telegram conectado: ${(me.firstName + " " + me.lastName).trim()}" + (me.usernames?.activeUsernames?.firstOrNull()?.let { " (@$it)" } ?: "") + ". Mensagens Salvas OK.") }
        } catch (e: Exception) { sb.append("✗ Erro no Telegram: ${e.message}") }
        val tok = Prefs.botToken.trim()
        if (tok.isNotEmpty()) {
            sb.append("\n")
            try {
                Net.http.newCall(okhttp3.Request.Builder().url("https://api.telegram.org/bot$tok/getMe").build()).await().use { r ->
                    val j = org.json.JSONObject(r.body?.string() ?: "{}")
                    if (j.optBoolean("ok")) sb.append("✓ Bot OK: @" + j.getJSONObject("result").optString("username"))
                    else sb.append("✗ Token do bot inválido: " + j.optString("description"))
                }
            } catch (e: Exception) { sb.append("✗ Sem conexão com api.telegram.org: ${e.message}") }
        }
        sb.toString()
    }

    fun startTransfer(only: Set<Int>? = null) {
        if (job?.isActive == true) return
        val todo = items.value.filter { if (only != null) it.id in only else it.sel && it.st != St.DONE }
        if (todo.isEmpty()) return
        phase.value = Phase.RUNNING; stopping.value = false
        val ids = todo.map { it.id }.toHashSet()
        items.update { l -> l.map { if (it.id in ids) it.copy(st = St.WAIT, prog = 0f, msg = "") else it } }
        WorkService.start(app)
        job = scope.launch {
            val dir = File(app.cacheDir, "dl").apply { mkdirs(); listFiles()?.forEach { it.deleteRecursively() } }
            val ok = AtomicInteger(); val fail = AtomicInteger()
            fun st() { status.value = "${ok.get() + fail.get()} de ${todo.size} concluídos • ${ok.get()} enviados" + (if (fail.get() > 0) " • ${fail.get()} com erro" else "") }
            try {
                if (!Td.ready) throw IllegalStateException("Telegram não conectado")
                Uploader.savedChat()
                st()
                coroutineScope {
                    val nDown = Prefs.parDown.coerceIn(1, 8); val nUp = Prefs.parUp.coerceIn(1, 4)
                    val queue = Channel<Item>(Channel.UNLIMITED); todo.forEach { queue.trySend(it) }; queue.close()
                    // fila limitada: no máximo nDown baixando + nDown prontos esperando envio (protege o armazenamento)
                    val ready = Channel<Pair<Item, File>>(nDown)
                    val downs = List(nDown) { launch { for (t in queue) { val f = downloadOne(t); if (f == null) { fail.incrementAndGet(); st() } else ready.send(t to f) } } }
                    launch { downs.joinAll(); ready.close() }
                    repeat(nUp) { launch { for ((t, f) in ready) { if (uploadOne(t, f)) ok.incrementAndGet() else fail.incrementAndGet(); st() } } }
                }
                status.value = "Pronto! ${ok.get()} enviados para Mensagens Salvas" + (if (fail.get() > 0) ", ${fail.get()} com erro" else "") + "."
            } catch (e: CancellationException) {
                items.update { l -> l.map { if (it.st == St.WAIT || it.st == St.DOWN || it.st == St.UP || it.st == St.READY) it.copy(st = St.STOPPED, msg = "Parado") else it } }
                status.value = "Parado. ${ok.get()} enviados."
            } catch (e: Exception) { status.value = "Erro: ${e.message}" }
            withContext(NonCancellable) { dir.listFiles()?.forEach { it.deleteRecursively() } }
            phase.value = Phase.LIST; stopping.value = false
        }
    }

    /** Baixa (e, se veio de HLS, remuxa para MP4 sem recodificar). null = erro (já marcado). */
    private suspend fun downloadOne(t: Item): File? {
        var file: File? = null
        try {
            set(t.id) { it.copy(st = St.DOWN, prog = 0f, msg = "") }
            var lastT = System.currentTimeMillis(); var lastB = 0L; var spd = 0L
            file = Downloader.download(t.f, File(app.cacheDir, "dl"), Prefs.server, Prefs.quality) { got, tot ->
                val now = System.currentTimeMillis(); if (now - lastT >= 1000) { spd = (got - lastB) * 1000 / (now - lastT); lastT = now; lastB = got }
                set(t.id) { it.copy(bytes = got, total = tot, prog = if (tot > 0) got.toFloat() / tot else 0f, speed = spd) }
            }
            if (Downloader.isHlsOutput(file)) {
                set(t.id) { it.copy(msg = "Convertendo para MP4 (sem perder qualidade)…") }
                val src = file
                file = try { withContext(Dispatchers.IO) { Remux.finish(src, Downloader.audioOf(src)) } } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    src // se o remux falhar, envia o original como arquivo
                }
            }
            val size = file!!.length()
            if (size > 2000L * 1024 * 1024 && !Td.isPremium.value) throw DlException("Maior que 2 GB (limite do Telegram)")
            set(t.id) { it.copy(st = St.READY, prog = 1f, bytes = size, total = size, msg = "") }
            return file
        } catch (e: CancellationException) { file?.delete(); set(t.id) { it.copy(st = St.STOPPED, msg = "Parado") }; throw e
        } catch (e: Exception) { file?.delete(); set(t.id) { it.copy(st = St.ERR, msg = e.message?.take(160) ?: "Erro") }; return null }
    }

    private suspend fun uploadOne(t: Item, file: File): Boolean {
        try {
            set(t.id) { it.copy(st = St.UP, prog = 0f) }
            val cap = t.f.title + "\n" + t.f.page + (if (t.f.url != t.f.page && !Bunkr.isCdn(t.f.url) && t.f.kind != Kind.MEGA) "\n" + t.f.url else "")
            Uploader.upload(file, cap) { p -> set(t.id) { it.copy(prog = p) } }
            Sent.markSent(t.f); History.add(t.f, file.length())
            set(t.id) { it.copy(st = St.DONE, prog = 1f, msg = "", sent = true) }; return true
        } catch (e: CancellationException) { set(t.id) { it.copy(st = St.STOPPED, msg = "Parado") }; throw e
        } catch (e: Exception) { set(t.id) { it.copy(st = St.ERR, msg = e.message?.take(160) ?: "Erro") }; return false
        } finally { withContext(NonCancellable) { file.delete(); Downloader.audioOf(file).delete() } }
    }

    // ---------- Histórico x Telegram ----------
    val syncStatus = MutableStateFlow("")
    @Volatile private var syncJob: Job? = null

    /** Lê as legendas das Mensagens Salvas e importa os links já enviados (inclusive de versões antigas). */
    fun syncTelegram(auto: Boolean = false) {
        if (syncJob?.isActive == true) return
        if (auto && Prefs.synced) return
        syncJob = scope.launch {
            try {
                if (!Td.awaitReady(60_000)) { syncStatus.value = "Telegram não conectado"; return@launch }
                syncStatus.value = "Lendo Mensagens Salvas…"
                val chat = Uploader.savedChat()
                var from = 0L; var read = 0; var added = 0; var empty = 0
                while (isActive) {
                    val r = Td.send(org.drinkless.tdlib.TdApi.GetChatHistory(chat, from, 0, 100, false))
                    if (r.messages.isEmpty()) { if (++empty >= 3) break; delay(700); continue }
                    empty = 0
                    val caps = r.messages.mapNotNull { m -> when (val c = m.content) {
                        is org.drinkless.tdlib.TdApi.MessageVideo -> c.caption.text
                        is org.drinkless.tdlib.TdApi.MessageDocument -> c.caption.text
                        is org.drinkless.tdlib.TdApi.MessageText -> c.text.text
                        else -> null } }
                    added += Sent.importCaptions(caps); read += r.messages.size
                    from = r.messages.last().id
                    syncStatus.value = "Lidas $read mensagens • $added links novos no histórico"
                }
                Prefs.synced = true
                syncStatus.value = "Sincronizado: $read mensagens lidas, ${Sent.size} chaves no histórico."
                refreshSent()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { syncStatus.value = "Erro ao sincronizar: ${e.message}" }
        }
    }

    /** Reaplica o histórico na lista atual (desmarca o que já foi enviado). */
    fun refreshSent() {
        if (phase.value == Phase.RUNNING) return
        items.update { l -> l.map { val s = Sent.isSent(it.f); if (s && !it.sent) it.copy(sent = true, sel = false) else if (!s && it.sent) it.copy(sent = false) else it } }
    }

    fun clearHistory() { Sent.clear(); Prefs.synced = false; items.update { l -> l.map { if (it.sent) it.copy(sent = false) else it } }; syncStatus.value = "Histórico apagado." }

    fun stop() { stopping.value = true; job?.cancel() }
    val busy get() = job?.isActive == true
}
