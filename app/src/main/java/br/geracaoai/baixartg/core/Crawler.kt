package br.geracaoai.baixartg.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.coroutines.coroutineContext

enum class Kind { DIRECT, HLS, SERVER, MEGA }

/** ref = Referer a usar no download (ex.: página do player embutido); se null usa page.
 *  extra = texto ao redor (alt, título do link, figcaption, JSON-LD) para o filtro por nome. */
data class Found(val url: String, val page: String, val title: String, val kind: Kind, val ref: String? = null, val extra: String = "", val thumb: String? = null, val height: Int = 0, val duration: Int = 0, val size: Long = -1, val site: String = "", val preview: String? = null, val bestQuality: Boolean = false) {
    val label get() = when (kind) { Kind.DIRECT -> "Arquivo direto"; Kind.HLS -> "Stream HLS (m3u8)"; Kind.SERVER -> "Via servidor (yt-dlp)"; Kind.MEGA -> "MEGA (descriptografado no celular)" }
}

data class CrawlOpts(val maxDepth: Int = 10, val maxPages: Int = 500, val probeServer: Boolean = false, val parallel: Int = 6, val nameWords: List<String> = emptyList(), val nameQuery: String = "")

data class CrawlProgress(val pagesDone: Int, val queued: Int, val found: Int, val current: String)

object Extract {
    private val VIDEO_EXT = Regex("""\.(mp4|webm|m3u8|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SKIP_EXT = Regex("""\.(jpe?g|png|gif|webp|svg|ico|css|js|json|xml|pdf|zip|rar|7z|gz|mp3|wav|ogg|woff2?|ttf|eot|apk|exe|dmg|txt|avif|bmp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>()\\]+?\.(?:mp4|webm|m3u8|mov|m4v|mkv)(?:\?[^\s"'<>\\]*)?(?=$|[\s"'<>\\),;])""", RegexOption.IGNORE_CASE)
    private val KVS = Regex("""video_(?:alt_)?url\d*\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val REL_MEDIA = Regex("""['"](/[^'"\s<>]+?\.(?:mp4|webm|m3u8|mov|m4v|mkv)(?:\?[^'"\s<>]*)?)['"]""", RegexOption.IGNORE_CASE)
    private val PLAYER_URL = Regex(
        """(?:file|src|source|url|videoUrl|video_url|videoSrc|contentUrl|content_url|hlsUrl|hls_url|dashUrl|streamUrl|playbackUrl|playlist|mp4|m3u8)\s*[:=]\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE)
    private val LD_URL = Regex(""""(?:contentUrl|embedUrl)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    /** Players conhecidos que dão o m3u8 direto pelo id (sem abrir o iframe). */
    private val VAZOU = Regex("""^https?://(?:[\w-]+\.)*vazounudes\.net/video/video-player[^?]*\?(?:.*&)?id=([0-9a-fA-F-]{36})""")
    private val AD_FRAME = Regex("""(doubleclick|googlesyndication|googletag|adservice|/ads?[/.?]|banner|recaptcha|disqus|facebook\.com/plugins/(?:like|page|comments)|platform\.twitter|instagram\.com/embed\.js|spotify|soundcloud|maps\.google|google\.com/maps|exoclick|juicyads|trafficjunky|popads|adsterra|chaturbate|stripchat|livejasmin)""", RegexOption.IGNORE_CASE)
    /** Páginas que o yt-dlp do servidor sabe extrair (YouTube, Vimeo, X, etc). */
    val YTDLP_HOSTS = Regex(
        """^https?://(?:[\w-]+\.)*(?:youtube\.com/(?:embed/|watch\?|shorts/|live/)|youtube-nocookie\.com/embed/|youtu\.be/|player\.vimeo\.com/video/|vimeo\.com/\d+|dailymotion\.com/(?:embed/)?video/|dai\.ly/|geo\.dailymotion\.com/player|streamable\.com/(?:e/)?\w+|rumble\.com/(?:embed/)?v|ok\.ru/video(?:embed)?/|facebook\.com/(?:plugins/video|watch|[^/]+/videos/|reel/)|fb\.watch/|instagram\.com/(?:p|reel|reels|tv)/|tiktok\.com/(?:@[^/]+/video/|embed)|(?:twitter|x)\.com/(?:(?:i/web|i|[^/]+)/)?status/|twitch\.tv/videos/|clips\.twitch\.tv/|bitchute\.com/(?:embed|video)/|odysee\.com/|loom\.com/(?:share|embed)/|wistia\.(?:com|net)/(?:medias|embed)/|fast\.wistia\.net/embed|player\.bilibili\.com|bilibili\.com/video/|vk\.com/video|kick\.com/[^/]+/videos?/|redgifs\.com/(?:watch|ifr)/|xvideos\.com/video|pornhub\.com/(?:view_video|embed)|xhamster\.com/(?:videos|embed)|archive\.org/(?:details|embed)/)""",
        RegexOption.IGNORE_CASE)

    fun host(u: String): String = try { (URI(u).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }

    fun abs(base: String, href: String?): String? {
        val h = href?.trim()?.replace("&amp;", "&") ?: return null
        if (h.isEmpty() || h.startsWith("data:") || h.startsWith("blob:") || h.startsWith("javascript:") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("#") || h == "about:blank") return null
        return try {
            val r = URI(base).resolve(URI(h.replace(" ", "%20")))
            if (r.scheme != "http" && r.scheme != "https") null else URI(r.scheme, r.authority, r.path, r.query, null).toString()
        } catch (_: Exception) { null }
    }

    fun isVideoUrl(u: String) = VIDEO_EXT.containsMatchIn(u.substringBefore('#'))
    fun isHls(u: String) = Regex("""\.m3u8(?:$|[?/])""", RegexOption.IGNORE_CASE).containsMatchIn(u)
    fun skipPage(u: String) = SKIP_EXT.containsMatchIn(u) || isVideoUrl(u)

    data class PageResult(val videos: List<Found>, val links: List<String>, val title: String, val frames: List<String> = emptyList(), val linkText: Map<String, String> = emptyMap(), val pageThumb: String? = null, val openSearch: String? = null)

    private fun mk(u: String, page: String, title: String, ref: String? = null, extra: String = "", thumb: String? = null) = Found(u, page, title, if (isHls(u)) Kind.HLS else Kind.DIRECT, ref, extra, thumb)

    private fun nearby(e: Element): String {
        val bits = ArrayList<String>()
        fun add(s: String?) { val t = s?.replace(Regex("\\s+"), " ")?.trim().orEmpty(); if (t.length > 1) bits += t }
        add(e.attr("alt")); add(e.attr("title")); add(e.attr("aria-label")); add(e.attr("data-title")); add(e.attr("data-name"))
        add(e.ownText()); add(e.text().take(200))
        e.select("img[alt],img[title]").forEach { add(it.attr("alt")); add(it.attr("title")) }
        // Só o contexto imediato: figcaption da <figure> desta mídia, texto do pai, heading irmão.
        // Não buscar figcaption/h1 em ancestrais largos (body), senão um nome vaza para todos os vídeos da página.
        e.closest("figure")?.selectFirst("figcaption")?.let { add(it.text()) }
        e.parent()?.let { p ->
            add(p.attr("title")); add(p.attr("aria-label")); add(p.ownText())
            if (p.tagName().matches(Regex("h[1-6]"))) add(p.text())
        }
        e.previousElementSibling()?.let { s ->
            if (s.tagName().matches(Regex("h[1-6]|figcaption")) || s.hasClass("title") || s.hasClass("name")) add(s.text())
        }
        return bits.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(400)
    }

    private fun putCtx(ctx: MutableMap<String, String>, url: String, text: String?) {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty(); if (t.length < 2) return
        val old = ctx[url]
        if (old.isNullOrBlank()) ctx[url] = t.take(400) else if (!old.contains(t.take(40))) ctx[url] = (old + " " + t).trim().take(500)
    }

    fun parse(pageUrl: String, html: String): PageResult {
        val doc: Document = Jsoup.parse(html, pageUrl)
        val site = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim().orEmpty()
        var title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()).trim()
        if (site.length > 2) title = title.replace(Regex("""\s*[|\-–—:]\s*""" + Regex.escape(site) + """\s*$""", RegexOption.IGNORE_CASE), "")
        title = title.ifBlank { pageUrl }.take(150)
        val media = LinkedHashSet<String>()
        val server = LinkedHashSet<String>()
        val frames = LinkedHashSet<String>()
        val ctx = HashMap<String, String>()
        val thumbs = HashMap<String, String>()
        val linkText = LinkedHashMap<String, String>()
        fun addThumb(u: String?, t: String?) {
            val a = u ?: return; val th = abs(pageUrl, t) ?: return
            if (th.startsWith("http") && thumbs[a] == null) thumbs[a] = th
        }
        fun addMedia(u: String?, text: String? = null, thumb: String? = null) { val a = abs(pageUrl, u) ?: return; if (isVideoUrl(a)) { media += a; putCtx(ctx, a, text); addThumb(a, thumb) } }
        fun addServer(u: String?, text: String? = null, thumb: String? = null) { val a = abs(pageUrl, u) ?: return; if (YTDLP_HOSTS.containsMatchIn(a)) { server += a; putCtx(ctx, a, text); addThumb(a, thumb) } }
        val pageThumb = abs(pageUrl, doc.selectFirst("meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]")?.attr("content"))
        val openSearch = abs(pageUrl, doc.selectFirst("link[rel=search][type=application/opensearchdescription+xml], link[rel=search][href]")?.attr("href"))

        doc.select("video[src], video source[src], source[src], video[data-src], source[data-src], video[data-url], source[data-url]").forEach { e ->
            val v = e.closest("video") ?: e
            val u = e.attr("src").ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") }
            addMedia(u, nearby(v), v.attr("poster").ifBlank { v.attr("data-poster") }.ifBlank { null })
        }
        doc.select("[data-video],[data-video-src],[data-stream],[data-file],[data-src][data-type*=video]").forEach { e ->
            addMedia(e.attr("data-video").ifBlank { e.attr("data-video-src") }.ifBlank { e.attr("data-stream") }.ifBlank { e.attr("data-file") }.ifBlank { e.attr("data-src") }, nearby(e))
        }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream], meta[itemprop=contentUrl], link[itemprop=contentUrl]").forEach { e ->
            val u = e.attr("content").ifBlank { e.attr("href") }
            addMedia(u, title); addServer(u, title)
        }
        doc.select("a[href]").forEach { e ->
            val a = abs(pageUrl, e.attr("href"))
            val txt = nearby(e)
            if (a != null && !skipPage(a)) {
                linkText[a] = (linkText[a]?.let { it + " " + txt } ?: txt).trim()
            }
            val img = e.selectFirst("img[src], img[data-src]")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            addMedia(e.attr("href"), txt, img); addServer(e.attr("href"), txt, img)
        }

        val raw = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("&amp;", "&")
        URL_IN_TEXT.findAll(raw).forEach { addMedia(it.value) }
        KVS.findAll(raw).forEach { addMedia(it.groupValues[1].replace(Regex("^function/\\d+/"), "")) }
        REL_MEDIA.findAll(raw).forEach { addMedia(it.groupValues[1]) }
        PLAYER_URL.findAll(raw).forEach { addMedia(it.groupValues[1]); addServer(it.groupValues[1]) }

        doc.select("script[type=application/ld+json]").forEach { s ->
            val body = s.data().replace("\\/", "/")
            LD_URL.findAll(body).forEach { addMedia(it.groupValues[1], title); addServer(it.groupValues[1], title) }
            val ldThumb = Regex(""""thumbnailUrl"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            if (ldThumb != null) { media.forEach { addThumb(it, ldThumb) }; server.forEach { addThumb(it, ldThumb) } }
            Regex(""""(?:name|description|caption)"\s*:\s*"([^"]{2,200})"""").findAll(body).forEach { m ->
                media.forEach { putCtx(ctx, it, m.groupValues[1]) }
                server.forEach { putCtx(ctx, it, m.groupValues[1]) }
            }
        }

        val frameSrcs = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], embed[src], object[data], amp-iframe[src]").mapNotNull { e ->
            abs(pageUrl, e.attr("src").takeIf { it.isNotBlank() && it != "about:blank" } ?: e.attr("data-src").ifBlank { e.attr("data-lazy-src") }.ifBlank { e.attr("data-url") }.ifBlank { e.attr("data") }) }
        for (f in frameSrcs) {
            val vz = VAZOU.find(f)
            when {
                vz != null -> media += "https://vazounudes.net/hls/${vz.groupValues[1].lowercase()}/playlist.m3u8"
                YTDLP_HOSTS.containsMatchIn(f) -> server += f
                !AD_FRAME.containsMatchIn(f) && !skipPage(f) -> frames += f
            }
        }
        doc.select("meta[name=twitter:player], meta[property=og:video:url]").forEach { addServer(it.attr("content"), title) }
        if (Bunkr.isBunkrPage(pageUrl)) {
            val cdn = media.firstOrNull { Bunkr.isCdn(it) }
            return PageResult(if (cdn != null) listOf(Found(cdn, pageUrl, title.substringBefore(" | "), Kind.DIRECT)) else emptyList(),
                doc.select("a[href]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct(), title)
        }
        val links = doc.select("a[href], link[rel=next]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct()
        fun thumbOf(u: String) = thumbs[u] ?: SiteSearch.youtubeThumb(u) ?: pageThumb
        val vids = ArrayList<Found>()
        media.filterNot { it.contains("preview", true) && media.size > 1 }.ifEmpty { media.toList() }.forEach { vids += mk(it, pageUrl, title, extra = ctx[it].orEmpty(), thumb = thumbOf(it)) }
        server.forEach { vids += Found(it, pageUrl, title, Kind.SERVER, extra = ctx[it].orEmpty(), thumb = thumbOf(it)) }
        return PageResult(vids, links, title, frames.take(8), linkText, pageThumb, openSearch)
    }

    /** Resultado de um iframe (player de outro site): vídeos herdam a página e o título de quem embutiu. */
    fun parseFrame(frameUrl: String, html: String, parentPage: String, parentTitle: String): List<Found> =
        parse(frameUrl, html).videos.map {
            if (it.kind == Kind.SERVER) it.copy(page = parentPage, title = parentTitle)
            else Found(it.url, parentPage, parentTitle, it.kind, frameUrl, it.extra, it.thumb)
        }
}

class Crawler(private val opts: CrawlOpts, private val probe: (suspend (String) -> String?)? = null) {
    /** probe(pageUrl) -> título se o servidor (yt-dlp) encontrou vídeo nessa página; null se não. */
    suspend fun run(start: String, onProgress: (CrawlProgress) -> Unit, onFound: (Found) -> Unit): Int = coroutineScope {
        val startUrl = Extract.abs(start, start) ?: throw IllegalArgumentException("Link inválido")
        val root = Extract.host(startUrl)
        val seenPages = HashSet<String>()
        val seenFrames = HashSet<String>()
        val seenVideos = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += startUrl to 0; seenPages += norm(startUrl)
        var done = 0; var found = 0
        fun emit(f: Found) { if (seenVideos.add(f.url.substringBefore('#'))) { found++; onFound(f) } }
        if (opts.nameWords.isEmpty() && Extract.YTDLP_HOSTS.containsMatchIn(startUrl)) emit(Found(startUrl, startUrl, startUrl, Kind.SERVER, thumb = SiteSearch.youtubeThumb(startUrl)))
        if (Extract.isVideoUrl(startUrl)) { emit(Found(startUrl, startUrl, startUrl.substringAfterLast('/'), if (Extract.isHls(startUrl)) Kind.HLS else Kind.DIRECT)); return@coroutineScope found }
        val par = opts.parallel.coerceIn(1, 12)
        val words = opts.nameWords
        val query = opts.nameQuery.ifBlank { words.joinToString(" ") }
        var searchSeeded = false
        while (queue.isNotEmpty() && done < opts.maxPages) {
            coroutineContext.ensureActive()
            val batch = ArrayList<Pair<String, Int>>()
            while (batch.size < par && queue.isNotEmpty() && done + batch.size < opts.maxPages) batch += queue.removeFirst()
            onProgress(CrawlProgress(done, queue.size, found, batch.first().first))
            val pages = batch.map { (u, d) -> async { Triple(u, d, safeFetch(u)) } }.awaitAll()
            // iframes (players de outros sites) — um nível, em paralelo
            val frameJobs = ArrayList<Pair<Extract.PageResult, Pair<String, String>>>()
            val parsed = pages.map { (u, d, html) -> done++; Triple(u, d, html?.let { Extract.parse(u, it) } to html) }
            for ((u, _, pair) in parsed) pair.first?.frames?.forEach { f -> if (seenFrames.add(f)) frameJobs += pair.first!! to (u to f) }
            val frameRes = frameJobs.chunked(par).flatMap { ch -> ch.map { (r, uf) -> async { Triple(uf.first, r.title, uf.second) to safeFetch(uf.second) } }.awaitAll() }
            for ((u, d, pair) in parsed) {
                val r = pair.first; val html = pair.second
                if (r == null) continue
                val before = found
                r.videos.forEach(::emit)
                frameRes.filter { it.first.first == u }.forEach { (k, fhtml) -> if (fhtml != null) Extract.parseFrame(k.third, fhtml, u, k.second).forEach(::emit) }
                // Fallback yt-dlp: na página inicial sempre; nas outras, se o ajuste estiver ligado.
                if (found == before && probe != null && !Extract.YTDLP_HOSTS.containsMatchIn(u) && (opts.probeServer || u == startUrl)) {
                    try { probe.invoke(u)?.let { t -> emit(Found(u, u, t, Kind.SERVER, thumb = r.pageThumb)) } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                }
                // Busca do próprio site (form / OpenSearch / padrões) a partir da página inicial.
                if (!searchSeeded && words.isNotEmpty() && u == startUrl && html != null) {
                    searchSeeded = true
                    val foundSearch = ArrayList<String>()
                    foundSearch += SiteSearch.discover(u, html, query)
                    r.openSearch?.let { osd ->
                        try { safeFetch(osd)?.let { xml -> SiteSearch.fromOpenSearch(xml, query, u)?.let { foundSearch += it } } } catch (_: Exception) {}
                    }
                    for (s in foundSearch.distinct().asReversed()) {
                        if (Extract.host(s) == root && seenPages.add(norm(s))) queue.addFirst(s to 0)
                    }
                }
                if (d < opts.maxDepth) {
                    val match = ArrayList<Pair<String, Int>>()
                    for (l in r.links) {
                        if (Extract.host(l) != root) continue
                        if (!seenPages.add(norm(l))) continue
                        if (words.isEmpty()) { match += l to d + 1; continue }
                        val txt = (r.linkText[l] ?: "") + " " + r.title + " " + l
                        if (SiteSearch.isSearchUrl(l) || SiteSearch.matches(txt, words)) match += l to d + 1
                        // páginas sem relação com o nome são puladas
                    }
                    // prioriza os que batem com o nome (entram no começo da fila)
                    for (i in match.indices.reversed()) queue.addFirst(match[i])
                }
            }
            onProgress(CrawlProgress(done, queue.size, found, batch.last().first))
        }
        found
    }

    private fun norm(u: String) = u.substringBefore('#').trimEnd('/').lowercase().replace("://www.", "://")

    private suspend fun safeFetch(url: String): String? = try { fetchHtml(url) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }

    private suspend fun fetchHtml(url: String): String? {
        Net.http.newCall(Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8").build()).await().use { r ->
            if (!r.isSuccessful) return null
            val ct = r.header("Content-Type") ?: ""
            if (ct.isNotEmpty() && !ct.contains("html", true) && !ct.contains("xml", true)) return null
            val body = r.body ?: return null
            val src = body.source(); src.request(4L * 1024 * 1024)
            return src.buffer.readUtf8(minOf(src.buffer.size, 4L * 1024 * 1024))
        }
    }
}
