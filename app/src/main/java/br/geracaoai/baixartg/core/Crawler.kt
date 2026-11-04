package br.geracaoai.baixartg.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlin.coroutines.coroutineContext

enum class Kind { DIRECT, HLS, SERVER, MEGA }

/** ref = Referer a usar no download (ex.: página do player embutido); se null usa page. */
data class Found(val url: String, val page: String, val title: String, val kind: Kind, val ref: String? = null) {
    val label get() = when (kind) { Kind.DIRECT -> "Arquivo direto"; Kind.HLS -> "Stream HLS (m3u8)"; Kind.SERVER -> "Via servidor (yt-dlp)"; Kind.MEGA -> "MEGA (descriptografado no celular)" }
}

data class CrawlOpts(val maxDepth: Int = 10, val maxPages: Int = 500, val probeServer: Boolean = false, val parallel: Int = 6)

data class CrawlProgress(val pagesDone: Int, val queued: Int, val found: Int, val current: String)

object Extract {
    private val VIDEO_EXT = Regex("""\.(mp4|webm|m3u8|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SKIP_EXT = Regex("""\.(jpe?g|png|gif|webp|svg|ico|css|js|json|xml|pdf|zip|rar|7z|gz|mp3|wav|ogg|woff2?|ttf|eot|apk|exe|dmg|txt|avif|bmp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>()\\]+?\.(?:mp4|webm|m3u8|mov|m4v|mkv)(?:\?[^\s"'<>\\]*)?(?=$|[\s"'<>\\),;])""", RegexOption.IGNORE_CASE)
    private val KVS = Regex("""video_(?:alt_)?url\d*\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val REL_MEDIA = Regex("""['"](/[^'"\s<>]+?\.(?:mp4|webm|m3u8)(?:\?[^'"\s<>]*)?)['"]""", RegexOption.IGNORE_CASE)
    /** Players conhecidos que dão o m3u8 direto pelo id (sem abrir o iframe). */
    private val VAZOU = Regex("""^https?://(?:[\w-]+\.)*vazounudes\.net/video/video-player[^?]*\?(?:.*&)?id=([0-9a-fA-F-]{36})""")
    private val AD_FRAME = Regex("""(doubleclick|googlesyndication|googletag|adservice|/ads?[/.?]|banner|recaptcha|disqus|facebook\.com/plugins/(?:like|page|comments)|platform\.twitter|instagram\.com/embed\.js|spotify|soundcloud|maps\.google|google\.com/maps|exoclick|juicyads|trafficjunky|popads|adsterra|chaturbate|stripchat|livejasmin)""", RegexOption.IGNORE_CASE)
    /** Páginas que o yt-dlp do servidor sabe extrair (YouTube, Vimeo, etc). */
    val YTDLP_HOSTS = Regex(
        """^https?://(?:[\w-]+\.)*(?:youtube\.com/(?:embed/|watch\?|shorts/|live/)|youtube-nocookie\.com/embed/|youtu\.be/|player\.vimeo\.com/video/|vimeo\.com/\d+|dailymotion\.com/(?:embed/)?video/|dai\.ly/|geo\.dailymotion\.com/player|streamable\.com/(?:e/)?\w+|rumble\.com/(?:embed/)?v|ok\.ru/video(?:embed)?/|facebook\.com/(?:plugins/video|watch|[^/]+/videos/|reel/)|fb\.watch/|instagram\.com/(?:p|reel|reels|tv)/|tiktok\.com/(?:@[^/]+/video/|embed)|twitter\.com/[^/]+/status/|x\.com/[^/]+/status/|twitch\.tv/videos/|clips\.twitch\.tv/|bitchute\.com/(?:embed|video)/|odysee\.com/|loom\.com/(?:share|embed)/|wistia\.(?:com|net)/(?:medias|embed)/|fast\.wistia\.net/embed|player\.bilibili\.com|bilibili\.com/video/|vk\.com/video|kick\.com/[^/]+/videos?/|redgifs\.com/(?:watch|ifr)/|xvideos\.com/video|pornhub\.com/(?:view_video|embed)|xhamster\.com/(?:videos|embed))""",
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

    data class PageResult(val videos: List<Found>, val links: List<String>, val title: String, val frames: List<String> = emptyList())

    private fun mk(u: String, page: String, title: String, ref: String? = null) = Found(u, page, title, if (isHls(u)) Kind.HLS else Kind.DIRECT, ref)

    fun parse(pageUrl: String, html: String): PageResult {
        val doc: Document = Jsoup.parse(html, pageUrl)
        val site = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim().orEmpty()
        var title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()).trim()
        if (site.length > 2) title = title.replace(Regex("""\s*[|\-–—:]\s*""" + Regex.escape(site) + """\s*$""", RegexOption.IGNORE_CASE), "")
        title = title.ifBlank { pageUrl }.take(150)
        val media = LinkedHashSet<String>()
        val server = LinkedHashSet<String>()
        val frames = LinkedHashSet<String>()
        fun addMedia(u: String?) { val a = abs(pageUrl, u) ?: return; if (isVideoUrl(a)) media += a }
        doc.select("video[src], video source[src], source[src], video[data-src], source[data-src]").forEach { e -> addMedia(e.attr("src").ifBlank { e.attr("data-src") }) }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]").forEach { addMedia(it.attr("content")) }
        doc.select("a[href]").forEach { e -> addMedia(e.attr("href")) }
        val raw = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("&amp;", "&")
        URL_IN_TEXT.findAll(raw).forEach { addMedia(it.value) }
        KVS.findAll(raw).forEach { addMedia(it.groupValues[1].replace(Regex("^function/\\d+/"), "")) }
        REL_MEDIA.findAll(raw).forEach { addMedia(it.groupValues[1]) }
        val frameSrcs = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], embed[src]").mapNotNull { e ->
            abs(pageUrl, e.attr("src").takeIf { it.isNotBlank() && it != "about:blank" } ?: e.attr("data-src").ifBlank { e.attr("data-lazy-src") }) }
        for (f in frameSrcs) {
            val vz = VAZOU.find(f)
            when {
                vz != null -> media += "https://vazounudes.net/hls/${vz.groupValues[1].lowercase()}/playlist.m3u8"
                YTDLP_HOSTS.containsMatchIn(f) -> server += f
                !AD_FRAME.containsMatchIn(f) && !skipPage(f) -> frames += f
            }
        }
        (doc.select("a[href]").map { it.attr("href") } +
            doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player]").map { it.attr("content") })
            .mapNotNull { abs(pageUrl, it) }.filter { YTDLP_HOSTS.containsMatchIn(it) }.forEach { server += it }
        if (Bunkr.isBunkrPage(pageUrl)) {
            val cdn = media.firstOrNull { Bunkr.isCdn(it) }
            return PageResult(if (cdn != null) listOf(Found(cdn, pageUrl, title.substringBefore(" | "), Kind.DIRECT)) else emptyList(),
                doc.select("a[href]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct(), title)
        }
        val links = doc.select("a[href], link[rel=next]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct()
        val vids = ArrayList<Found>()
        media.filterNot { it.contains("preview", true) && media.size > 1 }.ifEmpty { media.toList() }.forEach { vids += mk(it, pageUrl, title) }
        server.forEach { vids += Found(it, pageUrl, title, Kind.SERVER) }
        return PageResult(vids, links, title, frames.take(4))
    }

    /** Resultado de um iframe (player de outro site): vídeos herdam a página e o título de quem embutiu. */
    fun parseFrame(frameUrl: String, html: String, parentPage: String, parentTitle: String): List<Found> =
        parse(frameUrl, html).videos.map { if (it.kind == Kind.SERVER) it.copy(page = parentPage, title = parentTitle) else Found(it.url, parentPage, parentTitle, it.kind, frameUrl) }
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
        if (Extract.YTDLP_HOSTS.containsMatchIn(startUrl)) emit(Found(startUrl, startUrl, startUrl, Kind.SERVER))
        if (Extract.isVideoUrl(startUrl)) { emit(Found(startUrl, startUrl, startUrl.substringAfterLast('/'), if (Extract.isHls(startUrl)) Kind.HLS else Kind.DIRECT)); return@coroutineScope found }
        val par = opts.parallel.coerceIn(1, 12)
        while (queue.isNotEmpty() && done < opts.maxPages) {
            coroutineContext.ensureActive()
            val batch = ArrayList<Pair<String, Int>>()
            while (batch.size < par && queue.isNotEmpty() && done + batch.size < opts.maxPages) batch += queue.removeFirst()
            onProgress(CrawlProgress(done, queue.size, found, batch.first().first))
            val pages = batch.map { (u, d) -> async { Triple(u, d, safeFetch(u)) } }.awaitAll()
            // iframes (players de outros sites) — um nível, em paralelo
            val frameJobs = ArrayList<Pair<Extract.PageResult, Pair<String, String>>>()
            val parsed = pages.map { (u, d, html) -> done++; Triple(u, d, html?.let { Extract.parse(u, it) }) }
            for ((u, _, r) in parsed) r?.frames?.forEach { f -> if (seenFrames.add(f)) frameJobs += r to (u to f) }
            val frameRes = frameJobs.chunked(par).flatMap { ch -> ch.map { (r, uf) -> async { Triple(uf.first, r.title, uf.second) to safeFetch(uf.second) } }.awaitAll() }
            for ((u, d, r) in parsed) {
                if (r == null) continue
                val before = found
                r.videos.forEach(::emit)
                frameRes.filter { it.first.first == u }.forEach { (k, html) -> if (html != null) Extract.parseFrame(k.third, html, u, k.second).forEach(::emit) }
                if (found == before && probe != null && !Extract.YTDLP_HOSTS.containsMatchIn(u) && (opts.probeServer || u == startUrl)) {
                    try { probe.invoke(u)?.let { t -> emit(Found(u, u, t, Kind.SERVER)) } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                }
                if (d < opts.maxDepth) for (l in r.links) {
                    if (Extract.host(l) != root) continue
                    if (seenPages.add(norm(l))) queue += l to d + 1
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
