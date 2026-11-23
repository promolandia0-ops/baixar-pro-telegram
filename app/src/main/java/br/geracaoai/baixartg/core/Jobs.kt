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
data class Item(val id: Int, val f: Found, val sel: Boolean = true, val st: St = St.WAIT, val prog: Float = 0f, val bytes: Long = 0, val total: Long = -1, val msg: String = "", val speed: Long = 0, val sent: Boolean = false, val xkeys: List<String> = emptyList())
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
    private fun canAuto(it: Item) = !it.sent && it.st != St.DONE && it.f.kind != Kind.PHOTO
    /** Seção Fotos: marca/desmarca só as fotos. */
    fun selectPhotos(v: Boolean) = items.update { l -> l.map { if (it.f.kind != Kind.PHOTO) it else { val nv = v && !it.sent && it.st != St.DONE; if (it.sel == nv) it else it.copy(sel = nv) } } }
    fun sendPhotos() { val ids = items.value.filter { it.f.kind == Kind.PHOTO && it.sel && !it.sent && it.st != St.DONE }.map { it.id }.toSet(); if (ids.isNotEmpty()) startTransfer(ids) }
    fun sendVideos() { val ids = items.value.filter { it.f.kind != Kind.PHOTO && it.sel && !it.sent && it.st != St.DONE }.map { it.id }.toSet(); if (ids.isNotEmpty()) startTransfer(ids) }
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
    /** Relatório da última busca (sem URL/domínio) para o botão "Copiar diagnóstico". */
    val diagReport = MutableStateFlow("")
    /** Último pedido: começar a baixar sozinho quando a busca terminar. */
    @Volatile private var autoAfter = false

    fun parseLinks(text: String): List<String> = text.split(Regex("""[\s,;]+""")).map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (it.startsWith("http", true) || it.startsWith("mega:", true)) it else "https://$it" }
        .filter { Mega.looksLikeMega(it) || Mega.isMegaLink(it) || Extract.abs(it, it) != null }.distinct()

    fun startCrawl(url: String) = startScan(listOf(url), Mode.SITE, false)

    /** Só roda quando o usuário toca no botão (nunca ao digitar). name = filtro por título/nome da aula. */
    fun startScan(urls0: List<String>, m: Mode, auto: Boolean, name: String = "") {
        if (job?.isActive == true) return
        val urls = urls0.map { u -> u.trim().let { if (it.startsWith("http", true) || it.startsWith("mega:", true)) it else "https://$it" } }.filter { it.length > 8 }.distinct()
        if (urls.isEmpty()) return
        Prefs.lastUrl = urls.first()
        mode.value = m; autoAfter = auto && name.isBlank() // busca por nome sempre para na prévia
        items.value = emptyList(); phase.value = Phase.CRAWLING; status.value = ""; stopping.value = false
        crawl.value = CrawlProgress(0, urls.size, 0, urls.first())
        WorkService.start(app)
        job = scope.launch {
            val server = Prefs.server
            val words = NameFilter.words(name)
            val opts = if (m == Mode.SITE) CrawlOpts(Prefs.depth.coerceIn(0, 20), Prefs.maxPages.coerceIn(1, 5000), Prefs.probe, 24, words, name, maxJsPages = 3, maxSniffs = 0, followCards = true, maxCardPages = 200, resolvePlayer = false) else CrawlOpts(0, 1, false, 24, words, name, maxJsPages = 3, maxSniffs = 0, followCards = true, maxCardPages = 200, resolvePlayer = true)
            val buf = ArrayList<Item>(); var n = 0; var lastFlush = 0L; var pagesBefore = 0
            var methodUsed = if (m == Mode.SITE && words.isNotEmpty()) "" else SiteSearch.METHOD_FULL_CRAWL
            val seen = HashSet<String>()
            fun flush() { if (buf.isNotEmpty()) { val add = buf.toList(); buf.clear(); items.update { it + add } } }
            var stopped = false
            val errors = ArrayList<String>()
            var skipped = 0
            Downloader.lastProbeError = null
            val diags = ArrayList<Diag>()
            diagReport.value = ""
            scope.launch { Downloader.warmUp(server) }
            fun keep(f: Found): Boolean {
                if (f.kind == Kind.PHOTO) { skipped++; return false }
                if (f.kind != Kind.PHOTO && Extract.isPhotoUrl(f.url) && !Extract.isVideoUrl(f.url)) { skipped++; return false }
                if (words.isEmpty() || methodUsed == SiteSearch.METHOD_SITE_SEARCH) return true
                if (NameFilter.matches(f, words)) return true
                skipped++; return false
            }
            val keyToId = HashMap<String, Int>()
            fun registerKeys(id: Int, f: Found) { MediaIdentity.keys(f).forEach { keyToId[it] = id } }
            fun replaceItem(id: Int, neu: Found) {
                val old = (buf.firstOrNull { it.id == id } ?: items.value.getOrNull(id))?.f
                if (old != null) MediaIdentity.keys(old).forEach { if (keyToId[it] == id) keyToId.remove(it) }
                val sent = Sent.isSent(neu) || MediaIdentity.keys(neu).any { k -> items.value.any { it.id == id && it.sent } }
                val iBuf = buf.indexOfFirst { it.id == id }
                if (iBuf >= 0) buf[iBuf] = buf[iBuf].copy(f = neu, sent = sent || buf[iBuf].sent, sel = if (sent || buf[iBuf].sent) false else buf[iBuf].sel)
                else set(id) { it.copy(f = neu, sent = sent || it.sent, sel = if (sent || it.sent) false else it.sel) }
                registerKeys(id, neu)
                seen.add(neu.url.substringBefore('#'))
            }
            fun accept(f0: Found): Boolean {
                val f = MediaIdentity.withQuality(f0)
                if (!keep(f)) return false
                val hit = MediaIdentity.keys(f).firstOrNull { it in keyToId }
                if (hit != null) {
                    val id = keyToId[hit]!!
                    val cur = buf.firstOrNull { it.id == id }?.f ?: items.value.getOrNull(id)?.f
                    if (cur != null && MediaIdentity.isBetter(f, cur)) replaceItem(id, f)
                    return false
                }
                if (!seen.add(f.url.substringBefore('#'))) return false
                val s = Sent.isSent(f) || Sent.hasCanonical(f)
                val item = Item(n++, f, sel = !s, sent = s)
                buf += item
                registerKeys(item.id, f)
                return true
            }
            try {
                for (u in urls) {
                    if (Mega.looksLikeMega(u) || Mega.isMegaLink(u)) {
                        crawl.value = crawl.value.copy(current = u)
                        try {
                            val found = Mega.resolvePublic(u)
                            if (found.isEmpty()) errors += "Nenhum arquivo encontrado neste link do MEGA."
                            found.forEach { (f, sz) -> if (keep(f) && seen.add(f.url)) { val s = Sent.isSent(f); buf += Item(n++, f, sel = !s, sent = s, total = sz) } }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { errors += e.message ?: "Erro no MEGA" }
                        pagesBefore++; flush(); continue
                    }
                    val dg = Diag().also { it.webviewAvailable = JsRender.available() }
                    diags += dg
                    val c = Crawler(
                        opts,
                        diag = dg,
                        sniff = { x -> Downloader.sniffType(x) },
                        jsFallback = { x -> JsRender.render(x, 20_000, play = true) },
                        probe = { p -> Downloader.probe(server, p) },
                        jsScroll = { x -> JsRender.render(x, 20_000, scroll = true) },
                        upgrade = { old, neu ->
                            val id = keyToId[MediaIdentity.keys(old).firstOrNull { it in keyToId } ?: ""]
                                ?: keyToId[MediaIdentity.keys(neu).firstOrNull { it in keyToId } ?: ""]
                            if (id != null) replaceItem(id, neu)
                        },
                    )
                    val onProg: (CrawlProgress) -> Unit = { p ->
                        crawl.value = p.copy(pagesDone = pagesBefore + p.pagesDone, queued = p.queued + (urls.size - urls.indexOf(u) - 1))
                        val now = System.currentTimeMillis(); if (now - lastFlush > 80) { lastFlush = now; flush() }
                    }
                    val onItem: (Found) -> Unit = { f ->
                        accept(f)
                    }
                    if (m == Mode.SITE && words.isNotEmpty()) {
                        methodUsed = SiteSearch.METHOD_SITE_SEARCH
                        crawl.value = crawl.value.copy(method = methodUsed, current = u)
                        val nSearch = c.runSiteSearch(u, onProg, onItem)
                        flush()
                        if (nSearch == 0 && items.value.isEmpty() && buf.isEmpty()) {
                            methodUsed = SiteSearch.METHOD_FULL_CRAWL
                            c.run(u, onProg, onItem)
                        }
                    } else {
                        methodUsed = if (m == Mode.SITE) SiteSearch.METHOD_FULL_CRAWL else methodUsed
                        c.run(u, onProg, onItem)
                    }
                    pagesBefore = crawl.value.pagesDone
                    flush()
                }
                // tamanho em segundo plano, depois da lista já aparecer
            } catch (e: CancellationException) { stopped = true; flush(); status.value = "Busca parada. ${items.value.size} itens encontrados até agora."
            } catch (e: Exception) { flush(); errors += e.message ?: "Erro na busca" }
            // "Fotos também": fotos de verdade das páginas, numa seção separada (não mexe nos vídeos)
            if (!stopped && Prefs.photos) try {
                val vids = items.value.filter { it.f.kind != Kind.PHOTO }
                val vt = vids.flatMap { Photos.idTokens(it.f.url) + Photos.idTokens(it.f.page) }.toHashSet()
                // só páginas de listagem/post (páginas de vídeo só têm capa/miniatura)
                val pages = urls.filter { it.startsWith("http") }.distinct().take(40)
                val vidPages = vids.map { Sent.norm(it.f.page) }.toHashSet()
                val pk = HashSet<String>()
                for (pg in pages) {
                    coroutineContext.ensureActive()
                    crawl.value = crawl.value.copy(current = "fotos: $pg")
                    val html = PageResolver.fetch(pg, null) ?: continue
                    // posts de foto (card com link para a página do post, sem nada de vídeo): abre o post e pega as fotos grandes
                    val posts = Photos.cardsFromHtml(pg, html).filter { Sent.norm(it.href) !in vidPages }.take(60)
                    val fromPosts = ArrayList<Pair<String, Photos.Photo>>()
                    for (c in posts) {
                        coroutineContext.ensureActive()
                        crawl.value = crawl.value.copy(current = "fotos: ${c.href}")
                        Photos.fromPost(c.href, c.text, null).forEach { fromPosts += c.href to it }
                    }
                    for ((post, p) in fromPosts) {
                        val k = Photos.key(p.url)
                        if (!pk.add(k)) continue
                        if (words.isNotEmpty() && !NameFilter.matchesText(p.text, words)) continue
                        val f = Found(p.url, post, Photos.title(p), Kind.PHOTO, ref = post, extra = p.text)
                        if (Sent.anySent(listOf(k, PhotoGrab.key(p.url))) || Sent.isQueued(listOf(k, PhotoGrab.key(p.url)))) continue
                        items.update { it + Item(it.size, f, sel = false, sent = false, xkeys = listOf(k)) }
                    }
                    for (p in Photos.fromHtml(pg, html)) {
                        val k = Photos.key(p.url)
                        if (!pk.add(k)) continue
                        if (words.isNotEmpty() && !NameFilter.matchesText(p.text, words)) continue
                        val f = Found(p.url, pg, Photos.title(p), Kind.PHOTO, ref = pg, extra = p.text)
                        if (Sent.anySent(listOf(k, PhotoGrab.key(p.url))) || Sent.isQueued(listOf(k, PhotoGrab.key(p.url)))) continue
                        items.update { it + Item(it.size, f, sel = false, sent = false, xkeys = listOf(k)) }
                    }
                }
            } catch (e: CancellationException) { stopped = true } catch (_: Exception) {}
            val total = items.value.count { it.f.kind != Kind.PHOTO }
            diagReport.value = diags.joinToString("\n") { it.report() } + "\nlista final: $total vídeos, $skipped descartados pelo filtro" + (if (errors.isNotEmpty()) "\nerros: " + errors.joinToString(" | ") { e -> e.replace(Regex("""https?://\S+"""), "<url>") } else "")
            val probeErr = Downloader.lastProbeError
            if (total == 0 && probeErr != null && errors.none { probeErr in it }) errors += probeErr
            val midia = "vídeos"
            if (!stopped) {
                val via = if (methodUsed.isNotBlank()) "$methodUsed. " else ""
                val diag = crawl.value.detail.let { if (it.isNotBlank()) " ($it)" else "" }
                status.value = when {
                total == 0 && words.isNotEmpty() -> via + "Nenhuma aula encontrada com esse nome (“${name.trim()}”)." + diag + (if (skipped > 0) " $skipped mídias no site não batiam com o título." else "") + (if (errors.isNotEmpty()) " " + errors.joinToString(" ") else "")
                total == 0 && errors.isNotEmpty() -> via + errors.joinToString("\n")
                total == 0 -> via + (if (m == Mode.SITE) "Nenhum vídeo ou foto encontrado. Tente ativar “Usar servidor (yt-dlp) em todas as páginas” em Ajustes." else "Nenhum vídeo ou foto encontrado nesta página.")
                words.isNotEmpty() -> via + "$total $midia com esse nome. Confira os títulos e toque em “Baixar e enviar”." + diag + (if (errors.isNotEmpty()) " " + errors.joinToString(" ") else "")
                errors.isNotEmpty() -> via + (if (m == Mode.SITE) "Varredura concluída: ${crawl.value.pagesDone} páginas lidas, $total $midia." else "$total $midia encontrado(s)" + (if (urls.size > 1) " em ${urls.size} links." else " nesta página.")) + " " + errors.joinToString(" ")
                m == Mode.SITE -> via + "Varredura concluída: ${crawl.value.pagesDone} páginas lidas, $total $midia." + diag
                else -> via + "$total $midia encontrado(s)" + (if (urls.size > 1) " em ${urls.size} links." else " nesta página.")
            }
            }
            if (!stopped && autoAfter && items.value.any { it.sel }) { job = null; startTransfer() }
            else {
                phase.value = Phase.LIST
                if (!stopped && (m != Mode.SITE || items.value.size <= 300)) scope.launch { fetchSizes() }
            }
        }
    }

    /** Tamanho antes de baixar (até 6 consultas por vez). */
    private suspend fun fetchSizes() = coroutineScope {
        val sem = kotlinx.coroutines.sync.Semaphore(6)
        items.value.filter { it.f.kind == Kind.DIRECT }.map { t -> launch { sem.acquire(); try { val sz = Downloader.sizeOf(t.f); if (sz > 0) set(t.id) { it.copy(total = sz) } } finally { sem.release() } } }.joinAll()
    }

    // ---------- Identidade do vídeo (fila idempotente) ----------
    /** Chaves persistentes (gravadas no "já enviado"): URL/mídia canônica, nome do arquivo, página do card. */
    fun sendKeys(f: Found, x: List<String> = emptyList()): List<String> = (Sent.keysOf(f) + x).distinct()
    /** Chaves de comparação na fila (inclui as do MediaIdentity: mesma mídia em outra qualidade/variante). */
    private fun queueKeys(t: Item): Set<String> = (sendKeys(t.f, t.xkeys) + MediaIdentity.keys(t.f)).toHashSet()
    fun alreadySent(f: Found, x: List<String> = emptyList()) = Sent.anySent(sendKeys(f, x)) || Sent.hasCanonical(f)
    private val busyStates = setOf(St.DOWN, St.READY, St.UP)
    /** Já está na fila/enviando/enviado (qualquer lista). */
    fun isQueuedOrSent(f: Found, x: List<String> = emptyList()): Boolean {
        if (alreadySent(f, x)) return true
        val k = (sendKeys(f, x) + MediaIdentity.keys(f)).toHashSet()
        val running = job?.isActive == true
        return items.value.any { (it.st in busyStates || it.st == St.DONE || it.sent || (running && it.st == St.WAIT)) && queueKeys(it).any { q -> q in k } }
    }
    @Volatile private var lastEnqueueAt = 0L

    /**
     * Itens vindos de fora da varredura (MEGA, Navegador). Nunca duplica: ignora o que já foi enviado,
     * o que já está na fila e repetidos dentro da própria lista (fica o de maior qualidade).
     * Devolve quantos vídeos entraram para envio, ou -1 se há tarefa em andamento / toque repetido.
     */
    fun enqueue(list: List<Triple<Found, Long, List<String>>>, start: Boolean): Int {
        if (job?.isActive == true || list.isEmpty()) return -1
        val now = System.currentTimeMillis()
        if (start && now - lastEnqueueAt < 1500) return -1   // toque duplo
        lastEnqueueAt = now
        mode.value = Mode.LINKS
        val base = if (phase.value == Phase.LIST || phase.value == Phase.IDLE) items.value else emptyList()
        val baseKeys = base.map { it to queueKeys(it) }
        // repetidos dentro da lista: fica o de maior qualidade
        val picked = ArrayList<Triple<Found, Long, List<String>>>()
        val pickedKeys = ArrayList<Set<String>>()
        for (e in list) {
            val f = MediaIdentity.withQuality(e.first)
            val k = (sendKeys(f, e.third) + MediaIdentity.keys(f)).toHashSet()
            val i = pickedKeys.indexOfFirst { pk -> pk.any { it in k } }
            if (i >= 0) { if (MediaIdentity.isBetter(f, picked[i].first)) { picked[i] = Triple(f, e.second, e.third); pickedKeys[i] = pickedKeys[i] + k }; continue }
            picked += Triple(f, e.second, e.third); pickedKeys += k
        }
        val startIds = LinkedHashSet<Int>()
        val add = ArrayList<Item>()
        var skipped = 0
        for ((idx, e) in picked.withIndex()) {
            val (f, sz, x) = e
            if (alreadySent(f, x)) { skipped++; continue }
            val hit = baseKeys.firstOrNull { (_, bk) -> bk.any { it in pickedKeys[idx] } }?.first
            if (hit != null) {
                if (hit.sent || hit.st == St.DONE || hit.st in busyStates) { skipped++; continue }
                startIds += hit.id; continue          // mesmo vídeo já está na lista: usa o item existente
            }
            val it = Item(base.size + add.size, f, sel = true, sent = false, total = sz, xkeys = x)
            add += it; startIds += it.id
        }
        items.value = base + add
        phase.value = Phase.LIST
        status.value = "${startIds.size} vídeo(s) para enviar" + (if (skipped > 0) " • $skipped repetido(s)/já enviado(s) ignorado(s)" else "") + "."
        if (start && startIds.isNotEmpty()) startTransfer(startIds)
        return startIds.size
    }

    /** Itens vindos de fora da varredura (MEGA): entram na lista com o tamanho já conhecido. */
    fun addExternal(list: List<Pair<Found, Long>>, start: Boolean): Boolean =
        enqueue(list.map { Triple(it.first, it.second, emptyList()) }, start) >= 0

    /** Reenvia um vídeo do histórico (baixa de novo do site e manda pro Telegram). */
    fun resend(f: Found): Boolean {
        if (job?.isActive == true) return false
        val id = items.value.size
        items.update { it + Item(id, f, sel = true, sent = true) }
        if (phase.value == Phase.IDLE) phase.value = Phase.LIST
        startTransfer(setOf(id), force = true); return true
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

    /** force = reenvio pedido no Histórico (único caso que manda de novo algo já enviado). */
    fun startTransfer(only: Set<Int>? = null, force: Boolean = false) {
        if (job?.isActive == true) return
        val cand = items.value.filter { (if (only != null) it.id in only else it.sel) && it.st != St.DONE && it.st !in busyStates && (force || (!it.sent && !alreadySent(it.f, it.xkeys))) }
        // mesmo vídeo duas vezes na seleção (outra qualidade/variante): fica só o melhor
        val todo = ArrayList<Item>(); val todoKeys = ArrayList<Set<String>>()
        val dupIds = HashSet<Int>()
        for (t in cand) {
            val k = queueKeys(t)
            val i = todoKeys.indexOfFirst { tk -> tk.any { it in k } }
            if (i < 0) { todo += t; todoKeys += k; continue }
            if (MediaIdentity.isBetter(t.f, todo[i].f)) { dupIds += todo[i].id; todo[i] = t; todoKeys[i] = todoKeys[i] + k } else dupIds += t.id
        }
        if (dupIds.isNotEmpty()) items.update { l -> l.map { if (it.id in dupIds) it.copy(sel = false, msg = "Repetido (mesmo vídeo)") else it } }
        if (todo.isEmpty()) return
        forceIds = if (force) todo.map { it.id }.toHashSet() else emptySet()
        claimed.clear()
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
                    val nDown = Prefs.parDown.coerceIn(1, 10); val nUp = Prefs.parUp.coerceIn(1, 5)
                    val queue = Channel<Item>(Channel.UNLIMITED); todo.forEach { queue.trySend(it) }; queue.close()
                    // fila limitada: no máximo nDown baixando + nDown prontos esperando envio (protege o armazenamento)
                    val ready = Channel<Pair<Item, File>>(nDown)
                    val downs = List(nDown) { launch { for (t0 in queue) {
                        val t = items.value.getOrNull(t0.id) ?: t0
                        if (skipIfDuplicate(t)) { st(); continue }
                        val f = downloadOne(t); if (f == null) { fail.incrementAndGet(); st() } else ready.send(t to f) } } }
                    launch { downs.joinAll(); ready.close() }
                    repeat(nUp) { launch { for ((t, f) in ready) { if (uploadOne(t, f)) ok.incrementAndGet() else if (items.value.getOrNull(t.id)?.st != St.DONE) fail.incrementAndGet(); st() } } }
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

    @Volatile private var forceIds: Set<Int> = emptySet()
    /** Chaves "em uso" nesta tarefa: dois itens do mesmo vídeo nunca baixam/enviam juntos. */
    private val claimed = HashSet<String>()

    /** Já enviado ou outro item da mesma tarefa já pegou este vídeo: marca e pula (sem baixar). */
    private fun skipIfDuplicate(t: Item): Boolean {
        if (t.id in forceIds) return false
        val sent = alreadySent(t.f, t.xkeys)
        val k = queueKeys(t)
        val taken = synchronized(claimed) { if (!sent && k.none { it in claimed }) { claimed.addAll(k); false } else true }
        if (!sent && !taken) return false
        set(t.id) { it.copy(st = St.DONE, prog = 1f, sel = false, sent = true, msg = if (sent) "Já enviado antes (pulado)" else "Repetido (mesmo vídeo, pulado)") }
        return true
    }

    /** Impressão do arquivo: tamanho + SHA-256 do começo e do fim (mesmo arquivo = mesmo vídeo). */
    fun fileKey(f: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(f, "r").use { r ->
            val buf = ByteArray(1 shl 20)
            val n1 = r.read(buf); if (n1 > 0) md.update(buf, 0, n1)
            if (r.length() > 2L * buf.size) { r.seek(r.length() - buf.size); val n2 = r.read(buf); if (n2 > 0) md.update(buf, 0, n2) }
        }
        "h:" + f.length() + ":" + md.digest().take(12).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

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
            if (t.f.kind == Kind.PHOTO) {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
                if (o.outWidth > 0 && maxOf(o.outWidth, o.outHeight) < 200) throw DlException("Imagem pequena (miniatura/ícone), ignorada.")
            }
            if (t.f.kind != Kind.PHOTO) {
                val k = Downloader.sniffFile(file)
                val ext = file.extension.lowercase()
                if (k == "image" || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                    throw DlException("O arquivo não é um vídeo (veio uma imagem (capa)).")
                }
            }
            set(t.id) { it.copy(st = St.READY, prog = 1f, bytes = size, total = size, msg = "") }
            return file
        } catch (e: CancellationException) { file?.delete(); set(t.id) { it.copy(st = St.STOPPED, msg = "Parado") }; throw e
        } catch (e: Exception) { file?.delete(); set(t.id) { it.copy(st = St.ERR, msg = e.message?.take(160) ?: "Erro") }; return null }
    }

    private suspend fun uploadOne(t: Item, file: File): Boolean {
        try {
            set(t.id) { it.copy(st = St.UP, prog = 0f) }
            val cap = t.f.title + "\n" + t.f.page + (if (t.f.url != t.f.page && !Bunkr.isCdn(t.f.url) && t.f.kind != Kind.MEGA) "\n" + t.f.url else "")
            if (t.f.kind != Kind.PHOTO) {
                val k = Downloader.sniffFile(file)
                val ext = file.extension.lowercase()
                if (k == "image" || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                    throw DlException("O arquivo não é um vídeo (veio uma imagem (capa)).")
                }
            }
            // Idempotência: confere de novo logo antes de enviar (outra tarefa/lista pode ter enviado o mesmo vídeo)
            val fk = fileKey(file)
            if (t.id !in forceIds) {
                val dupFile = fk != null && (Sent.has(fk) || synchronized(claimed) { !claimed.add(fk) })
                if (dupFile || (if (t.f.kind == Kind.PHOTO) Sent.anySent(t.xkeys + PhotoGrab.key(t.f.url)) else alreadySent(t.f, t.xkeys))) {
                    file.delete()
                    set(t.id) { it.copy(st = St.DONE, prog = 1f, sel = false, sent = true, msg = "Repetido (mesmo arquivo já enviado, pulado)") }
                    return false
                }
            }
            // Envio único: sem nova tentativa depois que o TDLib aceitou a mensagem
            Uploader.upload(file, cap, asVideo = t.f.kind != Kind.PHOTO) { p -> set(t.id) { it.copy(prog = p) } }
            if (t.f.kind == Kind.PHOTO) Sent.markPhotoSent(t.xkeys + PhotoGrab.key(t.f.url) + listOfNotNull(fk)) else Sent.markSent(t.f, t.xkeys + listOfNotNull(fk)); History.add(t.f, file.length())
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
    /** Apaga só a lista visível do Histórico (os envios feitos). A memória de “já enviado” continua. */
    fun clearHistoryList() { History.clear(); syncStatus.value = "Lista do histórico apagada." }
    /** Apaga a memória de “já enviado”: os itens voltam a poder ser baixados no navegador. */
    fun clearSentMemory() { Sent.clearSentOnly(); Prefs.synced = false; items.update { l -> l.map { if (it.sent) it.copy(sent = false) else it } }; syncStatus.value = "Memória de já enviado apagada." }

    fun stop() { stopping.value = true; job?.cancel() }
    val busy get() = job?.isActive == true
}
