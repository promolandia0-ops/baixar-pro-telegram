package br.geracaoai.baixartg.core

import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlin.coroutines.coroutineContext

enum class Kind { DIRECT, HLS, SERVER }

data class Found(val url: String, val page: String, val title: String, val kind: Kind) {
    val label get() = when (kind) { Kind.DIRECT -> "Arquivo direto"; Kind.HLS -> "Stream HLS (m3u8)"; Kind.SERVER -> "Via servidor (yt-dlp)" }
}

data class CrawlOpts(val maxDepth: Int = 10, val maxPages: Int = 500, val probeServer: Boolean = false)

data class CrawlProgress(val pagesDone: Int, val queued: Int, val found: Int, val current: String)

object Extract {
    private val VIDEO_EXT = Regex("""\.(mp4|webm|m3u8|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SKIP_EXT = Regex("""\.(jpe?g|png|gif|webp|svg|ico|css|js|json|xml|pdf|zip|rar|7z|gz|mp3|wav|ogg|woff2?|ttf|eot|apk|exe|dmg|txt|avif|bmp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>()\\]+?\.(?:mp4|webm|m3u8|mov|m4v|mkv)(?:\?[^\s"'<>\\]*)?(?=$|[\s"'<>\\),;])""", RegexOption.IGNORE_CASE)
    private val KVS = Regex("""video_(?:alt_)?url\d*\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val REL_MEDIA = Regex("""['"](/[^'"\s<>]+?\.(?:mp4|webm|m3u8)(?:\?[^'"\s<>]*)?)['"]""", RegexOption.IGNORE_CASE)
    /** Páginas que o yt-dlp do servidor sabe extrair (YouTube, Vimeo, etc). */
    val YTDLP_HOSTS = Regex(
        """^https?://(?:[\w-]+\.)*(?:youtube\.com/(?:embed/|watch\?|shorts/|live/)|youtube-nocookie\.com/embed/|youtu\.be/|player\.vimeo\.com/video/|vimeo\.com/\d+|dailymotion\.com/(?:embed/)?video/|dai\.ly/|geo\.dailymotion\.com/player|streamable\.com/(?:e/)?\w+|rumble\.com/(?:embed/)?v|ok\.ru/video(?:embed)?/|facebook\.com/(?:plugins/video|watch|[^/]+/videos/|reel/)|fb\.watch/|instagram\.com/(?:p|reel|reels|tv)/|tiktok\.com/(?:@[^/]+/video/|embed)|twitter\.com/[^/]+/status/|x\.com/[^/]+/status/|twitch\.tv/videos/|clips\.twitch\.tv/|bitchute\.com/(?:embed|video)/|odysee\.com/|loom\.com/(?:share|embed)/|wistia\.(?:com|net)/(?:medias|embed)/|fast\.wistia\.net/embed|player\.bilibili\.com|bilibili\.com/video/|vk\.com/video|kick\.com/[^/]+/videos?/|redgifs\.com/(?:watch|ifr)/|xvideos\.com/video|pornhub\.com/(?:view_video|embed)|xhamster\.com/(?:videos|embed))""",
        RegexOption.IGNORE_CASE)

    fun host(u: String): String = try { (URI(u).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }

    fun abs(base: String, href: String?): String? {
        val h = href?.trim()?.replace("&amp;", "&") ?: return null
        if (h.isEmpty() || h.startsWith("data:") || h.startsWith("blob:") || h.startsWith("javascript:") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("#")) return null
        return try {
            val r = URI(base).resolve(URI(h.replace(" ", "%20")))
            if (r.scheme != "http" && r.scheme != "https") null else URI(r.scheme, r.authority, r.path, r.query, null).toString()
        } catch (_: Exception) { null }
    }

    fun isVideoUrl(u: String) = VIDEO_EXT.containsMatchIn(u.substringBefore('#'))
    fun isHls(u: String) = Regex("""\.m3u8(?:$|[?/])""", RegexOption.IGNORE_CASE).containsMatchIn(u)
    fun skipPage(u: String) = SKIP_EXT.containsMatchIn(u) || isVideoUrl(u)

    data class PageResult(val videos: List<Found>, val links: List<String>, val title: String)

    fun parse(pageUrl: String, html: String): PageResult {
        val doc: Document = Jsoup.parse(html, pageUrl)
        val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()).trim().ifBlank { pageUrl }.take(150)
        val media = LinkedHashSet<String>()
        val server = LinkedHashSet<String>()
        fun addMedia(u: String?) { val a = abs(pageUrl, u) ?: return; if (isVideoUrl(a)) media += a }
        doc.select("video[src], video source[src], source[src], video[data-src], source[data-src]").forEach { e -> addMedia(e.attr("src").ifBlank { e.attr("data-src") }) }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream]").forEach { addMedia(it.attr("content")) }
        doc.select("a[href]").forEach { e -> addMedia(e.attr("href")) }
        val raw = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("&amp;", "&")
        URL_IN_TEXT.findAll(raw).forEach { addMedia(it.value) }
        KVS.findAll(raw).forEach { addMedia(it.groupValues[1].replace(Regex("^function/\\d+/"), "")) }
        REL_MEDIA.findAll(raw).forEach { addMedia(it.groupValues[1]) }
        // Embeds / links que precisam de yt-dlp
        (doc.select("iframe[src], iframe[data-src], embed[src]").map { it.attr("src").ifBlank { it.attr("data-src") } } +
            doc.select("a[href]").map { it.attr("href") } +
            doc.select("meta[property=og:video], meta[property=og:video:url], meta[name=twitter:player]").map { it.attr("content") })
            .mapNotNull { abs(pageUrl, it) }.filter { YTDLP_HOSTS.containsMatchIn(it) }.forEach { server += it }
        val links = doc.select("a[href], link[rel=next]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct()
        val vids = ArrayList<Found>()
        media.filterNot { it.contains("preview", true) && media.size > 1 }.ifEmpty { media.toList() }.forEach { vids += Found(it, pageUrl, title, if (isHls(it)) Kind.HLS else Kind.DIRECT) }
        server.forEach { vids += Found(it, pageUrl, title, Kind.SERVER) }
        return PageResult(vids, links, title)
    }
}

class Crawler(private val opts: CrawlOpts, private val probe: (suspend (String) -> String?)? = null) {
    /** probe(pageUrl) -> título se o servidor (yt-dlp) encontrou vídeo nessa página; null se não. */
    suspend fun run(start: String, onProgress: (CrawlProgress) -> Unit, onFound: (Found) -> Unit): Int {
        val startUrl = Extract.abs(start, start) ?: throw IllegalArgumentException("Link inválido")
        val root = Extract.host(startUrl)
        val seenPages = HashSet<String>()
        val seenVideos = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue += startUrl to 0; seenPages += norm(startUrl)
        var done = 0; var found = 0
        fun emit(f: Found) { if (seenVideos.add(f.url.substringBefore('#'))) { found++; onFound(f) } }
        if (Extract.YTDLP_HOSTS.containsMatchIn(startUrl)) { emit(Found(startUrl, startUrl, startUrl, Kind.SERVER)) }
        if (Extract.isVideoUrl(startUrl)) { emit(Found(startUrl, startUrl, startUrl.substringAfterLast('/'), if (Extract.isHls(startUrl)) Kind.HLS else Kind.DIRECT)); return found }
        while (queue.isNotEmpty() && done < opts.maxPages) {
            coroutineContext.ensureActive()
            val (url, depth) = queue.removeFirst()
            onProgress(CrawlProgress(done, queue.size, found, url))
            val html = try { fetchHtml(url) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            done++
            if (html == null) continue
            val r = Extract.parse(url, html)
            val before = found
            r.videos.forEach(::emit)
            if (found == before && (opts.probeServer || done == 1) && probe != null && !Extract.YTDLP_HOSTS.containsMatchIn(url)) {
                try { probe.invoke(url)?.let { t -> emit(Found(url, url, t, Kind.SERVER)) } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
            if (depth < opts.maxDepth) for (l in r.links) {
                if (Extract.host(l) != root) continue
                if (seenPages.add(norm(l))) queue += l to depth + 1
            }
            onProgress(CrawlProgress(done, queue.size, found, url))
        }
        return found
    }

    private fun norm(u: String) = u.substringBefore('#').trimEnd('/').lowercase().replace("://www.", "://")

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
