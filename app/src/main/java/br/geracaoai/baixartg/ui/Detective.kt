package br.geracaoai.baixartg.ui

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject

/**
 * DETETIVE de vídeos e fotos.
 * Descobre por que a página não mostra o vídeo e corrige sozinho, camada por camada, até achar:
 * login (espera você entrar e volta para a aula), sessão/cookies, ganchos de detecção em todos os quadros
 * (inclusive players de outro site dentro de iframe), permissão de autoplay, avisos de cookies/idade, janelas por cima,
 * varredura profunda (vídeos, iframes, recursos já carregados, scripts, atributos, configs de player, respostas de rede),
 * ~40 receitas de players e plataformas (Panda, Vimeo, Bunny, Wistia, JW, Vturb, Hotmart, Kaltura, Brightcove,
 * Cloudflare, Mux, YouTube, Drive…), abertura dos players embutidos pela rede com o Referer certo, HTML da página com
 * o login, play em todos os quadros, players preguiçosos, servidor yt-dlp com a origem certa, DRM, recarregar com a
 * detecção desde o início, abrir a aula quando a página é só a lista do curso e fotos na maior versão.
 * Cada endereço achado é CONFERIDO (baixa o comecinho e vê se é vídeo/foto de verdade) e testado com vários Referer
 * até um funcionar — o mesmo vai junto no download. O que funcionou fica guardado por site e é tentado primeiro.
 */
object Detective {
    enum class St { RUNNING, OK, FIXED, FAIL, INFO, SKIP }
    data class Step(val title: String, val st: St, val detail: String = "")
    enum class Mode { FULL, QUIET }
    enum class CK { MEDIA, JSON, PAGE, SERVER, BRIGHTCOVE }
    data class Cand(val url: String, val ck: CK, val t: DetScan.T?, val via: String, val frame: String?, val ref: DetKnowledge.Ref?, val recipe: String = "", val depth: Int = 0)
    class Hooks(val tapPlay: () -> Unit, val collect: () -> Unit)
    interface Log { fun begin(t: String); fun done(st: St, d: String = ""); fun note(d: String) }
    private object NoLog : Log { override fun begin(t: String) {}; override fun done(st: St, d: String) {}; override fun note(d: String) {} }
    data class Result(val videos: Int, val photos: Int, val verdict: String, val lessonUrl: String? = null, val lessonTitle: String = "")

    /** Texto curto na barra do navegador quando o detetive roda sozinho. */
    val quietStatus = mutableStateOf("")
    @Volatile private var busy = false
    private val quietDone = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Roda sozinho (sem janela, sem clicar em nada) quando a página fica sem vídeo. Uma vez por página. */
    suspend fun runQuiet(web: WebView, hooks: Hooks) {
        val page = web.url ?: return
        if (busy || !page.startsWith("http") || !quietDone.add(PageKey.norm(page))) return
        quietStatus.value = "Detetive procurando…"
        try {
            run(web, Mode.QUIET, hooks, NoLog)
        } catch (e: Exception) { if (e is CancellationException) throw e } finally { quietStatus.value = "" }
    }

    suspend fun run(web: WebView, mode: Mode, hooks: Hooks, log: Log): Result {
        if (busy && mode == Mode.QUIET) return Result(0, 0, "")
        busy = true
        try { return Session(web, mode, hooks, log).go() } finally { busy = false }
    }

    // =====================================================================================================

    private class Session(val web: WebView, val mode: Mode, val hooks: Hooks, val log: Log) {
        val full = mode == Mode.FULL
        var page: String = web.url.orEmpty()
        var title: String = web.title.orEmpty()
        val host get() = Extract.host(page)
        val startV = videos(); val startP = BrowserDetect.photos.size
        val cands = LinkedHashMap<String, Cand>()
        val tested = HashSet<String>()
        val photoUrls = LinkedHashMap<String, String>()
        val players = LinkedHashSet<String>()
        val recipesHit = LinkedHashSet<String>()
        val wins = LinkedHashSet<String>()
        val blocked = LinkedHashMap<String, Int>()
        var verifiedV = 0
        var drm = ""
        var frames = 0
        var pw = 0
        var lessons = listOf<Pair<String, String>>()
        var bigFrames = 0
        var videoTags = 0
        var blobTags = 0
        var tests = 0
        val maxTests = if (full) 160 else 60
        val t0 = System.currentTimeMillis()
        var bodyText = ""

        fun videos() = BrowserDetect.list.count { !it.sent }
        fun gotV() = videos() > startV
        fun gotP() = BrowserDetect.photos.size > startP
        fun gotAny() = gotV() || (BrowserDetect.photosOn.value && BrowserDetect.mode.intValue == 1 && gotP())
        suspend fun waitFor(ms: Long): Boolean { var t = 0L; while (t < ms) { if (gotV()) return true; delay(400); t += 400 }; return gotV() }
        suspend fun eval(js: String, ms: Long = 8_000) = DetBridge.unq(DetBridge.eval(web, js, ms))

        // ------------------------------------------------------------------ candidatos
        fun addCand(c: Cand) {
            val u = c.url.substringBefore('#')
            if (!u.startsWith("http")) return
            if (MediaPick.isAdUrl(u) || br.geracaoai.baixartg.web.AdBlock.isAdHost(Extract.host(u))) return
            if (TitleFix.isSubtitleUrl(u)) return
            val k = c.ck.name + "|" + u
            if (cands.containsKey(k) || k in tested) return
            cands[k] = c.copy(url = u)
        }

        /** Endereços achados num texto (de qualquer quadro/resposta) viram candidatos. */
        suspend fun addText(text: String, base: String, via: String, frame: String?, depth: Int = 0): Int {
            if (text.isBlank()) return 0
            // leitura pesada (até 1,5 MB de texto) fora da thread da tela
            val (hits, leads, drmH) = withContext(Dispatchers.Default) {
                Triple(DetScan.urlsIn(text, base, max = 500), DetKnowledge.leads(DetScan.unescape(text)),
                    DetKnowledge.drmHint(text) && Regex("""(?i)licen[cs]e|keySystem|drm""").containsMatchIn(text))
            }
            hits.forEach { addHit(it, via, frame, depth) }
            leads.forEach { addLead(it, via, frame, depth) }
            if (drmH) drmMaybe = true
            return hits.size + leads.size
        }
        var drmMaybe = false

        fun addHit(h: DetScan.Hit, via: String, frame: String?, depth: Int) {
            when (h.t) {
                DetScan.T.HLS, DetScan.T.DASH, DetScan.T.FILE, DetScan.T.MAYBE -> addCand(Cand(h.url, CK.MEDIA, h.t, via, frame, null, depth = depth))
                DetScan.T.IMAGE -> if (photoUrls.size < 600) photoUrls.putIfAbsent(h.url, "")
                DetScan.T.EMBED -> DetKnowledge.leads(h.url).forEach { addLead(it, via, frame, depth) }
                DetScan.T.AUDIO -> {}
            }
        }

        fun addLead(l: DetKnowledge.Lead, via: String, frame: String?, depth: Int) {
            recipesHit += l.recipe
            DetKnowledge.all().firstOrNull { it.id == l.recipe }?.let { players += it.name }
            val ck = when (l.kind) { DetKnowledge.LeadKind.MEDIA -> CK.MEDIA; DetKnowledge.LeadKind.JSON -> CK.JSON; DetKnowledge.LeadKind.PAGE -> CK.PAGE; DetKnowledge.LeadKind.SERVER -> CK.SERVER; DetKnowledge.LeadKind.BRIGHTCOVE -> CK.BRIGHTCOVE }
            val fr = if (l.kind == DetKnowledge.LeadKind.MEDIA) (frame ?: l.url) else frame
            addCand(Cand(l.url, ck, if (ck == CK.MEDIA) DetScan.classify(l.url) ?: DetScan.T.MAYBE else null, "receita:${l.recipe}", fr, l.ref, l.recipe, depth))
        }

        fun pending(vararg ks: CK) = cands.values.filter { it.ck in ks && (it.ck.name + "|" + it.url) !in tested }

        // ------------------------------------------------------------------ rede
        data class Got(val code: Int, val ct: String, val bytes: ByteArray, val url: String)

        suspend fun get(url: String, ref: String?, max: Int, range: Boolean, headers: Map<String, String> = emptyMap()): Got? = withContext(Dispatchers.IO) {
            try {
                val b = Request.Builder().url(url).header("Accept", "*/*")
                if (range) b.header("Range", "bytes=0-" + (max - 1))
                if (ref != null) { b.header("Referer", ref); DetKnowledge.originOf(ref)?.let { b.header("Origin", it.trimEnd('/')) } }
                headers.forEach { (k, v) -> b.header(k, v) }
                Net.fast.newCall(b.build()).await().use { r ->
                    val out = java.io.ByteArrayOutputStream()
                    r.body?.byteStream()?.use { ins ->
                        val buf = ByteArray(16_384)
                        while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
                    }
                    Got(r.code, r.header("Content-Type").orEmpty(), out.toByteArray(), r.request.url.toString())
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; null }
        }

        /** Confere um candidato. Vídeo de verdade entra na lista (com o Referer que funcionou). */
        suspend fun test(c: Cand) {
            tested += c.ck.name + "|" + c.url
            tests++
            when (c.ck) {
                CK.MEDIA -> testMedia(c)
                CK.JSON, CK.PAGE -> {
                    val refs = DetKnowledge.refererOrder(c.ref, page, c.frame).take(3)
                    for (ref in refs) {
                        val g = get(c.url, ref, 3_000_000, range = false) ?: continue
                        if (g.code == 401 || g.code == 403) { blocked[Extract.host(c.url)] = g.code; continue }
                        if (g.code !in 200..299) break
                        val sn = DetScan.sniff(g.bytes, g.ct)
                        if (DetScan.isVideoSniff(sn)) { testMedia(c.copy(ck = CK.MEDIA, t = DetScan.T.MAYBE)); return }
                        val txt = String(g.bytes, Charsets.UTF_8)
                        if (c.depth < 2) addText(txt, g.url, c.via, if (c.ck == CK.PAGE) c.url else (c.frame ?: c.url), c.depth + 1)
                        return
                    }
                }
                CK.SERVER -> testServer(c)
                CK.BRIGHTCOVE -> testBrightcove(c)
            }
        }

        suspend fun testMedia(c: Cand) {
            val maybe = c.t == DetScan.T.MAYBE
            val refs = DetKnowledge.refererOrder(c.ref, page, c.frame).take(if (maybe) 2 else 4)
            for (ref in refs) {
                val g = get(c.url, ref, if (maybe) 600_000 else 96_000, range = true) ?: continue
                if (g.code == 401 || g.code == 403 || g.code == 410) { blocked[Extract.host(c.url)] = g.code; continue }
                if (g.code == 404 || g.code !in 200..299) return
                val sn = DetScan.sniff(g.bytes, g.ct)
                when {
                    sn == "hls" -> {
                        val info = DetScan.hlsInfo(String(g.bytes, Charsets.UTF_8))
                        if (info.drm) { drm = "lista HLS com proteção DRM"; return }
                        if (info.audioOnly) return
                        if (!info.master && Regex("""(?i)/(?:audio|aud)[_\-/]""").containsMatchIn(c.url)) return
                        publish(c, Kind.HLS, ref, info.maxHeight); return
                    }
                    sn == "dash" -> {
                        if (DetScan.dashDrm(String(g.bytes, Charsets.UTF_8))) { drm = "lista DASH com proteção DRM"; return }
                        RefererHints.set(c.url, ref); publish(c, Kind.SERVER, ref, 0); return
                    }
                    DetScan.isVideoSniff(sn) -> { publish(c, Kind.DIRECT, ref, 0); return }
                    DetScan.isImageSniff(sn) -> { photoUrls.putIfAbsent(c.url, ""); return }
                    sn == "json" || sn == "text" || sn == "html" -> {
                        if (c.depth < 2) addText(String(g.bytes, Charsets.UTF_8), g.url, c.via, c.frame ?: c.url, c.depth + 1)
                        return
                    }
                    else -> return
                }
            }
        }

        suspend fun testServer(c: Cand) {
            val ref = DetKnowledge.refFor(c.ref ?: DetKnowledge.Ref.PAGE, page, c.frame)
            RefererHints.set(c.url, ref)
            val t = withTimeoutOrNull(if (full) 50_000 else 30_000) { Downloader.probe(Prefs.server, c.url) }
            if (t != null) {
                withContext(Dispatchers.Main) { BrowserDetect.addFound(Found(c.url, page, TitleFix.pick(title, t).ifBlank { t }, Kind.SERVER, ref = page, extra = title)) }
                verifiedV++; wins += "servidor"; if (c.recipe.isNotBlank()) recipesHit += c.recipe
            } else Downloader.lastProbeError?.let { lastServerErr = it }
        }
        var lastServerErr = ""

        suspend fun testBrightcove(c: Cand) {
            val (acc, player, embed, vid) = c.url.split('|').let { if (it.size == 4) it else return }
            val js = get("https://players.brightcove.net/$acc/${player}_$embed/index.min.js", page, 3_000_000, range = false) ?: return
            val key = Regex("""policyKey\s*:\s*["'](BCpk[^"']+)""").find(String(js.bytes, Charsets.UTF_8))?.groupValues?.get(1) ?: return
            val api = "https://edge.api.brightcove.com/playback/v1/accounts/$acc/videos/" + (if (vid.all { it.isDigit() }) vid else "ref:$vid")
            val g = get(api, page, 2_000_000, range = false, headers = mapOf("Accept" to "application/json;pk=$key")) ?: return
            if (g.code in 200..299) addText(String(g.bytes, Charsets.UTF_8), api, c.via, page, c.depth + 1)
        }

        suspend fun publish(c: Cand, kind: Kind, ref: String?, height: Int) {
            val r = ref ?: DetKnowledge.originOf(c.url)
            if (kind == Kind.SERVER) RefererHints.set(c.url, r)
            val f = Found(c.url, page, title.ifBlank { Extract.mediaTitle(c.url, "") }, kind, ref = r, extra = title, quality = height)
            withContext(Dispatchers.Main) { BrowserDetect.addFound(f) }
            verifiedV++
            wins += c.via.substringBefore(':').let { if (it == "receita") "receita" else it }
            if (c.recipe.isNotBlank()) recipesHit += c.recipe
        }

        /** Confere tudo o que está pendente (vídeos primeiro, depois configs/páginas, por último os "talvez"). */
        suspend fun verifyAll(budgetMs: Long, servers: Boolean = false): Int {
            val before = verifiedV
            val end = System.currentTimeMillis() + budgetMs
            val gate = Semaphore(6)
            var rounds = 0
            while (System.currentTimeMillis() < end && tests < maxTests && rounds < 4) {
                rounds++
                val sure = pending(CK.MEDIA).filter { it.t != DetScan.T.MAYBE }
                val cfg = pending(CK.JSON, CK.PAGE, CK.BRIGHTCOVE)
                val maybe = pending(CK.MEDIA).filter { it.t == DetScan.T.MAYBE }.take(30)
                val batch = (sure + cfg + maybe).take(maxTests - tests)
                if (batch.isEmpty()) break
                batch.forEach { tested += it.ck.name + "|" + it.url }
                coroutineScope { batch.map { c -> async { gate.withPermit { withTimeoutOrNull(20_000) { tested -= c.ck.name + "|" + c.url; test(c) } } } }.awaitAll() }
            }
            if (servers) {
                val sv = pending(CK.SERVER).sortedBy { if (it.recipe.isNotBlank()) 0 else 1 }.take(if (full) 4 else 2)
                for (c in sv) { if (System.currentTimeMillis() > end + 60_000) break; test(c); if (verifiedV > before && !full) break }
            }
            return verifiedV - before
        }

        // ------------------------------------------------------------------ varredura dos quadros
        suspend fun deepScan(via: String): Int {
            val rs = DetBridge.ask(web, "scan", 2_500, DetKnowledge.globals)
            frames = maxOf(frames, rs.size)
            var n = 0
            for (o in rs) {
                val f = o.optString("f").ifBlank { page }
                val isTop = o.optInt("top") == 1
                if (isTop) { title = o.optString("title").ifBlank { title }; pw = o.optInt("pw"); bodyText = o.optString("body") }
                // pedir licença DRM não prova DRM (Vimeo/Shaka só testam se o aparelho suporta): só uma suspeita
                if (o.optString("drm").isNotBlank()) drmMaybe = true
                o.optJSONArray("players")?.let { a -> for (i in 0 until a.length()) players += a.optString(i) }
                o.optJSONArray("videos")?.let { a ->
                    for (i in 0 until a.length()) {
                        val v = a.optJSONObject(i) ?: continue
                        videoTags++
                        if (v.optInt("blob") == 1) blobTags++
                        val s = v.optString("s")
                        if (s.startsWith("http")) { addCand(Cand(s, CK.MEDIA, DetScan.classify(s) ?: DetScan.T.MAYBE, via, f, DetKnowledge.Ref.FRAME)); n++ }
                        v.optJSONArray("src")?.let { ss -> for (k in 0 until ss.length()) ss.optString(k).takeIf { it.startsWith("http") }?.let { u -> addCand(Cand(u, CK.MEDIA, DetScan.classify(u) ?: DetScan.T.MAYBE, via, f, DetKnowledge.Ref.FRAME)); n++ } }
                        v.optString("p").takeIf { it.startsWith("http") }?.let { photoUrls.putIfAbsent(it, title) }
                    }
                }
                o.optJSONArray("iframes")?.let { a ->
                    for (i in 0 until a.length()) {
                        val fr = a.optJSONObject(i) ?: continue
                        val src = fr.optString("s").ifBlank { fr.optString("ds") }
                        if (!src.startsWith("http")) continue
                        if (fr.optInt("w") >= 120 && fr.optInt("h") >= 80 || fr.optString("ds").isNotBlank()) bigFrames++
                        val ls = DetKnowledge.leads(src)
                        if (ls.isNotEmpty()) ls.forEach { addLead(it, "iframe", f, 0) }
                        else if ((fr.optInt("w") >= 120 && fr.optInt("h") >= 80) && !br.geracaoai.baixartg.web.AdBlock.isAdHost(Extract.host(src))) {
                            addCand(Cand(src, CK.PAGE, null, "iframe", f, DetKnowledge.Ref.PAGE))
                            addCand(Cand(src, CK.SERVER, null, "iframe", f, DetKnowledge.Ref.PAGE))
                        }
                        n++
                    }
                }
                o.optJSONArray("res")?.let { a -> n += addText((0 until a.length()).joinToString("\n") { a.optString(it) }, f, "recursos", f) }
                n += addText(o.optString("txt"), f, via, f)
                players += DetKnowledge.identify(o.optString("txt").take(400_000) + " " + f)
                if (isTop) o.optJSONArray("a")?.let { a ->
                    val ls = ArrayList<Triple<String, String, Int>>()
                    for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; val h = x.optString("h"); val t = x.optString("t")
                        if (PageKey.norm(h) == PageKey.norm(page) || Extract.host(h) != host) continue
                        val sc = DetKnowledge.lessonScore(h, t); if (sc >= 3) ls += Triple(h, t, sc) }
                    lessons = ls.sortedByDescending { it.third }.map { it.first to it.second }.distinctBy { PageKey.norm(it.first) }.take(30)
                }
            }
            if (DetBridge.drm.isNotBlank()) drmMaybe = true
            return n
        }

        suspend fun netScan(): Int {
            var n = 0
            for (h in DetBridge.netSince(DetBridge.pageStart)) n += addText(h.body, h.url.ifBlank { h.frame }, "rede", h.frame.ifBlank { page })
            for (u in DetBridge.mediaSeen()) { addCand(Cand(u, CK.MEDIA, DetScan.classify(u) ?: DetScan.T.MAYBE, "rede", null, null)); n++ }
            return n
        }

        // ------------------------------------------------------------------ fotos
        suspend fun photoLayer() {
            log.begin("Fotos (maior versão de cada uma, em todos os quadros)")
            val rs = DetBridge.ask(web, "photos", 2_500)
            val ps = LinkedHashMap<String, Photos.Photo>()
            for (o in rs) o.optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; val u = x.optString("u")
                    if (u.startsWith("http") && !ps.containsKey(u)) ps[u] = Photos.Photo(u, x.optString("a"), x.optInt("w"), x.optInt("h")) }
            }
            for ((u, t) in photoUrls) if (!ps.containsKey(u)) ps[u] = Photos.Photo(u, t, 0, 0)
            if (!BrowserDetect.photosOn.value) {
                log.done(St.INFO, if (ps.isEmpty()) "Nenhuma foto." else "${ps.size} foto(s) na página. Para baixar fotos, escolha \"Vídeos e fotos\" no painel.")
                return
            }
            val before = BrowserDetect.photos.size
            if (ps.isNotEmpty()) withContext(Dispatchers.Main) { BrowserDetect.onPhotos(page, ps.values.toList()) }
            var t = 0L; while (t < 8_000 && BrowserDetect.photos.size == before && ps.isNotEmpty()) { delay(400); t += 400 }
            val got = BrowserDetect.photos.size - before
            log.done(if (got > 0) St.FIXED else if (ps.isEmpty()) St.FAIL else St.INFO,
                if (got > 0) "$got foto(s) nova(s) na lista (de ${ps.size} achadas; miniaturas e repetidas ficam de fora)." else if (ps.isEmpty()) "Nenhuma foto achada." else "${ps.size} achada(s), mas eram miniaturas, ícones ou já estavam na lista.")
            if (got > 0) wins += "fotos"
        }

        // ------------------------------------------------------------------ esperas e navegação
        suspend fun waitLoad(maxMs: Long = 25_000) {
            delay(600)
            var t = 0L
            while (t < maxMs && web.progress < 100) { delay(300); t += 300 }
            delay(1_500)
            page = web.url.orEmpty(); title = web.title.orEmpty()
        }

        suspend fun snap(): JSONObject = try { JSONObject(eval(VideoDoctor.SNAP_JS)) } catch (_: Exception) { JSONObject() }

        // ================================================================== as camadas
        suspend fun go(): Result {
            try { DetMemory.init(web.context) } catch (_: Exception) {}
            CrashLog.mark("detetive (${mode.name.lowercase()}) iniciado em ${CrashLog.siteTag(page)}")
            val mem = DetMemory.load(host)

            // 0) receitas atualizadas + memória do site
            if (full) {
                log.begin("Estudo atualizado (receitas de players)")
                try { withTimeoutOrNull(8_000) { DetRecipes.ensure(web.context) } } catch (_: Exception) {}
                log.done(St.OK, "${DetKnowledge.builtIn.size} receitas no app • ${DetRecipes.lastInfo.ifBlank { "sem receitas extra" }}")
                if (mem != null) { log.begin("Memória deste site"); log.done(St.INFO, "Já achei vídeo aqui ${mem.hits}x. Funcionou: ${mem.wins.joinToString()}${if (mem.players.isNotEmpty()) " • player: " + mem.players.take(3).joinToString() else ""}. Tento isso primeiro.") }
            } else try { DetRecipes.ensure(web.context) } catch (_: Exception) {}

            // 1) página
            log.begin("Página carregada")
            var s = snap()
            if (!page.startsWith("http") || s.length() == 0) { log.done(St.FAIL, "A página não respondeu. Recarregue e tente de novo."); return result("A página não respondeu.") }
            log.done(St.OK, if (s.optString("ready") == "complete") "pronta" else "ainda carregando")

            // 2) login: espera você entrar e volta para a aula
            if (full && (s.optInt("login") > 0 || DetKnowledge.isLoginUrl(page))) {
                val target = previousNonLogin()
                log.begin("Login")
                log.done(St.INFO, "Esta página pede login. Entre na sua conta normalmente — eu espero e continuo sozinho.")
                log.begin("Esperando você entrar na conta…")
                val loginUrl = page
                var t = 0L; var ok = false
                while (t < 300_000) {
                    delay(2_000); t += 2_000
                    val u = web.url.orEmpty()
                    if (web.progress < 100) continue
                    val st = snap()
                    if (st.optInt("login") == 0 && !DetKnowledge.isLoginUrl(u) && (u != loginUrl || !DetKnowledge.isLoginUrl(loginUrl))) { ok = true; break }
                }
                if (!ok) { log.done(St.FAIL, "Passaram 5 minutos sem login."); return result("Não continuei: a página ainda pede login. Entre na conta e toque em Diagnosticar de novo.") }
                page = web.url.orEmpty()
                if (target != null && PageKey.norm(target) != PageKey.norm(page)) {
                    log.done(St.FIXED, "Login feito. Voltei para a página que você tinha aberto.")
                    web.loadUrl(target); waitLoad()
                } else { log.done(St.FIXED, "Login feito."); waitLoad(8_000) }
                s = snap()
            }

            // 3) sessão
            kotlin.run {
                log.begin("Sessão (cookies do login)")
                val cm = CookieManager.getInstance()
                var fixed = ""
                try { if (!cm.acceptThirdPartyCookies(web)) { cm.setAcceptThirdPartyCookies(web, true); fixed = "Liguei os cookies de players de outro site. " } } catch (_: Exception) {}
                val n = (cm.getCookie(page) ?: "").split(';').count { it.isNotBlank() }
                log.done(if (fixed.isNotBlank()) St.FIXED else if (n > 0) St.OK else St.INFO, fixed + if (n > 0) "$n cookie(s) deste site vão junto em cada pedido." else "Nenhum cookie. Se o vídeo é só para alunos, entre na conta.")
            }

            // 4) detecção em todos os quadros
            log.begin("Detecção ligada em todos os quadros")
            val hookWas = s.optInt("hook") == 1
            if (!hookWas) eval(MseHookScript.SCRIPT)
            val injected = DetBridge.ensureTop(web)
            log.done(if (!hookWas || injected) St.FIXED else St.OK, buildString {
                append(if (DetBridge.installed) "ponte com os quadros ativa" else "aparelho sem ponte entre quadros: só o quadro principal")
                if (!hookWas || injected) append(" • religuei a detecção nesta página")
            })
            val needReload = !hookWas || injected

            // 5) autoplay
            if (full) {
                log.begin("Permissão de tocar sozinho")
                val was = web.settings.mediaPlaybackRequiresUserGesture
                web.settings.mediaPlaybackRequiresUserGesture = false
                log.done(if (was) St.FIXED else St.OK, if (was) "Players podiam se recusar a tocar sem toque; liberei." else "")
            }

            // 6) descartados
            val disc = synchronized(BrowserDiag.discarded) { BrowserDiag.discarded.toList() }
            if (disc.isNotEmpty()) {
                log.begin("Vídeos vistos mas descartados")
                disc.forEach { (u, _) -> addCand(Cand(u, CK.MEDIA, DetScan.classify(u) ?: DetScan.T.MAYBE, "descartados", null, null)) }
                log.done(St.INFO, "${disc.size} endereço(s) (${disc.map { it.second }.distinct().joinToString()}). Vou conferir cada um de verdade.")
            }

            if (full) {
                // 7) aviso de cookies / 8) idade / 9) janelas por cima
                if (s.optInt("consent") == 1) {
                    log.begin("Aviso de cookies")
                    val n = eval(VideoDoctor.CONSENT_JS).toIntOrNull() ?: 0
                    log.done(if (n > 0) St.FIXED else St.INFO, if (n > 0) "Aceitei o aviso." else "Não achei o botão de aceitar.")
                }
                if (s.optInt("age") == 1) {
                    log.begin("Confirmação de idade"); eval(AgeGate.BYPASS_JS); log.done(St.FIXED, "Confirmei.")
                }
                log.begin("Janelas por cima do player")
                val ub = DetBridge.ask(web, "unblock", 1_200).sumOf { it.optInt("n") }
                log.done(if (ub > 0) St.FIXED else St.OK, if (ub > 0) "Fechei $ub janela(s)." else "nenhuma")
            }

            // memória: o que funcionou antes vem primeiro
            if (full && mem != null && "play" in mem.wins) { playAll(); waitFor(4_000) }

            discover()
            if (full) log.begin("Conferir cada endereço (baixa o começo e testa vários Referer)")
            val v1 = verifyAll(if (full) 60_000 else 35_000)
            if (full) log.done(if (v1 > 0) St.FIXED else if (tests == 0) St.INFO else St.FAIL, verifyDetail(v1))
            if (drm.isNotBlank() && !gotV()) return drmResult()

            if (full && !gotV()) {
                // 15) players preguiçosos
                log.begin("Carregar players que só aparecem depois")
                val lz = DetBridge.ask(web, "lazy", 1_200).sumOf { it.optInt("n") }
                if (lz > 0) delay(3_000)
                log.done(if (lz > 0) St.FIXED else St.OK, if (lz > 0) "Ativei $lz player(s)/quadro(s) que esperavam." else "nenhum")
                // 16) play em todos os quadros
                log.begin("Dar play (sem som) em todos os quadros, inclusive players embutidos")
                val np = playAll()
                val ok = waitFor(7_000)
                if (ok) { wins += "play"; log.done(St.FIXED, "O player pediu o vídeo depois do play.") }
                else {
                    discover(quiet = true)
                    val v2 = verifyAll(35_000)
                    if (v2 > 0) wins += "play"
                    log.done(if (gotV()) St.FIXED else St.FAIL, if (gotV()) "Depois do play apareceu o vídeo." else "Dei play em $np player(s), mas nenhum pediu vídeo.")
                }
                if (drm.isNotBlank() && !gotV()) return drmResult()
            }

            if (full && !gotV()) {
                // 17) rolar até o player
                log.begin("Mostrar o player na tela e tocar de novo")
                eval(VideoDoctor.SCROLL_JS); playAll(); hooks.collect()
                if (waitFor(4_000)) { wins += "play"; log.done(St.FIXED, "O player carregou quando apareceu na tela.") } else log.done(St.FAIL)
            }

            // 18) HTML da página pela rede e código no navegador
            if (!gotV()) {
                if (full) log.begin("Ler a página pela rede (com o seu login) e o código no navegador")
                var n = 0
                withTimeoutOrNull(20_000) {
                    get(page, page, 4_000_000, range = false)?.let { g -> if (g.code in 200..299) n += addText(String(g.bytes, Charsets.UTF_8), g.url, "html", page) }
                }
                val html = eval("(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()", 10_000)
                n += addText(html, page, "código", page)
                try { SiteAnalyzer.mediaUrlsInHtml(page, html).forEach { addCand(Cand(it, CK.MEDIA, DetScan.classify(it) ?: DetScan.T.MAYBE, "código", page, null)) } } catch (_: Exception) {}
                val v3 = verifyAll(40_000)
                if (full) log.done(if (v3 > 0) St.FIXED else St.FAIL, if (v3 > 0) "Achei $v3 vídeo(s) no código." else "$n endereço(s) lidos; nenhum era vídeo que abre.")
            }

            // 19) servidor yt-dlp com a origem certa
            if (!gotV()) {
                if (pending(CK.SERVER).isEmpty() && full) addCand(Cand(page, CK.SERVER, null, "servidor", null, DetKnowledge.Ref.PAGE))
                if (pending(CK.SERVER).isNotEmpty()) {
                    if (full) log.begin("Perguntar ao servidor yt-dlp (com a página da aula como origem)")
                    val b = verifiedV
                    verifyAll(0, servers = true)
                    if (full) log.done(if (verifiedV > b) St.FIXED else St.FAIL, if (verifiedV > b) "O servidor reconheceu ${verifiedV - b} vídeo(s)." else lastServerErr.take(160).ifBlank { "não reconheceu" })
                }
            }

            if (full && !gotV() && drm.isBlank() && (blobTags > 0 || DetBridge.blobs > 0)) {
                log.begin("Player que monta o vídeo em pedaços")
                log.done(St.INFO, "O vídeo é montado em pedaços (MediaSource). Deixe tocar uns segundos: o app grava os pedaços e ele entra na lista.")
                hooks.collect(); waitFor(6_000)
            }

            // 20) recarregar com a detecção desde o início
            if (full && !gotV() && needReload) {
                log.begin("Recarregar a página com a detecção desde o começo")
                web.reload(); waitLoad()
                playAll(); val ok = waitFor(6_000)
                if (!ok) { discover(quiet = true); verifyAll(30_000) }
                log.done(if (gotV()) St.FIXED else St.FAIL, if (gotV()) "Depois de recarregar o vídeo apareceu." else "")
                if (gotV()) wins += "recarregar"
            }

            // 21) a página é a lista do curso: abre a aula
            var lessonUrl: String? = null; var lessonTitle = ""
            if (!gotV() && lessons.isNotEmpty() && videoTags == 0 && bigFrames == 0) {
                val (u, t) = lessons.first()
                lessonUrl = u; lessonTitle = t
                if (full) {
                    log.begin("Abrir uma aula")
                    log.done(St.FIXED, "Esta página parece a lista do curso (${lessons.size} aula(s), sem player). Abri: ${t.ifBlank { "primeira aula" }.take(80)}")
                    web.loadUrl(u); waitLoad()
                    log.begin("Procurar na aula")
                    DetBridge.ensureTop(web); playAll(); waitFor(5_000)
                    discover(quiet = true); verifyAll(40_000)
                    if (!gotV()) { addCand(Cand(page, CK.SERVER, null, "servidor", null, DetKnowledge.Ref.PAGE)); verifyAll(0, servers = true) }
                    log.done(if (gotV()) St.FIXED else St.FAIL, if (gotV()) "Achei o vídeo da aula. Nas outras aulas use \"Carregar tudo\" ou abra cada uma." else "Também não achei na aula.")
                    if (gotV()) wins += "aula"
                }
            }

            // 22) fotos
            if (full || BrowserDetect.photosOn.value) photoLayer()

            if (gotV() || gotP()) DetMemory.save(host, wins, recipesHit, players.filter { it.isNotBlank() }.take(4))
            CrashLog.mark("detetive: vídeos=${videos() - startV} fotos=${BrowserDetect.photos.size - startP} testes=$tests")
            return result(null, lessonUrl.takeIf { !full && !gotV() }, lessonTitle)
        }

        fun previousNonLogin(): String? = try {
            val l = web.copyBackForwardList()
            (l.currentIndex - 1 downTo 0).map { l.getItemAtIndex(it).url }.firstOrNull { it.startsWith("http") && !DetKnowledge.isLoginUrl(it) }
        } catch (_: Exception) { null }

        suspend fun playAll(): Int {
            hooks.tapPlay()
            val n = DetBridge.ask(web, "play", 1_500).sumOf { it.optInt("n") }
            delay(1_500)
            DetBridge.ask(web, "play", 800)
            return n
        }

        /** Varredura + rede + players reconhecidos + memória das receitas. */
        suspend fun discover(quiet: Boolean = false) {
            if (full && !quiet) log.begin("Varredura profunda (vídeos, iframes, recursos, scripts, configs de player) em todos os quadros")
            val n = deepScan("varredura")
            if (full && !quiet) log.done(St.INFO, "$frames quadro(s) • <video>: $videoTags${if (blobTags > 0) " ($blobTags em pedaços)" else ""} • players embutidos: $bigFrames • ${cands.size} pista(s)")
            if (full && !quiet) log.begin("Respostas de rede com vídeo dentro")
            val nn = netScan()
            if (full && !quiet) log.done(St.INFO, if (nn > 0) "$nn endereço(s) vindos das respostas do site" else "nada ainda (aparece quando o player carrega)")
            if (full && !quiet) {
                log.begin("Players e plataforma reconhecidos")
                val ps = players.filter { it.isNotBlank() && it.first().isUpperCase() }.distinct()
                log.done(if (ps.isNotEmpty()) St.OK else St.INFO, if (ps.isNotEmpty()) ps.take(8).joinToString() + if (recipesHit.isNotEmpty()) " • receitas usadas: ${recipesHit.size}" else "" else "nenhum conhecido; uso a busca genérica")
            }
            if (pw > 0 && full && !quiet) { log.begin("Login"); log.done(St.INFO, "Ainda há um campo de senha visível. Se o vídeo é só para alunos, entre na conta.") }
            if (n == 0 && nn == 0 && full && !quiet) hooks.collect()
        }

        fun verifyDetail(v: Int): String = buildString {
            if (v > 0) append("$v vídeo(s) conferido(s) e na lista. ") else append("Nenhum vídeo abriu. ")
            append("$tests teste(s)")
            if (blocked.isNotEmpty()) append(" • recusados: " + blocked.entries.take(4).joinToString { "${it.key} (HTTP ${it.value})" })
        }

        fun drmResult(): Result {
            if (full) { log.begin("Proteção DRM"); log.done(St.FAIL, "Este vídeo usa DRM ($drm). Nenhum app consegue baixar sem quebrar a proteção, e isso o Baixar pro Telegram não faz.") }
            return result("Vídeo protegido por DRM: não dá para baixar.")
        }

        fun result(fixed: String?, lessonUrl: String? = null, lessonTitle: String = ""): Result {
            val v = (videos() - startV).coerceAtLeast(0); val p = (BrowserDetect.photos.size - startP).coerceAtLeast(0)
            val verdict = fixed ?: when {
                v > 0 || p > 0 -> "Pronto: " + listOfNotNull(if (v > 0) "$v vídeo(s)" else null, if (p > 0) "$p foto(s)" else null).joinToString(" e ") + " na lista."
                drmMaybe -> "Não achei o vídeo e a página tem sinais de DRM. Copie o diagnóstico e mande no chat."
                pw > 0 -> "Não achei vídeo. A página pede login: entre na conta e abra a página da aula."
                blocked.isNotEmpty() -> "O site recusou os pedidos (${blocked.values.distinct().joinToString { "HTTP $it" }}). Abra a aula, dê play uma vez e toque em Diagnosticar de novo."
                videoTags == 0 && bigFrames == 0 -> "Não há player nesta página. Abra a página da aula (onde o vídeo toca)."
                else -> "Não consegui achar o vídeo nesta página. Copie o diagnóstico e mande no chat."
            }
            return Result(v, p, verdict, lessonUrl, lessonTitle)
        }
    }
}
