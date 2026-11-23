package br.geracaoai.baixartg.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import kotlin.coroutines.coroutineContext

// expressões fixas compiladas uma vez (antes: recompiladas a cada chamada)
private val RX_CRAWLER_1 = Regex("""\.m3u8(?:$|[?/])""", RegexOption.IGNORE_CASE)
private val RX_CRAWLER_2 = Regex("""\.mpd(?:$|[?/])""", RegexOption.IGNORE_CASE)
private val RX_CRAWLER_3 = Regex("""\b(icon|logo|sprite|avatar|emoji|badge)\b""", RegexOption.IGNORE_CASE)
private val RX_CRAWLER_4 = Regex("""\b(thumb|thumbnail|poster|preview|cover|capa|sprite)\b""")
private val RX_CRAWLER_5 = Regex("\\s+")
private val RX_CRAWLER_6 = Regex("h[1-6]")
private val RX_CRAWLER_7 = Regex("\\s+")
private val RX_CRAWLER_8 = Regex("\\s+")
private val RX_CRAWLER_9 = Regex("\\s+")
private val RX_CRAWLER_10 = Regex("\\s+")
private val RX_CRAWLER_11 = Regex("\\s+")
private val RX_CRAWLER_12 = Regex("\\s+")
private val RX_CRAWLER_13 = Regex("^function/\\d+/")
private val RX_CRAWLER_14 = Regex("\\}\\s*,\\s*\\{")
private val RX_CRAWLER_15 = Regex("""https?://\S+""")


enum class Kind { DIRECT, HLS, SERVER, MEGA, PHOTO, MSE }

/** ref = Referer no download; extra = texto associado com folga curta (nunca o título da página inteira). */
data class Found(val url: String, val page: String, val title: String, val kind: Kind, val ref: String? = null, val extra: String = "", val quality: Int = 0) {
    val label get() = when (kind) {
        Kind.DIRECT -> "Arquivo direto"
        Kind.HLS -> "Stream HLS (m3u8)"
        Kind.SERVER -> "Via servidor (yt-dlp)"
        Kind.MEGA -> "MEGA (descriptografado no celular)"
        Kind.PHOTO -> "Foto"
        Kind.MSE -> "Pelo navegador (MSE)"
    }
    val typeLabel get() = when {
        kind == Kind.PHOTO -> "Foto"
        quality > 0 -> "Vídeo " + MediaIdentity.qualityLabel(quality)
        else -> "Vídeo"
    }
}

data class CrawlOpts(
    val maxDepth: Int = 10,
    val maxPages: Int = 500,
    val probeServer: Boolean = false,
    val parallel: Int = 24,
    val nameWords: List<String> = emptyList(),
    val nameQuery: String = "",
    val maxFrameDepth: Int = 2,
    val maxFrames: Int = 16,
    val maxJsPages: Int = 3,
    val maxSniffs: Int = 0,
    val htmlMaxBytes: Long = 1_048_576,
    val followCards: Boolean = true,
    val maxCardPages: Int = 200,
    val resolvePlayer: Boolean = false,
)

data class CrawlProgress(
    val pagesDone: Int, val queued: Int, val found: Int, val current: String, val method: String = "",
    val searchPages: Int = 0, val cards: Int = 0, val resolved: Int = 0, val failed: Int = 0, val detail: String = "",
)

data class JsPage(val html: String? = null, val media: List<Found> = emptyList(), val challenge: Boolean = false, val cards: Int = -1, val collected: JsScripts.Collected? = null, val apis: List<String> = emptyList(), val mse: Boolean = false, val specialHeader: Boolean = false, val ageGateBypassed: Boolean? = null)

object Extract {
    private val VIDEO_EXT = Regex("""\.(mp4|webm|m3u8|mpd|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    val PHOTO_EXT = Regex("""\.(jpe?g|png|webp|gif|avif|bmp)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SKIP_EXT = Regex("""\.(jpe?g|png|gif|webp|svg|ico|css|js|json|xml|pdf|zip|rar|7z|gz|mp3|wav|ogg|woff2?|ttf|eot|apk|exe|dmg|txt|avif|bmp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val URL_IN_TEXT = Regex("""https?://[^\s"'<>()\\]+?\.(?:mp4|webm|m3u8|mpd|mov|m4v|mkv|jpe?g|png|webp|gif)(?:\?[^\s"'<>\\]*)?(?=$|[\s"'<>\\),;])""", RegexOption.IGNORE_CASE)
    private val KVS = Regex("""video_(?:alt_)?url\d*\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val REL_MEDIA = Regex("""['"](/[^'"\s<>]+?\.(?:mp4|webm|m3u8|mpd|mov|m4v|mkv|jpe?g|png|webp)(?:\?[^'"\s<>]*)?)['"]""", RegexOption.IGNORE_CASE)
    private val PLAYER_URL = Regex(
        """(?:file|src|source|url|videoUrl|video_url|videoSrc|contentUrl|content_url|hlsUrl|hls_url|dashUrl|streamUrl|playbackUrl|playlist|mp4|m3u8|mpd|imageUrl|image_url|mediaUrl|media_url|srcUrl|src_url|clipUrl|fileUrl|playlistUrl|playlist_url|hls|dash|sources)\s*[:=]\s*\[?\s*\{?\s*(?:file|src|url)?\s*:?\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE)
    private val LD_URL = Regex(""""(?:contentUrl|embedUrl|thumbnailUrl)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val ICON_OR_SPRITE = Regex(
        """(?:favicon|sprite|logo|icon|pixel|tracking|1x1|spacer|blank\.gif|dummy|avatar[-_]?default|/icons?/|/sprites?/|/logos?/|apple-touch-icon|ms-icon)""",
        RegexOption.IGNORE_CASE)
    private val THUMB_URL = Regex(
        """(?:^|[/?#&_.=-])(?:thumbs?|thumbnail|poster|preview|covers?|capa|sprite|hqdefault|mqdefault|sddefault|maxresdefault)(?:$|[/?#&_.=-])|(?:(?:^|//)(?:i\.)?ytimg\.com/vi/|/vi/[\w-]{8,}/)""",
        RegexOption.IGNORE_CASE,
    )
    private val VIDEO_PAGE_PATH = Regex(
        """/(?:watch|video|videos|v|embed|player|aula|aulas|lesson|lessons|clip|filme|episodio|episodios|watchvideo)(?:/|$|\.)""",
        RegexOption.IGNORE_CASE,
    )
    private val LD_VIDEO = Regex(""""(?:contentUrl|embedUrl)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val LD_THUMB = Regex(""""(?:thumbnailUrl|thumbnail)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val SNIFF_PATH = Regex(
        """(/(play(er)?|stream|media|video|clip|download|file|hls|playlist|api|get[-_]?file|load[-_]?video|source)(/|$|\?)|\.(php|asp|aspx|jsp)(?:$|\?))""",
        RegexOption.IGNORE_CASE)
    private val VAZOU = Regex("""^https?://(?:[\w-]+\.)*vazounudes\.net/video/video-player[^?]*\?(?:.*&)?id=([0-9a-fA-F-]{36})""")
    private val AD_FRAME = Regex("""(doubleclick|googlesyndication|googletag|adservice|/ads?[/.?]|banner|recaptcha|disqus|facebook\.com/plugins/(?:like|page|comments)|platform\.twitter|instagram\.com/embed\.js|spotify|soundcloud|maps\.google|google\.com/maps|exoclick|juicyads|trafficjunky|popads|adsterra|chaturbate|stripchat|livejasmin)""", RegexOption.IGNORE_CASE)
    val YTDLP_HOSTS = Regex(
        """^https?://(?:[\w-]+\.)*(?:youtube\.com/(?:embed/|watch\?|shorts/|live/)|youtube-nocookie\.com/embed/|youtu\.be/|player\.vimeo\.com/video/|vimeo\.com/\d+|dailymotion\.com/(?:embed/)?video/|dai\.ly/|geo\.dailymotion\.com/player|streamable\.com/(?:e/)?\w+|rumble\.com/(?:embed/)?v|ok\.ru/video(?:embed)?/|facebook\.com/(?:plugins/video|watch|[^/]+/videos/|reel/)|fb\.watch/|instagram\.com/(?:p|reel|reels|tv)/|tiktok\.com/(?:@[^/]+/video/|embed)|(?:twitter|x)\.com/(?:(?:i/web|i|[^/]+)/)?status/|twitch\.tv/videos/|clips\.twitch\.tv/|bitchute\.com/(?:embed|video)/|odysee\.com/|loom\.com/(?:share|embed)/|wistia\.(?:com|net)/(?:medias|embed)/|fast\.wistia\.net/embed|player\.bilibili\.com|bilibili\.com/video/|vk\.com/video|kick\.com/[^/]+/videos?/|redgifs\.com/(?:watch|ifr)/|xvideos\.com/video|pornhub\.com/(?:view_video|embed)|xhamster\.com/(?:videos|embed)|archive\.org/(?:details|embed)/)""",
        RegexOption.IGNORE_CASE)

    private val TIGHT_TAGS = setOf("figure", "article", "li", "dd", "td", "figcaption")
    private val TIGHT_CLASS = Regex("""(card|item|lesson|aula|post|entry|media|thumb|clip|video-item|gallery-item|grid-item|tile|box)""", RegexOption.IGNORE_CASE)
    private val LOOSE_TAGS = setOf("body", "html", "main", "header", "footer", "nav", "aside")
    private val MEDIA_TAGS = setOf("video", "audio", "img", "picture", "source", "figure", "iframe", "embed")
    private val HEADING_SEL = "h1,h2,h3,h4,h5,h6"

    fun host(u: String): String = try { (URI(u).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }

    fun abs(base: String, href: String?): String? {
        val h = href?.trim()?.replace("&amp;", "&") ?: return null
        if (h.isEmpty() || h.startsWith("data:") || h.startsWith("blob:") || h.startsWith("javascript:") || h.startsWith("mailto:") || h.startsWith("tel:") || h.startsWith("#") || h == "about:blank") return null
        return try {
            val r = URI(base).resolve(URI(h.replace(" ", "%20")))
            if (r.scheme != "http" && r.scheme != "https") null else URI(r.scheme, r.authority, r.path, r.query, null).toString()
        } catch (_: Exception) { null }
    }

    private val SEGMENT_URL = Regex("""(?i)(?:\.(?:ts|m4s|m4f|cmfv|cmfa)(?:$|[?#])|/(?:init|seg(?:ment)?[-_]?\d+|chunk[-_]?\d+|frag(?:ment)?[-_]?\d+)\.(?:mp4|m4s|webm|m4v)(?:$|[?#]))""")
    private val PREVIEW_VIDEO = Regex("""(?i)(?:^|[/?#&_.=-])(?:preview|previews|teaser|hover|thumb[-_]?video|video[-_]?thumb|videothumb|thumbpreview|animated[-_]?thumb|mini[-_]?video|sample[-_]?clip|sprite)(?:$|[/?#&_.=-])""")
    fun isSegmentUrl(u: String) = SEGMENT_URL.containsMatchIn(u.substringBefore('#'))
    fun isPreviewVideo(u: String) = PREVIEW_VIDEO.containsMatchIn(u.substringBefore('#'))
    fun isVideoUrl(u: String) = !isSegmentUrl(u) && (VIDEO_EXT.containsMatchIn(u.substringBefore('#')) || u.contains("video.twimg.com", true))
    fun isPhotoUrl(u: String) = PHOTO_EXT.containsMatchIn(u.substringBefore('#'))
    fun isHls(u: String) = RX_CRAWLER_1.containsMatchIn(u)
    fun isDash(u: String) = RX_CRAWLER_2.containsMatchIn(u)
    fun skipPage(u: String) = SKIP_EXT.containsMatchIn(u) || isVideoUrl(u) || isPhotoUrl(u)

    fun isIconOrSprite(u: String, el: Element? = null): Boolean {
        if (ICON_OR_SPRITE.containsMatchIn(u)) return true
        val w = el?.attr("width")?.toIntOrNull() ?: el?.attr("data-width")?.toIntOrNull() ?: 0
        val h = el?.attr("height")?.toIntOrNull() ?: el?.attr("data-height")?.toIntOrNull() ?: 0
        if ((w in 1..64) || (h in 1..64)) return true
        val cls = (el?.className().orEmpty() + " " + el?.id().orEmpty() + " " + el?.attr("alt"))
        if (RX_CRAWLER_3.containsMatchIn(cls)) return true
        var p = el?.parent()
        var hops = 0
        while (p != null && hops < 4) {
            if (p.tagName() in setOf("nav", "header", "footer")) return true
            p = p.parent(); hops++
        }
        return false
    }

    fun isVideoThumb(u: String): Boolean {
        if (u.isBlank()) return false
        return THUMB_URL.containsMatchIn(u) || ICON_OR_SPRITE.containsMatchIn(u) && THUMB_URL.containsMatchIn(u)
    }

    fun looksLikeVideoPage(u: String): Boolean {
        if (isVideoUrl(u) || YTDLP_HOSTS.containsMatchIn(u)) return true
        return VIDEO_PAGE_PATH.containsMatchIn(try { URI(u).path ?: u } catch (_: Exception) { u })
    }

    /** Capa/pôster de player, miniatura de card de vídeo, ou URL típica de thumb. */
    fun isCoverImage(u: String, el: Element? = null, pageUrl: String = ""): Boolean {
        if (isVideoThumb(u)) return true
        if (el == null) return false
        val cls = (el.className() + " " + el.id() + " " + el.attr("alt") + " " + el.attr("itemprop")).lowercase()
        if (RX_CRAWLER_4.containsMatchIn(cls)) return true
        if (el.closest("video") != null) return true
        fun isPlayer(e: Element?): Boolean {
            if (e == null) return false
            val tag = e.tagName()
            if (tag in setOf("video", "iframe", "embed", "object", "source")) return true
            if (e.className().contains("player", true) || e.className().contains("jwplayer", true)) return true
            return false
        }
        if (isPlayer(el.previousElementSibling()) || isPlayer(el.nextElementSibling())) return true
        val parent = el.parent()
        if (parent != null && parent.children().any { it !== el && isPlayer(it) }) return true
        val a = el.closest("a[href]")
        if (a != null) {
            val href = abs(pageUrl.ifBlank { a.baseUri() }, a.attr("href"))
            if (href != null) {
                if (isVideoUrl(href) || YTDLP_HOSTS.containsMatchIn(href) || looksLikeVideoPage(href)) return true
                // miniatura de card: o link aponta para uma página (não para o arquivo da foto)
                if (!isPhotoUrl(href) && !skipPage(href) && href.substringBefore('#') != pageUrl.substringBefore('#')) return true
            }
        }
        return false
    }

    fun sniffMagic(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "unknown"
        fun at(off: Int) = if (off in bytes.indices) bytes[off].toInt() and 0xFF else -1
        fun starts(off: Int, vararg xs: Int) = xs.indices.all { at(off + it) == xs[it] }
        fun ascii(off: Int, len: Int): String {
            if (off + len > bytes.size) return ""
            val cs = CharArray(len)
            for (k in 0 until len) cs[k] = at(off + k).toChar()
            return String(cs)
        }
        if (starts(0, 0xFF, 0xD8, 0xFF)) return "image"
        if (starts(0, 0x89, 0x50, 0x4E, 0x47)) return "image"
        if (starts(0, 0x47, 0x49, 0x46, 0x38)) return "image"
        if (starts(0, 0x52, 0x49, 0x46, 0x46) && ascii(8, 4) == "WEBP") return "image"
        if (starts(0, 0x42, 0x4D)) return "image"
        if (ascii(4, 4) == "ftyp") return "video"
        if (starts(0, 0x1A, 0x45, 0xDF, 0xA3)) return "video"
        var i = 0
        while (i < bytes.size && at(i) in setOf(0x09, 0x0A, 0x0D, 0x20)) i++
        val head = ascii(i, minOf(64, bytes.size - i)).lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml") || (head.startsWith("<") && head.contains("html"))) return "html"
        if (head.startsWith("#extm3u")) return "video"
        if (head.startsWith("riff") && head.contains("avi")) return "video"
        return "unknown"
    }

    fun parseSrcset(base: String, srcset: String): String? {
        var best: Pair<String, Int>? = null
        for (part in srcset.split(',')) {
            val bits = part.trim().split(RX_CRAWLER_5)
            if (bits.isEmpty()) continue
            val u = abs(base, bits[0]) ?: continue
            val desc = bits.getOrNull(1)?.lowercase().orEmpty()
            val score = Regex("""(\d+)w""").find(desc)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(\d+(?:\.\d+)?)x""").find(desc)?.groupValues?.get(1)?.toDoubleOrNull()?.times(1000)?.toInt()
                ?: 1
            if (best == null || score > best.second) best = u to score
        }
        return best?.first
    }

    fun fileName(u: String): String {
        val path = try { URI(u).path ?: u } catch (_: Exception) { u }
        val name = try { java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8") } catch (_: Exception) { path.substringAfterLast('/') }
        return name.substringBefore('?').ifBlank { "" }
    }

    fun mediaTitle(url: String, ctx: String): String {
        val c = TitleFix.clean(ctx)
        if (c.isNotBlank()) return c.take(150)
        return fileName(url).ifBlank { "arquivo" }.take(150)
    }

    fun kindFromContentType(ct: String?): Kind? {
        val c = (ct ?: "").lowercase().substringBefore(';').trim()
        if (c.isEmpty() || c.contains("text/html") || c.contains("text/plain") || c.contains("application/json") || c.contains("text/css") || c.contains("javascript")) return null
        if (c.contains("mpegurl") || c.contains("x-mpegurl")) return Kind.HLS
        if (c.contains("dash+xml") || c.endsWith("/mpd")) return Kind.SERVER
        if (c.startsWith("image/")) return null
        if (c.startsWith("video/") || c.contains("mp4") || c.contains("webm") || c.contains("quicktime") || c.contains("ogg")) return Kind.DIRECT
        return null
    }

    fun isSniffCandidate(u: String): Boolean {
        if (u.isBlank() || isVideoUrl(u) || isPhotoUrl(u) || YTDLP_HOSTS.containsMatchIn(u)) return false
        if (SKIP_EXT.containsMatchIn(u) && !isPhotoUrl(u)) return false
        return SNIFF_PATH.containsMatchIn(u)
    }

    fun mediaKindOf(u: String): Kind? = when {
        isSegmentUrl(u) -> null
        isPhotoUrl(u) && !isVideoUrl(u) -> null
        TitleFix.isSubtitleUrl(u) -> null
        isHls(u) -> Kind.HLS
        isDash(u) || YTDLP_HOSTS.containsMatchIn(u) -> Kind.SERVER
        isVideoUrl(u) -> Kind.DIRECT
        else -> null
    }


    data class PageResult(
        val videos: List<Found>,
        val links: List<String>,
        val title: String,
        val frames: List<String> = emptyList(),
        val linkText: Map<String, String> = emptyMap(),
        val sniffCandidates: List<Pair<String, String>> = emptyList(),
        val frameExtra: Map<String, String> = emptyMap(),
        val pageText: String = "",
    )

    private fun isHeading(e: Element) = e.tagName().matches(RX_CRAWLER_6) || e.hasClass("title") || e.hasClass("name")

    private fun isTrivial(e: Element): Boolean {
        val t = e.tagName()
        if (t in setOf("br", "hr", "script", "style", "link", "meta", "noscript", "svg", "path")) return true
        if (t in MEDIA_TAGS) return true
        if (t == "a" && e.select("img,video,source").isNotEmpty() && e.ownText().trim().length < 3) return true
        val text = e.text().replace(RX_CRAWLER_7, " ").trim()
        return text.length <= 2 && e.select("img,video,iframe,source").isEmpty()
    }

    private fun isSubstantial(e: Element): Boolean {
        if (e.tagName() in setOf("section", "article", "nav", "header", "footer", "main", "aside")) return true
        val text = e.ownText().ifBlank { e.text() }.replace(RX_CRAWLER_8, " ").trim()
        return text.length >= 40 && e.select("video,img,iframe").isEmpty()
    }

    private fun tightWrapper(e: Element): Element? {
        var p = e.parent()
        var hops = 0
        while (p != null && hops < 5) {
            val tag = p.tagName()
            if (tag in LOOSE_TAGS) return null
            if (tag in TIGHT_TAGS || TIGHT_CLASS.containsMatchIn(p.className()) || TIGHT_CLASS.containsMatchIn(p.id())) return p
            if (tag in setOf("section", "div") && isSmallBlock(p)) return p
            p = p.parent(); hops++
        }
        return null
    }

    private fun isSmallBlock(p: Element): Boolean {
        if (p.tagName() in LOOSE_TAGS) return false
        val media = p.select("video,audio,img,iframe,figure,source,picture").size
        if (media == 0) return false
        if (p.text().length > 2500) return false
        if (p.select("a[href]").size > 20) return false
        val body = p.ownerDocument()?.body()
        if (body != null && p !== body) {
            val bt = body.text().length
            if (bt > 400 && p.text().length > bt * 0.5) return false
        }
        return p.select(HEADING_SEL).isNotEmpty() || TIGHT_CLASS.containsMatchIn(p.className())
    }

    fun nearby(e: Element): String {
        val bits = ArrayList<String>()
        fun add(s: String?) { val t = s?.replace(RX_CRAWLER_9, " ")?.trim().orEmpty(); if (t.length > 1 && !TitleFix.isJunk(t)) bits += t }
        add(e.attr("alt")); add(e.attr("title")); add(e.attr("aria-label")); add(e.attr("data-title")); add(e.attr("data-name")); add(e.attr("data-caption"))
        add(e.ownText())
        val short = e.text().trim()
        if (short.length in 2..200) add(short)
        e.select("img[alt],img[title]").forEach { add(it.attr("alt")); add(it.attr("title")) }
        e.closest("figure")?.selectFirst("figcaption")?.let { add(it.text()) }
        e.closest("a")?.let { a -> add(a.attr("title")); add(a.attr("aria-label")); add(a.ownText()); add(a.text().take(200)) }
        var s = e.previousElementSibling()
        while (s != null) {
            if (isHeading(s)) { add(s.text()); break }
            if (isTrivial(s) || s.tagName() in MEDIA_TAGS) { s = s.previousElementSibling(); continue }
            if (isSubstantial(s)) break
            s = s.previousElementSibling()
        }
        val wrap = tightWrapper(e)
        if (wrap != null) {
            wrap.select("$HEADING_SEL, .title, .name, [class*=title]").firstOrNull { el -> el.closest("[class*=menu],[class*=settings],[class*=caption],[class*=subtitle],[class*=track],[class*=lang],[class*=audio],button") == null && !TitleFix.isJunk(el.text()) }?.let { add(it.text()) }
            wrap.selectFirst("figcaption")?.let { add(it.text()) }
            add(wrap.attr("title")); add(wrap.attr("aria-label")); add(wrap.attr("data-title")); add(wrap.attr("data-name"))
        }
        return bits.joinToString(" ").replace(RX_CRAWLER_10, " ").trim().take(400)
    }

    private fun putCtx(ctx: MutableMap<String, String>, url: String, text: String?) {
        val t = text?.replace(RX_CRAWLER_11, " ")?.trim().orEmpty(); if (t.length < 2) return
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
        val photos = LinkedHashSet<String>()
        val server = LinkedHashSet<String>()
        val frames = LinkedHashSet<String>()
        val ctx = HashMap<String, String>()
        val kinds = HashMap<String, Kind>()
        val linkText = LinkedHashMap<String, String>()
        val sniff = LinkedHashMap<String, String>()
        val frameExtra = LinkedHashMap<String, String>()
        val ogTitle = title
        val mainTitle = TitleFix.pick(
            TitleFix.stripSite(doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), site),
            TitleFix.stripSite(doc.selectFirst("meta[name=twitter:title]")?.attr("content").orEmpty(), site),
            doc.selectFirst("h1")?.text(),
            TitleFix.stripSite(doc.title(), site),
            Regex(""""@type"\s*:\s*"VideoObject"[\s\S]{0,800}?"name"\s*:\s*"([^"]{2,200})"""").find(html)?.groupValues?.get(1),
        )
        val pageText = listOfNotNull(
            doc.selectFirst("meta[property=og:description]")?.attr("content"), doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.selectFirst("meta[name=author]")?.attr("content"), doc.selectFirst("[itemprop=author]")?.text(), doc.selectFirst("a[rel=author]")?.text(),
            doc.select("[class*=channel],[class*=author],[class*=uploader],[class*=teacher],[class*=professor],[class*=instrutor],[class*=instructor]").take(3).joinToString(" ") { it.text().take(120) },
        ).joinToString(" ").replace(RX_CRAWLER_12, " ").trim().take(600)
        fun addVideo(u: String?, text: String? = null, kind: Kind? = null) {
            val a = abs(pageUrl, u) ?: return
            if (TitleFix.isSubtitleUrl(a) || isSegmentUrl(a)) return
            when {
                isDash(a) || (kind == Kind.SERVER) -> { if (isDash(a) || YTDLP_HOSTS.containsMatchIn(a)) { server += a; kinds[a] = Kind.SERVER; putCtx(ctx, a, text) } }
                isVideoUrl(a) -> { media += a; kinds[a] = if (isHls(a)) Kind.HLS else if (isDash(a)) Kind.SERVER else Kind.DIRECT; putCtx(ctx, a, text) }
                isSniffCandidate(a) -> { sniff[a] = (sniff[a]?.let { it + " " + (text ?: "") } ?: text.orEmpty()).trim(); putCtx(ctx, a, text) }
            }
            if (kind != null && a in media) kinds[a] = kind
        }
        fun addPhoto(u: String?, text: String? = null, el: Element? = null) {
            val a = abs(pageUrl, u) ?: return
            if (!isPhotoUrl(a) || isIconOrSprite(a, el) || isCoverImage(a, el, pageUrl)) return
            photos += a; kinds[a] = Kind.PHOTO; putCtx(ctx, a, text)
        }
        val posterUrls = HashSet<String>()
        doc.select("video[poster]").forEach { e ->
            abs(pageUrl, e.attr("poster"))?.let { posterUrls += it }
        }
        fun addServer(u: String?, text: String? = null) {
            val a = abs(pageUrl, u) ?: return
            if (YTDLP_HOSTS.containsMatchIn(a) || isDash(a)) { server += a; kinds[a] = Kind.SERVER; putCtx(ctx, a, text) }
        }

        val sameVideo = HashMap<String, Int>()
        doc.select("video[src], video source[src], source[src], video[data-src], source[data-src], video[data-url], source[data-url]").forEach { e ->
            val v = e.closest("video") ?: e
            val u = e.attr("src").ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") }
            addVideo(u, nearby(v))
            abs(pageUrl, u)?.let { sameVideo[it] = System.identityHashCode(v) }
        }
        doc.select("[data-video],[data-video-src],[data-stream],[data-file],[data-src][data-type*=video],[data-url]").forEach { e ->
            val u = e.attr("data-video").ifBlank { e.attr("data-video-src") }.ifBlank { e.attr("data-stream") }.ifBlank { e.attr("data-file") }.ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-url") }
            addVideo(u, nearby(e))
        }
        doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player:stream], meta[itemprop=contentUrl], link[itemprop=contentUrl]").forEach { e ->
            val u = e.attr("content").ifBlank { e.attr("href") }
            addVideo(u, ogTitle); addServer(u, ogTitle)
        }
        val hasOgVideo = doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]").any { it.attr("content").ifBlank { it.attr("href") }.isNotBlank() }
        if (!hasOgVideo) {
            doc.select("meta[property=og:image], meta[property=og:image:secure_url], meta[name=twitter:image]").forEach { e ->
                addPhoto(e.attr("content").ifBlank { e.attr("href") }, ogTitle)
            }
        }
        doc.select("img[src], img[data-src], img[data-lazy-src], img[data-original], picture source[srcset], img[srcset]").forEach { e ->
            val fromSet = parseSrcset(pageUrl, e.attr("srcset").ifBlank { e.attr("data-srcset") })
            val u = fromSet ?: e.attr("src").ifBlank { e.attr("data-src") }.ifBlank { e.attr("data-lazy-src") }.ifBlank { e.attr("data-original") }
            addPhoto(u, nearby(e.closest("img") ?: e), e.closest("img") ?: e)
        }
        doc.select("a[href]").forEach { e ->
            val a = abs(pageUrl, e.attr("href"))
            val txt = nearby(e)
            if (a != null && !skipPage(a)) linkText[a] = (linkText[a]?.let { it + " " + txt } ?: txt).trim()
            addVideo(e.attr("href"), txt); addServer(e.attr("href"), txt); addPhoto(e.attr("href"), txt, e)
        }

        val raw = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("&amp;", "&")
        URL_IN_TEXT.findAll(raw).forEach { m ->
            val u = m.value
            if (isPhotoUrl(u)) addPhoto(u) else addVideo(u)
        }
        KVS.findAll(raw).forEach { addVideo(it.groupValues[1].replace(RX_CRAWLER_13, "")) }
        REL_MEDIA.findAll(raw).forEach { m ->
            val u = m.groupValues[1]
            if (isPhotoUrl(u)) addPhoto(u) else addVideo(u)
        }
        PLAYER_URL.findAll(raw).forEach { m ->
            val u = m.groupValues[1]
            addVideo(u); addServer(u); if (isPhotoUrl(u)) addPhoto(u)
        }

        // JSON-LD: name/description só no contentUrl/embedUrl do mesmo objeto.
        doc.select("script[type=application/ld+json], script[type=application/json]").forEach { s ->
            val body = s.data().replace("\\/", "/")
            val objects = body.split(RX_CRAWLER_14).map { it.trim().trimStart('[', '{').trimEnd(']', '}') }
            val chunks = if (objects.size <= 1) listOf(body) else objects
            for (chunk in chunks) {
                val urls = LD_VIDEO.findAll(chunk).map { it.groupValues[1] }.toList()
                val names = Regex(""""(?:name|description|caption|headline)"\s*:\s*"([^"]{2,200})"""").findAll(chunk).map { it.groupValues[1] }.joinToString(" ")
                for (u in urls) {
                    addVideo(u, names.ifBlank { null }); addServer(u, names.ifBlank { null })
                }
            }
        }

        val frameEls = doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-url], embed[src], object[data], amp-iframe[src]")
        val frameSrcs = ArrayList<String>()
        for (e in frameEls) {
            val f = abs(pageUrl, e.attr("src").takeIf { it.isNotBlank() && it != "about:blank" } ?: e.attr("data-src").ifBlank { e.attr("data-lazy-src") }.ifBlank { e.attr("data-url") }.ifBlank { e.attr("data") }) ?: continue
            frameSrcs += f
            putCtx(frameExtra, f, nearby(e))
        }
        for (f in frameSrcs.distinct()) {
            val vz = VAZOU.find(f)
            when {
                vz != null -> {
                    val hls = "https://vazounudes.net/hls/${vz.groupValues[1].lowercase()}/playlist.m3u8"
                    media += hls; kinds[hls] = Kind.HLS; putCtx(ctx, hls, frameExtra[f])
                }
                YTDLP_HOSTS.containsMatchIn(f) -> { server += f; kinds[f] = Kind.SERVER; putCtx(ctx, f, frameExtra[f]) }
                !AD_FRAME.containsMatchIn(f) && !skipPage(f) -> frames += f
            }
        }
        doc.select("meta[name=twitter:player], meta[property=og:video:url]").forEach { addServer(it.attr("content"), ogTitle) }
        if (Bunkr.isBunkrPage(pageUrl)) {
            val cdn = media.firstOrNull { Bunkr.isCdn(it) }
            return PageResult(if (cdn != null) listOf(Found(cdn, pageUrl, title.substringBefore(" | "), Kind.DIRECT)) else emptyList(),
                doc.select("a[href]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct(), title)
        }
        val links = doc.select("a[href], link[rel=next]").mapNotNull { abs(pageUrl, it.attr("href")) }.filter { !skipPage(it) }.distinct()
        val vids = ArrayList<Found>()
        fun emit(u: String, defaultKind: Kind) {
            val k = kinds[u] ?: defaultKind
            val own = TitleFix.clean(ctx[u])
            val t = TitleFix.pick(own.take(150), mainTitle).ifBlank { mediaTitle(u, "") }
            val extra = listOf(own, mainTitle, pageText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(900)
            vids += Found(u, pageUrl, t, k, extra = extra)
        }
        // várias <source> do mesmo <video> (idiomas/qualidades) = um vídeo só, o de maior qualidade
        val dropVariants = HashSet<String>()
        sameVideo.entries.groupBy { it.value }.values.filter { it.size > 1 }.forEach { g ->
            val us = g.map { it.key }.filter { it in media }
            val best = us.maxByOrNull { MediaIdentity.withQuality(Found(it, pageUrl, "", Kind.DIRECT)).quality } ?: return@forEach
            us.filter { it != best }.forEach { dropVariants += it }
        }
        val nonPrev = media.any { !isPreviewVideo(it) && it !in dropVariants }
        media.filterNot { (nonPrev && isPreviewVideo(it)) || it in dropVariants }.ifEmpty { media.toList() }.forEach {
            emit(it, when { isHls(it) -> Kind.HLS; isDash(it) -> Kind.SERVER; else -> Kind.DIRECT })
        }
        server.forEach { emit(it, Kind.SERVER) }
        // v1.7.4: nunca coleta foto; capas/miniaturas também ficam de fora
        return PageResult(MediaIdentity.collapse(vids), links, mainTitle.ifBlank { title }, frames.take(16), linkText, sniff.toList(), frameExtra, pageText)
    }

    fun parseFrame(frameUrl: String, html: String, parentPage: String, parentTitle: String, parentExtra: String = ""): List<Found> =
        parse(frameUrl, html).videos.map { f ->
            val extra = listOf(f.extra, parentExtra).filter { it.isNotBlank() }.joinToString(" ")
            val title = mediaTitle(f.url, extra)
            if (f.kind == Kind.SERVER) f.copy(page = parentPage, title = title, extra = extra)
            else Found(f.url, parentPage, title, f.kind, frameUrl, extra)
        }
}

class Crawler(
    private val opts: CrawlOpts,
    private val sniff: (suspend (String) -> String?)? = null,
    private val jsFallback: (suspend (String) -> JsPage?)? = null,
    private val jsScroll: (suspend (String) -> JsPage?)? = null,
    private val upgrade: ((Found, Found) -> Unit)? = null,
    private val probe: (suspend (String) -> String?)? = null,
    val diag: Diag = Diag(),
) {
    private val challengedHosts = java.util.Collections.synchronizedSet(HashSet<String>())
    private var startRendered = false
    private val seenPages = HashSet<String>()
    private val seenFrames = HashSet<String>()
    private val seenVideos = HashSet<String>()
    private val emptyPages = ArrayList<Pair<String, String>>()
    private val startSniff = ArrayList<Pair<String, List<Pair<String, String>>>>()
    private val pageTitles = HashMap<String, String>()
    private val emittedByKey = HashMap<String, Found>()
    private var trustResults = false
    private var found = 0
    private var onFound: (Found) -> Unit = {}
    private var searchPages = 0
    private var cardsFound = 0
    private var failedCount = 0
    private var cardPagesUsed = 0
    private var startWasListing = false
    private var startJsTried = false
    private val failNotes = ArrayList<String>()

    private val par get() = opts.parallel.coerceIn(1, 32)
    private val words get() = opts.nameWords
    private val query get() = opts.nameQuery.ifBlank { words.joinToString(" ") }

    fun norm(u: String) = u.substringBefore('#').trimEnd('/').lowercase().replace("://www.", "://")

    private fun emit(f: Found) {
        var item = MediaIdentity.withQuality(f)
        if (item.kind == Kind.PHOTO) return
        if (Extract.isPhotoUrl(item.url) && !Extract.isVideoUrl(item.url)) return
        val hint = pageTitles[item.page] ?: pageTitles[norm(item.page)]
        if (!hint.isNullOrBlank()) {
            val title = if (item.title.isBlank() || TitleFix.isJunk(item.title) || item.title == Extract.fileName(item.url)) hint else item.title
            val extra = listOf(item.extra, hint).filter { it.isNotBlank() }.distinct().joinToString(" ")
            item = item.copy(title = title, extra = extra)
        }
        if (!trustResults && words.isNotEmpty() && !NameFilter.matches(item, words)) return
        val keys = MediaIdentity.keys(item)
        val hit = keys.firstOrNull { it in emittedByKey }
        if (hit != null) {
            val old = emittedByKey[hit]!!
            if (MediaIdentity.isBetter(item, old)) {
                keys.forEach { emittedByKey[it] = item }
                MediaIdentity.keys(old).forEach { emittedByKey[it] = item }
                seenVideos.remove(old.url.substringBefore('#'))
                seenVideos.add(item.url.substringBefore('#'))
                upgrade?.invoke(old, item)
            }
            return
        }
        if (!seenVideos.add(item.url.substringBefore('#'))) return
        keys.forEach { emittedByKey[it] = item }
        found++; diag.found = found; onFound(item)
    }

    private fun noteFail(msg: String) {
        failedCount++
        if (failNotes.size < 12 && msg.isNotBlank()) failNotes += msg
    }

    private fun detailLine(): String {
        val bits = ArrayList<String>()
        if (searchPages > 0) bits += "$searchPages págs. de busca"
        if (cardsFound > 0) bits += "$cardsFound cards"
        if (found > 0) bits += "$found vídeos"
        if (failedCount > 0) bits += "$failedCount sem stream"
        if (failNotes.isNotEmpty()) bits += failNotes.take(4).joinToString("; ")
        return bits.joinToString(" • ")
    }

    /** Busca nativa do site. 0 itens = o chamador cai na varredura completa. Confia nos cards da busca (sem filtro estrito). */
    suspend fun runSiteSearch(start: String, onProgress: (CrawlProgress) -> Unit, onFound: (Found) -> Unit): Int = coroutineScope {
        this@Crawler.onFound = onFound
        trustResults = true
        val startUrl = Extract.abs(start, start) ?: throw IllegalArgumentException("Link inválido")
        val root = Extract.host(startUrl)
        val method = SiteSearch.METHOD_SITE_SEARCH
        var done = 0
        fun progress(queued: Int, current: String) = onProgress(CrawlProgress(done, queued, found, current, method, searchPages, cardsFound, found, failedCount, detailLine()))

        progress(1, startUrl)
        diag.mode = "busca por nome"; diag.site = Diag.hash(startUrl)
        var startHtml = safeFetch(startUrl)
        if (startHtml == null && jsScroll != null && !Extract.isVideoUrl(startUrl)) {
            diag.note("página inicial não abriu direto: usando navegador oculto")
            val js = try { jsScroll.invoke(startUrl) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            if (js?.challenge == true) diag.challenge = true
            startHtml = js?.html
            if (startHtml != null) startRendered = true
        }
        seenPages += norm(startUrl)
        done++
        progress(0, startUrl)
        if (Extract.isVideoUrl(startUrl) || Extract.isPhotoUrl(startUrl)) {
            if (startHtml != null) Extract.parse(startUrl, startHtml).videos.forEach(::emit)
            return@coroutineScope found
        }

        val reqs = ArrayList<SiteSearch.SearchRequest>()
        val seenReq = HashSet<String>()
        fun addReqs(list: List<SiteSearch.SearchRequest>) {
            for (r in list) {
                val key = r.method.uppercase() + " " + r.url + " " + (r.body ?: "")
                if (Extract.host(r.url) == root && seenReq.add(key)) reqs += r
            }
        }
        if (startHtml != null) addReqs(SiteSearch.discoverRequests(startUrl, startHtml, query, includeGuesses = true))
        val osd = startHtml?.let { SiteSearch.openSearchHref(startUrl, it) }
        if (osd != null && Extract.host(osd) == root) {
            progress(1, osd)
            safeFetch(osd)?.let { xml ->
                done++
                SiteSearch.fromOpenSearch(xml, query, startUrl)?.let {
                    addReqs(listOf(SiteSearch.SearchRequest(it, source = "opensearch")))
                }
            }
        }
        if (reqs.none { it.source.startsWith("form") || it.source.startsWith("opensearch") || it.source == "json-api" }) {
            addReqs(SiteSearch.patternGuesses(startUrl, query))
            SiteSearch.originOf(startUrl)?.let { addReqs(SiteSearch.wpEndpoints(it, query)) }
        }

        // Páginas de tag/categoria/canal/autor cujo texto do link bate com o nome
        val taxonomy = LinkedHashSet<String>()
        if (startHtml != null && words.isNotEmpty()) {
            val pr = Extract.parse(startUrl, startHtml)
            for ((l, txt) in pr.linkText) {
                if (Extract.host(l) != root || taxonomy.size >= 12) continue
                if (!SiteSearch.looksLikeListingUrl(l) && !SiteSearch.TAXONOMY_PATH.containsMatchIn(l)) continue
                if (NameFilter.matchesText(txt + " " + l.replace('-', ' ').replace('_', ' ').replace('+', ' '), words)) taxonomy += l
            }
            if (taxonomy.isNotEmpty()) { diag.note("${taxonomy.size} págs. de tag/categoria/autor com o nome"); addReqs(taxonomy.map { SiteSearch.SearchRequest(it, source = "taxonomy") }) }
        }
        diag.searchRequests = reqs.size
        val hitSources = LinkedHashSet<String>()
        val resultPages = LinkedHashSet<String>()
        val paginate = ArrayDeque<String>()
        val triedSearch = HashSet<String>()
        val searchHits = ArrayList<String>()
        var usedAny = false

        fun ingestCards(url: String, body: String) {
            val cards = SiteSearch.resultCards(url, body)
            if (cards.isNotEmpty()) { usedAny = true; cardsFound += cards.size }
            for (c in cards) {
                if (c.title.isNotBlank()) {
                    pageTitles[c.url] = c.title
                    pageTitles[norm(c.url)] = c.title
                }
                if (Extract.isVideoUrl(c.url) || Extract.isPhotoUrl(c.url)) {
                    val kind = Extract.mediaKindOf(c.url) ?: continue
                    emit(Found(c.url, url, c.title.ifBlank { Extract.mediaTitle(c.url, query) }, kind, extra = c.title.ifBlank { query }))
                } else if (Extract.host(c.url) == root && seenPages.add(norm(c.url))) {
                    resultPages += c.url
                }
            }
            val rp = SiteSearch.resultLinks(url, body)
            for (l in rp.results) if (Extract.host(l) == root && seenPages.add(norm(l))) resultPages += l
            for (p in rp.pagination + SiteSearch.loadMoreUrls(url, body)) {
                if (Extract.host(p) == root && triedSearch.add(norm(p))) paginate.addLast(p)
            }
        }

        suspend fun ingestSearchDoc(url: String, body: String, ct: String) {
            if (SiteSearch.looksLikeJson(body, ct)) {
                val links = SiteSearch.parseSearchJson(body, url)
                if (links.isEmpty()) return
                usedAny = true
                searchHits += url
                searchPages++
                for (l in links) {
                    if (Extract.isPhotoUrl(l) && !Extract.isVideoUrl(l)) continue
                    if (Extract.isVideoUrl(l)) {
                        val kind = Extract.mediaKindOf(l) ?: Kind.DIRECT
                        emit(Found(l, url, Extract.mediaTitle(l, query), kind, extra = query))
                    } else if (Extract.host(l) == root && seenPages.add(norm(l))) {
                        resultPages += l
                    }
                }
                SiteSearch.nextSearchUrl(url)?.let { n -> if (triedSearch.add(norm(n))) paginate.addLast(n) }
                return
            }
            val looks = url in taxonomy || SiteSearch.isSearchUrl(url) || SiteSearch.looksLikeSearchResults(body, query, url, startHtml)
            if (!looks && url != startUrl) return
            searchPages++
            val before = resultPages.size
            ingestCards(url, body)
            Extract.parse(url, body).videos.filter { it.kind != Kind.PHOTO }.forEach(::emit)
            if (resultPages.size > before || SiteSearch.resultCards(url, body).isNotEmpty()) {
                usedAny = true
                searchHits += url
                if (url in taxonomy) diag.taxonomyPages++
                if (paginate.none { true } || (resultPages.size - before) >= 6) {
                    SiteSearch.nextSearchUrl(url)?.let { n -> if (triedSearch.add(norm(n))) paginate.addLast(n) }
                }
            }
        }

        if (startHtml != null && (SiteSearch.isSearchUrl(startUrl) || reqs.isEmpty())) {
            ingestSearchDoc(startUrl, startHtml, "text/html")
        }

        if (reqs.isNotEmpty()) progress(reqs.size, reqs.first().url)
        for (chunk in reqs.chunked(par)) {
            coroutineContext.ensureActive()
            val batch = chunk.map { r -> async { r to fetchDoc(r) } }.awaitAll()
            for ((r, pair) in batch) {
                done++
                val (body, ct) = pair ?: continue
                val before = resultPages.size + found
                ingestSearchDoc(r.url, body, ct)
                if (resultPages.size + found > before) hitSources += r.source
            }
        }

        val maxPag = 50
        var pagPages = 0
        var emptyStreak = 0
        while (paginate.isNotEmpty() && pagPages < maxPag && done < opts.maxPages) {
            val batch = ArrayList<String>()
            while (batch.size < par && paginate.isNotEmpty() && pagPages + batch.size < maxPag) batch += paginate.removeFirst()
            if (batch.isEmpty()) break
            progress(paginate.size + resultPages.size, batch.first())
            val pages = batch.map { u -> async { u to fetchDoc(SiteSearch.SearchRequest(u)) } }.awaitAll()
            var gained = 0
            for ((u, pair) in pages) {
                done++; pagPages++
                val before = resultPages.size
                val (body, ct) = pair ?: continue
                ingestSearchDoc(u, body, ct)
                gained += (resultPages.size - before)
            }
            if (gained == 0) {
                emptyStreak++
                if (emptyStreak >= 2) break
            } else emptyStreak = 0
        }

        // Busca renderizada no navegador oculto: site em JS (SPA), desafio anti-bot ou poucos resultados no HTML.
        if (resultPages.size < 12 && jsScroll != null) {
            val targets = LinkedHashSet<String>()
            searchHits.filter { it !in taxonomy }.take(2).forEach { targets += it }
            if (targets.isEmpty()) {
                reqs.filter { it.method.equals("GET", true) && (it.source.startsWith("form") || it.source.startsWith("opensearch")) }.take(2).forEach { targets += it.url }
                if (targets.isEmpty()) reqs.filter { it.method.equals("GET", true) && it.source == "pattern" }.take(2).forEach { targets += it.url }
            }
            var renderGain = 0
            for (u in targets) {
                coroutineContext.ensureActive()
                progress(resultPages.size, u)
                try {
                    val js = jsScroll.invoke(u) ?: continue
                    if (js.challenge) diag.challenge = true
                    diag.webCards = maxOf(diag.webCards, js.cards)
                    val before = resultPages.size + found
                    js.html?.let { html ->
                        done++; searchPages++
                        ingestCards(u, html)
                        Extract.parse(u, html).videos.forEach(::emit)
                    }
                    js.media.filter { it.kind != Kind.PHOTO }.forEach(::emit)
                    renderGain += resultPages.size + found - before
                    if (resultPages.size + found > before) { hitSources += "navegador"; searchHits += u }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                if (renderGain >= 12) break
            }
        }
        // paginação descoberta na busca renderizada (próxima página / rel=next): estático e, se vazio, navegador oculto
        run {
            var n = 0; var dry = 0
            while (paginate.isNotEmpty() && n < 25 && dry < 2) {
                coroutineContext.ensureActive()
                val u = paginate.removeFirst(); n++
                val before = resultPages.size + found
                fetchDoc(SiteSearch.SearchRequest(u))?.let { (b, ct) -> ingestSearchDoc(u, b, ct) }
                if (resultPages.size + found == before && jsScroll != null) {
                    try { jsScroll.invoke(u)?.html?.let { h -> searchPages++; ingestCards(u, h); Extract.parse(u, h).videos.forEach(::emit) } }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                }
                if (resultPages.size + found == before) dry++ else dry = 0
            }
        }
        diag.searchMethod = hitSources.joinToString("+") { when {
            it.startsWith("form") -> "formulário"; it.startsWith("opensearch") -> "opensearch"; it == "pattern" -> "padrão de URL"
            it.startsWith("wp") -> "API WordPress"; it == "json-api" -> "API JSON"; it == "taxonomy" -> "tag/categoria"; else -> it } }.ifBlank { if (usedAny) "busca do site" else "nenhuma busca respondeu" }

        diag.searchPages = searchPages
        val toOpen = resultPages.toList().take(opts.maxPages.coerceIn(1, 1000))
        resolvePages(toOpen, startUrl, root)

        trustResults = false
        if (!usedAny && found == 0) 0 else found
    }

    suspend fun run(start: String, onProgress: (CrawlProgress) -> Unit, onFound: (Found) -> Unit): Int = coroutineScope {
        this@Crawler.onFound = onFound
        trustResults = false
        val startUrl = Extract.abs(start, start) ?: throw IllegalArgumentException("Link inválido")
        val root = Extract.host(startUrl)
        val method = SiteSearch.METHOD_FULL_CRAWL
        val queue = ArrayDeque<Pair<String, Int>>()
        if (diag.mode.isBlank()) diag.mode = if (opts.resolvePlayer) "link/página" else "varrer site"
        diag.site = Diag.hash(startUrl)
        if (seenPages.add(norm(startUrl))) queue += startUrl to 0
        var done = 0
        fun progress(q: Int, cur: String) = onProgress(CrawlProgress(done, q, found, cur, method, 0, 0, found, 0, detailLine()))

        if (words.isEmpty() && Extract.YTDLP_HOSTS.containsMatchIn(startUrl)) emit(Found(startUrl, startUrl, startUrl, Kind.SERVER))
        if (Extract.isVideoUrl(startUrl)) {
            val k = when { Extract.isHls(startUrl) -> Kind.HLS; Extract.isDash(startUrl) -> Kind.SERVER; else -> Kind.DIRECT }
            emit(Found(startUrl, startUrl, startUrl.substringAfterLast('/'), k)); return@coroutineScope found
        }
        // link direto de imagem: não envia foto
        if (Extract.isPhotoUrl(startUrl) && !Extract.isVideoUrl(startUrl)) return@coroutineScope found

        val probeJob = if (opts.resolvePlayer && probe != null && !Extract.YTDLP_HOSTS.containsMatchIn(startUrl) && !Extract.isVideoUrl(startUrl))
            async {
                try { probe.invoke(startUrl) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            } else null

        seedSitemaps(startUrl, root, queue)
        progress(queue.size, startUrl)

        while (queue.isNotEmpty() && done < opts.maxPages) {
            coroutineContext.ensureActive()
            val batch = ArrayList<Pair<String, Int>>()
            while (batch.size < par && queue.isNotEmpty() && done + batch.size < opts.maxPages) batch += queue.removeFirst()
            if (batch.isEmpty()) break
            progress(queue.size, batch.first().first)
            val pages = batch.map { (u, d) -> async { Triple(u, d, safeFetch(u)) } }.awaitAll()
            for ((u, d, html0) in pages) {
                done++
                var html = html0
                if (html == null && norm(u) == norm(startUrl) && jsScroll != null && !startRendered) {
                    diag.note("página inicial não abriu direto: usando navegador oculto")
                    html = renderStartListing(startUrl)
                }
                if (html == null) { if (norm(u) == norm(startUrl)) diag.fail(u, "página inicial não abriu"); continue }
                consumePage(u, html, startUrl, root, enqueue = true, depth = d, queue = queue)
            }
            progress(queue.size, batch.last().first)
            // Com nome: para quando a fila de links relevantes acaba (não varre o resto do site).
            if (words.isNotEmpty() && queue.isEmpty()) break
        }

        // Página única (Filtrar / Início): WebView com autoplay + interceptação, depois yt-dlp se ainda não houver stream.
        if (opts.resolvePlayer && !startWasListing && jsFallback != null && found == 0) {
            startJsTried = true
            coroutineContext.ensureActive()
            progress(0, startUrl)
            try {
                val js = jsFallback.invoke(startUrl)
                js?.media?.filter { it.kind != Kind.PHOTO }?.forEach { m ->
                    emit(m.copy(page = startUrl, title = Extract.mediaTitle(m.url, m.extra), extra = m.extra))
                }
                js?.html?.let { Extract.parse(startUrl, it).videos.forEach(::emit) }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
        }
        val probedTitle = try { probeJob?.await() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (!probedTitle.isNullOrBlank() && !startWasListing) {
            val already = emittedByKey.values.any { it.kind != Kind.PHOTO && (norm(it.page) == norm(startUrl) || norm(it.url) == norm(startUrl)) }
            if (!already) emit(Found(startUrl, startUrl, probedTitle, Kind.SERVER, extra = probedTitle))
        }

        if (found == 0 && !startJsTried && jsFallback != null && opts.maxJsPages > 0) {
            val targets = LinkedHashSet<String>()
            targets += startUrl
            emptyPages.forEach { targets += it.first }
            for (u in targets.take(opts.maxJsPages)) {
                coroutineContext.ensureActive()
                progress(0, u)
                try {
                    val js = jsFallback.invoke(u) ?: continue
                    js.media.filter { it.kind != Kind.PHOTO }.forEach { m ->
                        val extra = m.extra
                        emit(m.copy(page = u, title = Extract.mediaTitle(m.url, extra), extra = extra))
                    }
                    js.html?.let { Extract.parse(u, it).videos.forEach(::emit) }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
        }
        if (found == 0 && sniff != null) {
            val cands = startSniff.flatMap { (page, list) -> list.map { page to it } }.take(8)
            for ((page, pair) in cands) {
                val (cu, ctext) = pair
                if (cu in seenVideos) continue
                try {
                    val ct = sniff.invoke(cu) ?: continue
                    val k = Extract.kindFromContentType(ct) ?: continue
                    emit(Found(cu, page, Extract.mediaTitle(cu, ctext), k, extra = ctext))
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
        }
        if (found == 0 && probe != null && opts.probeServer) {
            val targets = (listOf(startUrl) + emptyPages.map { it.first }).distinct().take(3)
            for (u in targets) {
                if (Extract.YTDLP_HOSTS.containsMatchIn(u)) continue
                try { probe.invoke(u)?.let { t -> emit(Found(u, u, t, Kind.SERVER, extra = t)) } } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
        found
    }


    /** Abre os cards de uma listagem e resolve o stream real. Não é crawl geral: funciona mesmo com maxDepth 0. */
    private suspend fun followListingCards(pageUrl: String, html: String, startUrl: String, root: String): Unit = coroutineScope {
        val cards = SiteSearch.resultCards(pageUrl, html)
        val videoCount = Extract.parse(pageUrl, html).videos.count { it.kind != Kind.PHOTO }
        if (!SiteSearch.isListingPage(pageUrl, html, cards, videoCount)) return@coroutineScope
        if (norm(pageUrl) == norm(startUrl)) startWasListing = true
        cardsFound += cards.size
        val toOpen = ArrayList<String>()
        for (c in cards) {
            if (c.title.isNotBlank()) {
                pageTitles[c.url] = c.title
                pageTitles[norm(c.url)] = c.title
            }
            if (Extract.isVideoUrl(c.url)) {
                val kind = Extract.mediaKindOf(c.url) ?: Kind.DIRECT
                emit(Found(c.url, pageUrl, c.title.ifBlank { Extract.mediaTitle(c.url, c.title) }, kind, extra = c.title))
                continue
            }
            if (Extract.isPhotoUrl(c.url) && !Extract.isVideoUrl(c.url)) continue
            if (Extract.host(c.url) != root) continue
            if (norm(c.url) == norm(pageUrl)) continue
            if (seenPages.add(norm(c.url))) toOpen += c.url
        }
        val room = (opts.maxCardPages - cardPagesUsed).coerceAtLeast(0)
        resolvePages(toOpen.take(room), startUrl, root)
    }

    /** Renderiza a página inicial no WebView (rola, coleta cards do DOM). Devolve o HTML com os cards. */
    private suspend fun renderStartListing(u: String): String? {
        startRendered = true
        val js = try { jsScroll?.invoke(u) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        if (js == null) { diag.note("navegador oculto não respondeu"); return null }
        if (js.challenge) diag.challenge = true
        diag.webCards = js.cards
        diag.note("navegador: ${js.cards} cards, ${js.media.size} mídias interceptadas")
        js.media.filter { it.kind != Kind.PHOTO }.forEach(::emit)
        return js.html
    }

    private fun shape(u: String): String = try {
        val segs = (URI(u).path ?: "").split('/').filter { it.isNotBlank() }
        (segs.firstOrNull()?.takeIf { s0 -> segs.size > 1 && !s0.any(Char::isDigit) } ?: "*") + "/" + segs.size.coerceAtMost(4)
    } catch (_: Exception) { "?" }

    private suspend fun tryProbe(u: String): String? = try { probe?.invoke(u) } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e; null
    }

    private fun titleFor(u: String): String = pageTitles[u] ?: pageTitles[norm(u)] ?: Extract.fileName(u).replace('-', ' ').replace('_', ' ').ifBlank { u }

    /**
     * Resolve páginas de vídeo (cards): 1) HTML estático em paralelo; 2) yt-dlp (testa 2, e se o servidor entende
     * o site confia nos demais do mesmo formato — o download resolve); 3) WebView oculto com play.
     */
    private suspend fun resolvePages(pages0: List<String>, startUrl: String, root: String): Unit = coroutineScope {
        val pages = pages0.distinct()
        if (pages.isEmpty()) return@coroutineScope
        diag.resultPages += pages.size
        val left = ArrayList<String>()
        var staticOk = root !in challengedHosts
        if (!staticOk && pages.isNotEmpty()) {
            // depois do navegador resolver o desafio, os cookies podem liberar o acesso direto: testa um
            val body = safeFetch(pages.first())
            if (body != null) { staticOk = true; challengedHosts.remove(root); diag.note("acesso direto liberado após navegador") }
        }
        if (staticOk) {
            for (chunk in pages.chunked(par)) {
                coroutineContext.ensureActive()
                val got = chunk.map { href -> async { href to safeFetch(href) } }.awaitAll()
                for ((href, body) in got) {
                    cardPagesUsed++
                    if (body == null) { left += href; continue }
                    val before = found
                    consumePage(href, body, startUrl, root, enqueue = false, depth = 999, queue = null, followCardsOnThisPage = false)
                    if (found == before) left += href else diag.resolvedStatic.incrementAndGet()
                }
            }
        } else left += pages
        if (left.isEmpty()) return@coroutineScope
        diag.note("${left.size} págs. sem stream no HTML")

        // 2) WebView oculto com play/"assistir" (vídeo que só carrega ao abrir a página e tocar)
        if (left.isNotEmpty() && jsFallback != null && diag.webviewAvailable) {
            var misses = 0
            val todo = left.take(80)
            for (chunk in todo.chunked(3)) {
                coroutineContext.ensureActive()
                val got = chunk.map { u -> async { u to PageResolver.viaWebView(u) { x -> jsFallback.invoke(x) } } }.awaitAll()
                var anyChunk = false
                for ((u, res) in got) {
                    val (realTitle, best) = res
                    if (best == null) continue
                    val t = TitleFix.pick(realTitle, titleFor(u)).ifBlank { titleFor(u) }
                    val before = found
                    emit(best.copy(page = u, ref = best.ref ?: u, title = t, extra = listOf(t, titleFor(u), best.extra).joinToString(" ").trim()))
                    if (found > before || emittedByKey.values.any { it.page == u }) { left.remove(u); anyChunk = true; diag.resolvedWebView.incrementAndGet() }
                }
                if (!anyChunk) { misses++; if (misses >= 3) break } else misses = 0
            }
        }

        // 3) servidor yt-dlp para o que sobrou
        if (left.isNotEmpty() && probe != null) {
            val sample = left.take(2)
            val res = sample.map { u -> async { u to tryProbe(u) } }.awaitAll()
            val okShapes = HashSet<String>()
            for ((u, t) in res) if (t != null) {
                left.remove(u); okShapes += shape(u); diag.resolvedYtdlp.incrementAndGet()
                emit(Found(u, u, TitleFix.pick(pageTitles[u], t).ifBlank { t }, Kind.SERVER, extra = listOfNotNull(pageTitles[u], t).joinToString(" ")))
            }
            diag.ytdlp = if (okShapes.isNotEmpty()) "ok (reconhece o site)" else (Downloader.lastProbeError?.let { "falhou: " + it.replace(RX_CRAWLER_15, "<url>").take(120) } ?: "não reconheceu")
            if (okShapes.isNotEmpty()) {
                for (chunk in left.toList().take(60).chunked(4)) {
                    coroutineContext.ensureActive()
                    val jobs = chunk.map { u -> async { u to tryProbe(u) } }.awaitAll()
                    for ((u, t) in jobs) if (t != null) {
                        left.remove(u); diag.resolvedYtdlp.incrementAndGet()
                        emit(Found(u, u, TitleFix.pick(pageTitles[u], t).ifBlank { t }, Kind.SERVER, extra = listOfNotNull(pageTitles[u], t).joinToString(" ")))
                    }
                }
            }
        }
        val why = when {
            jsFallback != null && !diag.webviewAvailable -> "navegador oculto indisponível"
            probe != null && jsFallback != null -> "nem HTML, nem yt-dlp, nem navegador acharam o vídeo"
            else -> "sem stream"
        }
        for (u in left) {
            if (emittedByKey.values.none { it.page == u || it.url == u }) { diag.fail(u, why); noteFail("sem stream em ${titleFor(u).take(50)}") }
        }
    }

    private suspend fun consumePage(
        u: String,
        html: String,
        startUrl: String,
        root: String,
        enqueue: Boolean,
        depth: Int,
        queue: ArrayDeque<Pair<String, Int>>?,
        followCardsOnThisPage: Boolean = true,
    ): Unit = coroutineScope {
        val r = Extract.parse(u, html)
        val before = found
        r.videos.forEach(::emit)
        if (u == startUrl) startSniff += u to r.sniffCandidates

        val frameJobs = ArrayList<Triple<String, String, String>>()
        r.frames.forEach { f ->
            if (seenFrames.size < opts.maxFrames && seenFrames.add(f)) {
                frameJobs += Triple(u, r.frameExtra[f].orEmpty(), f)
            }
        }
        val frameRes = frameJobs.chunked(par).flatMap { ch ->
            ch.map { t -> async { t to safeFetch(t.third) } }.awaitAll()
        }
        val nestedFound = ArrayList<Pair<Triple<String, String, String>, String?>>()
        if (opts.maxFrameDepth >= 2) {
            val nested = ArrayList<Triple<String, String, String>>()
            for ((k, fhtml) in frameRes) {
                if (fhtml == null) continue
                val pr = Extract.parse(k.third, fhtml)
                pr.frames.forEach { f ->
                    if (seenFrames.size < opts.maxFrames && seenFrames.add(f)) {
                        nested += Triple(k.first, listOf(k.second, pr.frameExtra[f].orEmpty()).filter { it.isNotBlank() }.joinToString(" "), f)
                    }
                }
            }
            nestedFound += nested.chunked(par).flatMap { ch ->
                ch.map { t -> async { t to safeFetch(t.third) } }.awaitAll()
            }
        }
        frameRes.forEach { (k, fhtml) ->
            if (fhtml != null) Extract.parseFrame(k.third, fhtml, u, r.title, k.second).forEach(::emit)
        }
        nestedFound.forEach { (k, fhtml) ->
            if (fhtml != null) Extract.parseFrame(k.third, fhtml, u, r.title, k.second).forEach(::emit)
        }

        if (sniff != null && opts.maxSniffs > 0 && r.sniffCandidates.isNotEmpty()) {
            var nSniff = 0
            for ((cu, ctext) in r.sniffCandidates) {
                if (nSniff >= opts.maxSniffs) break
                if (cu in seenVideos) continue
                nSniff++
                try {
                    val ct = sniff.invoke(cu)
                    val k = Extract.kindFromContentType(ct) ?: continue
                    emit(Found(cu, u, Extract.mediaTitle(cu, ctext), k, extra = ctext))
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
        }

        val gained = found > before
        if (!gained && !opts.resolvePlayer && probe != null && !Extract.YTDLP_HOSTS.containsMatchIn(u) && u == startUrl && !opts.probeServer) {
            try { probe.invoke(u)?.let { t -> emit(Found(u, u, t, Kind.SERVER, extra = t)) } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        if (!gained) emptyPages += u to html

        var listingHtml = html
        if (followCardsOnThisPage && norm(u) == norm(startUrl)) {
            val early = SiteSearch.resultCards(u, html)
            if (diag.staticCards < 0) diag.staticCards = early.size
            val vcount = r.videos.count { it.kind != Kind.PHOTO }
            // PRINCIPAL: listagem renderizada no navegador oculto quando o HTML estático não mostra os cards
            if (opts.followCards && jsScroll != null && !startRendered && vcount == 0 &&
                (early.size < 2 || root in challengedHosts || (SiteSearch.isListingPage(u, html, early, vcount) && early.size < 12))) {
                renderStartListing(u)?.let { listingHtml = it }
            }
        } else if (followCardsOnThisPage && opts.followCards && jsScroll != null && SiteSearch.looksLikeListingUrl(u)) {
            val earlyCards = SiteSearch.resultCards(u, html)
            val vcount = r.videos.count { it.kind != Kind.PHOTO }
            if (SiteSearch.isListingPage(u, html, earlyCards, vcount) && earlyCards.size < 12) {
                try {
                    val js = jsScroll.invoke(u)
                    js?.media?.filter { it.kind != Kind.PHOTO }?.forEach(::emit)
                    js?.html?.let { body ->
                        listingHtml = body
                        Extract.parse(u, body).videos.forEach(::emit)
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
        }
        if (followCardsOnThisPage && opts.followCards) {
            followListingCards(u, listingHtml, startUrl, root)
        }

        if (enqueue && queue != null && depth < opts.maxDepth) {
            val match = ArrayList<Pair<String, Int>>()
            val other = ArrayList<Pair<String, Int>>()
            for (l in r.links) {
                if (Extract.host(l) != root) continue
                if (!seenPages.add(norm(l))) continue
                val txt = (r.linkText[l] ?: "") + " " + l
                if (words.isEmpty() || SiteSearch.isSearchUrl(l) || NameFilter.matchesAny(txt, words)) match += l to depth + 1
                else if (words.isEmpty()) other += l to depth + 1
            }
            for (i in other.indices.reversed()) queue.addLast(other[i])
            for (i in match.indices.reversed()) queue.addFirst(match[i])
        }
    }

    private suspend fun seedSitemaps(startUrl: String, root: String, queue: ArrayDeque<Pair<String, Int>>) = coroutineScope {
        val origin = SiteSearch.originOf(startUrl) ?: return@coroutineScope
        val seeds = listOf("$origin/robots.txt", "$origin/sitemap.xml", "$origin/sitemap_index.xml")
        val bodies = seeds.map { u -> async { u to safeFetch(u) } }.awaitAll()
        val locs = LinkedHashSet<String>()
        val childMaps = ArrayList<String>()
        for ((u, body) in bodies) {
            if (body == null) continue
            if (u.endsWith("robots.txt")) {
                Regex("""(?im)^sitemap:\s*(\S+)""").findAll(body).forEach { m ->
                    Extract.abs(startUrl, m.groupValues[1])?.let { childMaps += it }
                }
            } else {
                collectSitemap(body, startUrl, root, locs, childMaps)
            }
        }
        for (chunk in childMaps.distinct().take(5).chunked(par)) {
            val more = chunk.map { u -> async { u to safeFetch(u) } }.awaitAll()
            for ((_, body) in more) if (body != null) collectSitemap(body, startUrl, root, locs, childMaps)
        }
        var added = 0
        for (l in locs) {
            if (added >= 400) break
            if (Extract.host(l) != root) continue
            if (Extract.skipPage(l)) continue
            if (words.isNotEmpty() && !NameFilter.matchesAny(l, words) && !SiteSearch.isSearchUrl(l)) continue
            if (seenPages.add(norm(l))) { queue.addLast(l to 1); added++ }
        }
    }

    private fun collectSitemap(body: String, base: String, root: String, locs: MutableSet<String>, childMaps: MutableList<String>) {
        Regex("""<(?:loc|LOC)>\s*([^<\s]+)\s*</(?:loc|LOC)>""").findAll(body).forEach { m ->
            val a = Extract.abs(base, m.groupValues[1].trim()) ?: return@forEach
            if (Extract.host(a) != root) return@forEach
            val path = try { java.net.URI(a).path ?: a } catch (_: Exception) { a }
            if (path.contains("sitemap", true) && (path.endsWith(".xml", true) || path.contains("sitemap", true))) childMaps += a
            else locs += a
        }
    }

    private suspend fun safeFetch(url: String): String? = try { fetchHtml(url)?.first } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e; null
    }

    private val noFetch = Regex(
        """\.(jpe?g|png|gif|webp|svg|ico|css|js|woff2?|ttf|eot|mp3|wav|ogg|mp4|webm|m3u8|mpd|mov|m4v|mkv|pdf|zip|rar|7z|gz|apk|exe|dmg|avif|bmp|wasm|map)(?:$|[?#])""",
        RegexOption.IGNORE_CASE,
    )

    private suspend fun fetchDoc(req: SiteSearch.SearchRequest): Pair<String, String>? = try {
        fetchHtml(req.url, req.method, req.body, req.contentType)
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }

    private suspend fun fetchHtml(url: String, method: String = "GET", body: String? = null, contentType: String? = null): Pair<String, String>? {
        if (noFetch.containsMatchIn(url.substringBefore('?'))) return null
        val b = Request.Builder().url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json,text/plain;q=0.9,*/*;q=0.1")
        if (method.equals("POST", true)) {
            val mt = (contentType ?: "application/x-www-form-urlencoded").toMediaType()
            b.post((body ?: "").toRequestBody(mt))
        }
        Net.fast.newCall(b.build()).await().use { r ->
            diag.code(r.code)
            if (!r.isSuccessful) {
                val srv = (r.header("Server") ?: "").lowercase()
                if (r.header("cf-mitigated") != null || (r.code in setOf(403, 429, 503) && (srv.contains("cloudflare") || srv.contains("ddos-guard") || r.header("cf-ray") != null))) {
                    if (!diag.challenge) diag.note("desafio anti-bot (HTTP ${r.code})")
                    diag.challenge = true; challengedHosts += Extract.host(url)
                }
                return null
            }
            val ct = r.header("Content-Type") ?: ""
            val ok = ct.isEmpty() || listOf("html", "xml", "json", "javascript", "text/plain", "text/").any { ct.contains(it, true) }
            if (!ok) return null
            val respBody = r.body ?: return null
            val src = respBody.source()
            val cap = opts.htmlMaxBytes
            src.request(cap)
            val text = src.buffer.readUtf8(minOf(src.buffer.size, cap))
            if (ct.contains("html", true) && JsScripts.isChallengeHtml(text) && text.length < 60_000) {
                if (!diag.challenge) diag.note("desafio anti-bot (página de verificação)")
                diag.challenge = true; challengedHosts += Extract.host(url)
                return null
            }
            return text to ct
        }
    }
}
