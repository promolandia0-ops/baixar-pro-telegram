package br.geracaoai.baixartg.core

import android.content.Context
import android.content.Intent
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.tg.Td
import br.geracaoai.baixartg.tg.Uploader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class St { WAIT, DOWN, UP, DONE, ERR, STOPPED }
data class Item(val id: Int, val f: Found, val sel: Boolean = true, val st: St = St.WAIT, val prog: Float = 0f, val bytes: Long = 0, val total: Long = -1, val msg: String = "")
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

    private fun set(id: Int, f: (Item) -> Item) = items.update { l -> l.map { if (it.id == id) f(it) else it } }
    fun toggle(id: Int) = set(id) { it.copy(sel = !it.sel) }
    fun selectAll(v: Boolean) = items.update { l -> l.map { it.copy(sel = v) } }
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
            val c = Crawler(CrawlOpts(Prefs.depth, Prefs.maxPages, Prefs.probe)) { p -> Downloader.probe(server, p) }
            var n = 0
            try {
                val total = c.run(u, { crawl.value = it }) { f -> items.update { it + Item(n++, f) } }
                status.value = if (total == 0) "Nenhum vídeo encontrado. Tente ativar “Usar servidor (yt-dlp) em todas as páginas”." else "Busca concluída: ${crawl.value.pagesDone} páginas lidas, $total vídeos."
            } catch (e: CancellationException) { status.value = "Busca parada. ${items.value.size} vídeos encontrados até agora."
            } catch (e: Exception) { status.value = "Erro na busca: ${e.message}" }
            phase.value = Phase.LIST
        }
    }

    fun startTransfer() {
        if (job?.isActive == true) return
        val todo = items.value.filter { it.sel && it.st != St.DONE }
        if (todo.isEmpty()) return
        phase.value = Phase.RUNNING; stopping.value = false
        todo.forEach { t -> set(t.id) { it.copy(st = St.WAIT, prog = 0f, msg = "") } }
        WorkService.start(app)
        job = scope.launch {
            val dir = File(app.cacheDir, "dl").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var ok = 0; var fail = 0
            try {
                if (!Td.ready) throw IllegalStateException("Telegram não conectado")
                for ((i, t) in todo.withIndex()) {
                    ensureActive()
                    status.value = "Vídeo ${i + 1} de ${todo.size}"
                    var file: File? = null
                    try {
                        set(t.id) { it.copy(st = St.DOWN, prog = 0f, msg = "") }
                        file = Downloader.download(t.f, dir, Prefs.server) { got, tot -> set(t.id) { it.copy(bytes = got, total = tot, prog = if (tot > 0) got.toFloat() / tot else 0f) } }
                        val size = file.length()
                        if (size > 2000L * 1024 * 1024 && !Td.isPremium.value) throw DlException("Maior que 2 GB (limite do Telegram)")
                        set(t.id) { it.copy(st = St.UP, prog = 0f, bytes = size, total = size) }
                        val cap = t.f.title + "\n" + t.f.page + (if (t.f.url != t.f.page) "\n" + t.f.url else "")
                        Uploader.upload(file, cap) { p -> set(t.id) { it.copy(prog = p) } }
                        set(t.id) { it.copy(st = St.DONE, prog = 1f, msg = "") }; ok++
                    } catch (e: CancellationException) { set(t.id) { it.copy(st = St.STOPPED, msg = "Parado") }; throw e
                    } catch (e: Exception) { fail++; set(t.id) { it.copy(st = St.ERR, msg = e.message?.take(160) ?: "Erro") }
                    } finally { withContext(NonCancellable) { file?.delete() } }
                }
                status.value = "Pronto! $ok enviados para Mensagens Salvas" + (if (fail > 0) ", $fail com erro" else "") + "."
            } catch (e: CancellationException) {
                items.update { l -> l.map { if (it.st == St.WAIT || it.st == St.DOWN || it.st == St.UP) it.copy(st = St.STOPPED, msg = "Parado") else it } }
                status.value = "Parado. $ok enviados."
            } catch (e: Exception) { status.value = "Erro: ${e.message}" }
            withContext(NonCancellable) { dir.listFiles()?.forEach { it.delete() } }
            phase.value = Phase.LIST; stopping.value = false
        }
    }

    fun stop() { stopping.value = true; job?.cancel() }
    val busy get() = job?.isActive == true
}
