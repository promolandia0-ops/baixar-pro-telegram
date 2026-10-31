package br.geracaoai.baixartg.core

import android.content.Context
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.tg.Td
import br.geracaoai.baixartg.tg.Uploader
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class St { WAIT, DOWN, READY, UP, DONE, ERR, STOPPED }
data class Item(val id: Int, val f: Found, val sel: Boolean = true, val st: St = St.WAIT, val prog: Float = 0f, val bytes: Long = 0, val total: Long = -1, val msg: String = "", val speed: Long = 0)
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

    fun init(c: Context) { app = c.applicationContext }

    /** id == posição na lista, então a atualização é O(1) por índice (rápido com 5000 itens). */
    private fun set(id: Int, f: (Item) -> Item) = items.update { l -> if (id !in l.indices) l else l.toMutableList().also { it[id] = f(it[id]) } }
    fun toggle(id: Int) = set(id) { it.copy(sel = !it.sel) }
    fun selectAll(v: Boolean) = items.update { l -> l.map { if (it.sel == v) it else it.copy(sel = v) } }
    /** Marca/desmarca só os ids informados (ex.: resultado do filtro por nome). */
    fun setSel(ids: Set<Int>, v: Boolean) = items.update { l -> l.map { if (it.id in ids && it.sel != v) it.copy(sel = v) else it } }
    /** Deixa marcados SOMENTE esses ids. */
    fun onlySel(ids: Set<Int>) = items.update { l -> l.map { val v = it.id in ids; if (it.sel != v) it.copy(sel = v) else it } }
    /** Marca os próximos N ainda não marcados (e não enviados). */
    fun markNext(n: Int) = items.update { l -> var left = n; l.map { if (left > 0 && !it.sel && it.st != St.DONE) { left--; it.copy(sel = true) } else it } }
    fun clear() { if (job?.isActive == true) return; items.value = emptyList(); phase.value = Phase.IDLE; status.value = "" }

    fun startCrawl(url: String) {
        if (job?.isActive == true) return
        var u = url.trim(); if (!u.startsWith("http", true)) u = "https://$u"
        Prefs.lastUrl = u
        items.value = emptyList(); phase.value = Phase.CRAWLING; status.value = ""; stopping.value = false
        crawl.value = CrawlProgress(0, 1, 0, u)
        WorkService.start(app)
        job = scope.launch {
            val server = Prefs.server
            val c = Crawler(CrawlOpts(Prefs.depth, Prefs.maxPages, Prefs.probe, 6)) { p -> Downloader.probe(server, p) }
            val buf = ArrayList<Item>(); var n = 0; var lastFlush = 0L
            fun flush() { if (buf.isNotEmpty()) { val add = buf.toList(); buf.clear(); items.update { it + add } } }
            try {
                val total = c.run(u, { crawl.value = it; val now = System.currentTimeMillis(); if (now - lastFlush > 400) { lastFlush = now; flush() } }) { f -> buf += Item(n++, f) }
                flush()
                status.value = if (total == 0) "Nenhum vídeo encontrado. Tente ativar “Usar servidor (yt-dlp) em todas as páginas”." else "Busca concluída: ${crawl.value.pagesDone} páginas lidas, $total vídeos."
            } catch (e: CancellationException) { flush(); status.value = "Busca parada. ${items.value.size} vídeos encontrados até agora."
            } catch (e: Exception) { flush(); status.value = "Erro na busca: ${e.message}" }
            phase.value = Phase.LIST
        }
    }

    fun startTransfer() {
        if (job?.isActive == true) return
        val todo = items.value.filter { it.sel && it.st != St.DONE }
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
            file = Downloader.download(t.f, File(app.cacheDir, "dl"), Prefs.server) { got, tot ->
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
            val cap = t.f.title + "\n" + t.f.page + (if (t.f.url != t.f.page && !Bunkr.isCdn(t.f.url)) "\n" + t.f.url else "")
            Uploader.upload(file, cap) { p -> set(t.id) { it.copy(prog = p) } }
            set(t.id) { it.copy(st = St.DONE, prog = 1f, msg = "") }; return true
        } catch (e: CancellationException) { set(t.id) { it.copy(st = St.STOPPED, msg = "Parado") }; throw e
        } catch (e: Exception) { set(t.id) { it.copy(st = St.ERR, msg = e.message?.take(160) ?: "Erro") }; return false
        } finally { withContext(NonCancellable) { file.delete(); Downloader.audioOf(file).delete() } }
    }

    fun stop() { stopping.value = true; job?.cancel() }
    val busy get() = job?.isActive == true
}
