package br.geracaoai.baixartg.core

import java.net.URI
import java.net.URLDecoder

/**
 * Agrupa URLs do mesmo vídeo (qualidades, tokens de CDN, a mesma aula em duas páginas)
 * e escolhe a maior qualidade.
 */
object MediaIdentity {
    private val HEIGHT = Regex(
        """(?:^|[/_.=-])((?:2160|1440|1080|720|480|360|240|144))p?(?:$|[/_.=-])""",
        RegexOption.IGNORE_CASE,
    )
    private val NAMED = mapOf(
        "2160" to 2160, "1440" to 1440, "1080" to 1080, "720" to 720, "480" to 480,
        "360" to 360, "240" to 240, "144" to 144,
        "4k" to 2160, "uhd" to 2160, "2160p" to 2160,
        "2k" to 1440, "qhd" to 1440,
        "fhd" to 1080, "fullhd" to 1080,
        "hd" to 720, "sd" to 480,
        "high" to 720, "hq" to 720,
        "low" to 360, "lq" to 360,
    )
    private val NAMED_RE = Regex(
        """(?:^|[/_.=-])(4k|uhd|2160p?|1440p?|2k|qhd|1080p?|fhd|fullhd|720p?|hd|480p?|sd|360p?|240p?|144p?|high|hq|low|lq|orig(?:inal)?|best|worst|_q\d+)(?:$|[/_.=-])""",
        RegexOption.IGNORE_CASE,
    )
    private val BITRATE = Regex("""(?:^|[/_.=-])(\d{3,5})k(?:bps)?(?:$|[/_.=-])""", RegexOption.IGNORE_CASE)
    private val QUALITY_STRIP = Regex(
        """[-_./]?(?:2160|1440|1080|720|480|360|240|144)p?|[-_./]?(?:4k|uhd|2k|qhd|fhd|fullhd|\bhd\b|\bsd\b|high|hq|low|lq|original|orig|best|worst|_q\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val SKIP_QUERY = setOf(
        "signature", "sig", "expires", "expiry", "expire", "token", "hmac", "hash", "auth",
        "policy", "key-pair-id", "key_pair_id", "awsaccesskeyid", "x-amz-signature", "x-amz-security-token",
        "x-amz-credential", "x-amz-date", "x-amz-expires", "x-amz-signedheaders", "awsaccesskeyid",
        "st", "e", "start", "end", "t", "s", "uid", "session", "jwt", "access_key", "key",
        "utm_source", "utm_medium", "utm_campaign", "fbclid", "gclid",
    )
    private val WEAK_STEMS = setOf(
        "master", "playlist", "index", "manifest", "video", "source", "file", "media",
        "stream", "movie", "clip", "default", "output", "download", "play", "player",
    )
    private val MASTER_NAME = Regex("""(?:^|/)(?:master|playlist|index|manifest|video)(?:_all)?\.m3u8(?:$|\?)""", RegexOption.IGNORE_CASE)

    fun qualityOf(url: String, extra: String = ""): Int {
        val blob = "$extra $url"
        HEIGHT.findAll(blob).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()?.let { if (it in 144..2160) return it }
        NAMED_RE.findAll(blob).mapNotNull { NAMED[it.groupValues[1].lowercase()] }.maxOrNull()?.let { return it }
        return 0
    }

    fun qualityLabel(q: Int): String = when {
        q <= 0 -> ""
        q >= 2160 -> "2160p"
        else -> "${q}p"
    }

    fun isLikelyMaster(url: String): Boolean {
        if (!Extract.isHls(url) && !Extract.isDash(url)) return false
        return qualityOf(url) == 0 && (MASTER_NAME.containsMatchIn(url) || Extract.isDash(url))
    }

    /** Pontuação: master HLS/DASH na frente; depois altura; bitrate no path; tipo (HLS/DASH > mp4). */
    fun score(f: Found, length: Long = -1): Long {
        var s = 0L
        if (isLikelyMaster(f.url) || (f.kind == Kind.HLS && qualityOf(f.url) == 0) || (f.kind == Kind.SERVER && Extract.isDash(f.url))) s += 3_000_000_000L
        val q = if (f.quality > 0) f.quality else qualityOf(f.url, f.extra + " " + f.title)
        s += q * 1_000_000L
        BITRATE.find(f.url)?.groupValues?.get(1)?.toLongOrNull()?.let { s += it }
        if (length > 0) s += (length / 1024).coerceAtMost(500_000)
        when (f.kind) {
            Kind.HLS, Kind.SERVER -> s += 1_000
            Kind.DIRECT -> s += 100
            else -> {}
        }
        return s
    }

    fun isBetter(a: Found, b: Found, sizeA: Long = -1, sizeB: Long = -1): Boolean = score(a, sizeA) > score(b, sizeB)

    fun withQuality(f: Found): Found {
        val q = f.quality.takeIf { it > 0 } ?: qualityOf(f.url, f.extra + " " + f.title)
        return if (q != f.quality) f.copy(quality = q) else f
    }

    fun canonicalPage(url: String): String = Sent.norm(url)

    fun canonicalMedia(url: String): String {
        val base = stripQuery(url)
        return stripQuality(base)
    }

    fun fileStem(url: String): String {
        val path = try {
            val raw = URI(url.trim().substringBefore('#')).rawPath ?: url
            try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
        } catch (_: Exception) { url.substringBefore('?').substringBefore('#') }
        val file = path.substringAfterLast('/').substringBefore('?')
        val noExt = file.replace(Regex("""\.(mp4|webm|m3u8|mpd|mov|m4v|mkv|ts)$""", RegexOption.IGNORE_CASE), "")
        return stripQuality(noExt).trim('-', '_', '.').lowercase()
    }

    fun usefulStem(stem: String): Boolean = stem.length >= 3 && stem !in WEAK_STEMS

    fun mediaDir(url: String): String = stripQuery(url).substringBeforeLast('/')

    fun keys(f: Found): List<String> {
        val out = LinkedHashSet<String>()
        val media = canonicalMedia(f.url)
        if (media.isNotBlank()) out += "m:$media"
        val page = canonicalPage(f.page)
        val stem = fileStem(f.url)
        if (page.isNotBlank() && usefulStem(stem)) out += "ps:$page|$stem"
        if (usefulStem(stem) && stem.length >= 8) out += "s:$stem"
        if (Extract.isHls(f.url) || Extract.isDash(f.url)) {
            val dir = mediaDir(f.url)
            if (dir.length >= 8) out += "d:$dir"
        }
        return out.toList()
    }

    fun same(a: Found, b: Found): Boolean {
        if (a.kind == Kind.PHOTO || b.kind == Kind.PHOTO) return canonicalMedia(a.url) == canonicalMedia(b.url) && a.url.isNotBlank()
        val ka = keys(a).toSet()
        return ka.isNotEmpty() && keys(b).any { it in ka }
    }

    fun collapse(list: List<Found>, sizes: Map<String, Long> = emptyMap()): List<Found> {
        val groups = ArrayList<ArrayList<Found>>()
        for (f0 in list) {
            val f = withQuality(f0)
            val g = groups.firstOrNull { slot -> slot.any { same(it, f) } }
            if (g != null) g += f else groups += arrayListOf(f)
        }
        return groups.map { slot ->
            slot.maxWithOrNull { a, b ->
                score(a, sizes[a.url] ?: -1).compareTo(score(b, sizes[b.url] ?: -1))
            }!!
        }
    }

    private fun stripQuery(url: String): String = try {
        val x = URI(url.trim().substringBefore('#').replace(" ", "%20"))
        val host = (x.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
        val path = (try { URLDecoder.decode(x.rawPath ?: "", "UTF-8") } catch (_: Exception) { x.rawPath ?: "" }).trimEnd('/')
        val kept = (x.query ?: "").split('&').mapNotNull {
            val i = it.indexOf('=')
            val n = (if (i < 0) it else it.substring(0, i)).lowercase()
            if (n.isBlank() || n in SKIP_QUERY) null else it
        }
        val q = if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        host + path.lowercase() + q
    } catch (_: Exception) {
        Sent.norm(url)
    }

    private fun stripQuality(s: String): String {
        var t = s
        t = HEIGHT.replace(t, "-")
        t = NAMED_RE.replace(t, "-")
        t = t.replace(Regex("[-_.]{2,}"), "-").trim('-', '_', '.')
        return t
    }
}
