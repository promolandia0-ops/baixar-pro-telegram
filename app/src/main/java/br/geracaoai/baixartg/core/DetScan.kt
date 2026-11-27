package br.geracaoai.baixartg.core

/**
 * Detetive — leitura de endereços de mídia em qualquer texto (HTML, JSON, JavaScript, respostas de rede, configs de
 * player), inclusive escondidos: barras escapadas (\/), /, &amp;, endereço inteiro codificado (%3A%2F%2F),
 * base64, protocolo relativo (//cdn...) e caminhos relativos com extensão de mídia.
 * Também reconhece o tipo do arquivo pelos primeiros bytes (sem confiar no nome).
 * Puro Kotlin (sem Android) para poder ser testado.
 */
object DetScan {
    enum class T { HLS, DASH, FILE, AUDIO, IMAGE, EMBED, MAYBE }
    data class Hit(val url: String, val t: T)

    private val ABS = Regex("""(?i)(?:https?:)?//[a-z0-9][a-z0-9.\-]*\.[a-z]{2,}(?::\d+)?(?:/[^\s"'<>`\\{}|^\[\]]*)?""")
    private val REL_MEDIA = Regex("""(?i)["'(=]\s*((?:\.{0,2}/)?[a-z0-9_\-./%]+\.(?:m3u8|mpd|mp4|m4v|webm|mov|mkv)(?:\?[^\s"'<>\\]*)?)""")
    private val B64 = Regex("""[A-Za-z0-9+/_\-]{40,}={0,2}""")

    private val HLS = Regex("""(?i)\.m3u8(?:$|[?#&/])|/hls/.*(?:master|index|playlist)|[?&]format=m3u8|/manifest/video\.m3u8|/playlist\.m3u8""")
    private val DASH = Regex("""(?i)\.mpd(?:$|[?#&/])|[?&]format=mpd|/manifest\.mpd|/dash/.*manifest""")
    private val FILE = Regex("""(?i)\.(?:mp4|m4v|webm|mov|mkv|avi|flv|3gp|ogv)(?:$|[?#&/])""")
    private val AUDIO = Regex("""(?i)\.(?:mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:$|[?#&])""")
    private val IMAGE = Regex("""(?i)\.(?:jpe?g|png|webp|avif|gif|bmp|heic)(?:$|[?#&])""")
    private val SEGMENT = Regex("""(?i)(?:\.(?:ts|m4s|aac|vtt|srt|key)(?:$|[?#])|/seg(?:ment)?[-_]?\d+|/chunk[-_]?\d+|/frag(?:ment)?[-_]?\d+|init\.mp4|/range/\d+-\d+)""")
    private val JUNK = Regex("""(?i)\.(?:css|js|mjs|woff2?|ttf|otf|eot|svg|ico|map|json|xml|html?|php|txt)(?:$|[?#])|google-analytics|googletagmanager|doubleclick|facebook\.net/|/pixel|/beacon|/collect\?|hotjar|clarity\.ms|sentry|/favicon""")
    private val MAYBE = Regex("""(?i)(?:manifest|playlist|master|/hls/|/dash/|/vod/|/stream(?:s|ing)?/|videoplayback|/media/|/video/|/videos/|/play/|/playback|/delivery/|/download)""")

    /** Endereços de player conhecidos (para o detetive abrir/perguntar ao servidor). */
    fun isEmbed(u: String): Boolean = DetKnowledge.recipeFor(u) != null

    fun classify(u: String): T? {
        val s = u.substringBefore('#')
        if (!s.startsWith("http", true)) return null
        if (SEGMENT.containsMatchIn(s) && !HLS.containsMatchIn(s)) return null
        return when {
            HLS.containsMatchIn(s) -> T.HLS
            DASH.containsMatchIn(s) -> T.DASH
            FILE.containsMatchIn(s) -> T.FILE
            IMAGE.containsMatchIn(s) -> T.IMAGE
            AUDIO.containsMatchIn(s) -> T.AUDIO
            isEmbed(s) -> T.EMBED
            JUNK.containsMatchIn(s) -> null
            MAYBE.containsMatchIn(s) || DetKnowledge.isMediaHost(s) -> T.MAYBE
            else -> null
        }
    }

    /** Desfaz os jeitos comuns de esconder um endereço dentro de JSON/JS/HTML. */
    fun unescape(s: String): String {
        var t = s
        if (t.contains("\\u")) t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        if (t.contains("\\x")) t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        t = t.replace("\\\\/", "/").replace("\\/", "/")
        t = t.replace("&amp;", "&").replace("&#38;", "&").replace("&#x2F;", "/").replace("&#47;", "/").replace("&quot;", "\"").replace("&#34;", "\"")
        // endereço inteiro codificado: https%3A%2F%2F...
        if (t.contains("%3A%2F%2F", true)) t = Regex("""(?i)https?%3A%2F%2F[A-Za-z0-9%._\-~+]+""").replace(t) { m -> pct(m.value) }
        return t
    }

    private fun pct(s: String): String = try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    fun resolve(base: String, u: String): String? {
        val x = u.trim().trimEnd('.', ',', ';', ')', '\\')
        if (x.isEmpty()) return null
        return try {
            when {
                x.startsWith("//") -> (if (base.startsWith("http:")) "http:" else "https:") + x
                x.startsWith("http", true) -> x
                base.startsWith("http") -> java.net.URI(base).resolve(x.replace(" ", "%20")).toString()
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** Todos os endereços de mídia (e players conhecidos) achados no texto. Sem repetidos, na ordem em que aparecem. */
    fun urlsIn(text0: String, base: String = "", max: Int = 400, withImages: Boolean = true, deep: Boolean = true): List<Hit> {
        if (text0.isBlank()) return emptyList()
        val text = unescape(if (text0.length > 3_000_000) text0.substring(0, 3_000_000) else text0)
        val out = LinkedHashMap<String, T>()
        fun add(raw: String) {
            if (out.size >= max) return
            val u = resolve(base, raw) ?: return
            val clean = u.replace(Regex("""[)"'\],;]+$"""), "")
            val t = classify(clean) ?: return
            if (t == T.IMAGE && !withImages) return
            if (!out.containsKey(clean)) out[clean] = t
        }
        val absRanges = ArrayList<IntRange>()
        ABS.findAll(text).forEach { absRanges += it.range; add(it.value) }
        // relativo só fora de um endereço completo (antes "acl=x/123/master.m3u8" virava um endereço falso)
        REL_MEDIA.findAll(text).forEach { m -> val g = m.groups[1] ?: return@forEach; if (absRanges.none { g.range.first in it }) add(g.value) }
        if (deep) for (m in B64.findAll(text)) {
            if (out.size >= max) break
            val d = b64(m.value) ?: continue
            if (d.contains("http") || d.contains(".m3u8") || d.contains(".mp4")) ABS.findAll(unescape(d)).forEach { add(it.value) }
        }
        return out.map { Hit(it.key, it.value) }
    }

    private fun b64(s: String): String? = try {
        val norm = s.replace('-', '+').replace('_', '/')
        val pad = norm + "=".repeat((4 - norm.length % 4) % 4)
        val b = java.util.Base64.getDecoder().decode(pad)
        val txt = String(b, Charsets.UTF_8)
        if (txt.count { it.code in 32..126 } >= txt.length * 0.9) txt else null
    } catch (_: Exception) { null }

    // ---------------- tipo pelos primeiros bytes ----------------

    /** "hls", "dash", "mp4", "webm", "ts", "mp3", "jpeg", "png", "webp", "gif", "avif", "html", "json", "text" ou null. */
    fun sniff(head: ByteArray, contentType: String? = null): String? {
        val ct = (contentType ?: "").lowercase()
        if (head.isEmpty()) return when {
            ct.contains("mpegurl") -> "hls"; ct.contains("dash+xml") -> "dash"; ct.startsWith("video/") -> "mp4"
            ct.startsWith("image/") -> ct.substringAfter("image/").substringBefore(';').ifBlank { "img" }
            else -> null
        }
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xff else -1
        fun ascii(from: Int, n: Int) = if (from + n <= head.size) String(head, from, n, Charsets.ISO_8859_1) else ""
        val txt = String(head, 0, minOf(head.size, 2048), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            txt.startsWith("#EXTM3U") -> "hls"
            txt.startsWith("<?xml") && txt.contains("<MPD", true) || txt.startsWith("<MPD") -> "dash"
            ascii(4, 4) == "ftyp" || ascii(4, 4) == "moov" || ascii(4, 4) == "mdat" || ascii(4, 4) == "styp" -> if (ascii(8, 4).startsWith("avi")) "avif" else "mp4"
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "webm"
            at(0) == 0x47 && (head.size < 189 || at(188) == 0x47) -> "ts"
            ascii(0, 3) == "ID3" || (at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 && ct.contains("audio")) -> "mp3"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpeg"
            at(0) == 0x89 && ascii(1, 3) == "PNG" -> "png"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
            ascii(0, 3) == "GIF" -> "gif"
            ascii(0, 3) == "FLV" -> "flv"
            txt.startsWith("{") || txt.startsWith("[") -> "json"
            txt.startsWith("<", true) && (txt.contains("<html", true) || txt.contains("<!doctype", true) || txt.contains("<head", true) || txt.contains("<body", true)) -> "html"
            ct.contains("mpegurl") -> "hls"
            ct.contains("dash+xml") -> "dash"
            else -> "text"
        }
    }

    fun isVideoSniff(s: String?) = s in setOf("hls", "dash", "mp4", "webm", "ts", "flv")
    fun isImageSniff(s: String?) = s in setOf("jpeg", "png", "webp", "gif", "avif")

    // ---------------- HLS ----------------

    data class HlsInfo(val master: Boolean, val variants: Int, val maxHeight: Int, val drm: Boolean, val aes: Boolean, val audioOnly: Boolean)

    fun hlsInfo(m3u8: String): HlsInfo {
        val master = m3u8.contains("#EXT-X-STREAM-INF")
        val heights = Regex("""RESOLUTION=\d+x(\d+)""").findAll(m3u8).map { it.groupValues[1].toInt() }.toList()
        val variants = Regex("""#EXT-X-STREAM-INF""").findAll(m3u8).count()
        val keys = Regex("""#EXT-X-(?:SESSION-)?KEY:[^\n]*""").findAll(m3u8).map { it.value }.toList()
        val drm = keys.any { k -> k.contains("SAMPLE-AES", true) || k.contains("urn:uuid:edef8ba9", true) || k.contains("com.apple.streamingkeydelivery", true) || k.contains("com.widevine", true) || k.contains("skd://", true) }
        val aes = keys.any { it.contains("METHOD=AES-128", true) }
        val audioOnly = master && heights.isEmpty() && Regex("""CODECS="(?:mp4a|ac-3|ec-3|opus)[^"]*"""").containsMatchIn(m3u8) && !Regex("""CODECS="[^"]*(?:avc|hvc|hev|vp0|av01)""").containsMatchIn(m3u8)
        return HlsInfo(master, variants, heights.maxOrNull() ?: 0, drm, aes, audioOnly)
    }

    /** DASH com proteção (ContentProtection Widevine/PlayReady). */
    fun dashDrm(mpd: String): Boolean = Regex("""(?i)<ContentProtection[^>]*(?:edef8ba9|9a04f079|widevine|playready)""").containsMatchIn(mpd)
}

/**
 * Qualidade máxima: o player costuma começar numa qualidade baixa (ex.: 360p) e o app via só o link dessa qualidade.
 * Aqui saem os endereços prováveis da versão maior: a lista principal HLS (master/playlist/index… na mesma pasta ou
 * na pasta de cima) e o mesmo arquivo com 2160/1440/1080/720 no lugar do número da qualidade. Tudo é conferido antes.
 */
object QualityUp {
    val HEIGHTS = listOf(2160, 1440, 1080, 720)
    private val MASTER_NAMES = listOf("master.m3u8", "playlist.m3u8", "index.m3u8", "manifest.m3u8", "main.m3u8", "video.m3u8", "hls.m3u8", "stream.m3u8")
    private val QDIR = Regex("""(?i)^(?:\d{3,4}p?\d{0,2}|[a-z_\-]*\d{3,4}p?|hd|sd|fhd|uhd|low|mid|medium|high|mobile|v\d+|stream_?\d+|rendition_?\d+|quality_?\d+|[0-9a-f]{0,4}_?\d{3,4})$""")
    private val QTOK = Regex("""(?i)(?<=[/_\-.=])(\d{3,4})(p\d{0,2})?(?=[/_\-.?&]|$)""")
    private val NAMED = listOf("sd" to "hd", "low" to "high", "lq" to "hq", "mobile" to "hd", "small" to "large", "medium" to "high")

    private fun split(u: String): Pair<String, String> { val b = u.substringBefore('#'); return b.substringBefore('?') to (if ('?' in b) "?" + b.substringAfter('?') else "") }

    /** Alturas no endereço que parecem qualidade (144…1080). */
    private val COMMON = setOf(144, 180, 234, 240, 270, 288, 360, 384, 404, 406, 432, 480, 540, 544, 576, 640, 720, 768, 960, 1024, 1080, 1152, 1280, 1440, 2160)
    fun heightIn(u: String): Int = QTOK.findAll(split(u).first.substringAfter("://").substringAfter('/')).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in COMMON }.maxOrNull() ?: 0

    fun hlsCandidates(u: String): List<String> {
        val (base, q) = split(u)
        val out = LinkedHashSet<String>()
        val dir = base.substringBeforeLast('/')
        val last = dir.substringAfterLast('/')
        // 1º: lista principal na pasta de cima quando a pasta é a da qualidade (…/ID/360p/video.m3u8 → …/ID/playlist.m3u8)
        if (QDIR.matches(last)) { val parent = dir.substringBeforeLast('/'); for (n in MASTER_NAMES) out += "$parent/$n$q"; out += "$parent.m3u8$q" }
        // 2º: o mesmo endereço com a qualidade maior
        out += sameWithHeight(u)
        // 3º: arquivo com a qualidade no nome: video_360p.m3u8 → video.m3u8
        val file = base.substringAfterLast('/')
        val noQ = file.replace(Regex("""(?i)[_\-.](\d{3,4}p?|sd|hd|low|high)(?=\.m3u8$)"""), "")
        if (noQ != file) out += "$dir/$noQ$q"
        // 4º: lista principal na mesma pasta
        for (n in MASTER_NAMES) out += "$dir/$n$q"
        out.remove(u); out.remove(base + q)
        return out.toList().take(24)
    }

    /** O mesmo endereço trocando o número da qualidade (e sd→hd, low→high…). Maior primeiro. */
    fun sameWithHeight(u: String): List<String> {
        val (base, q) = split(u)
        val host = base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/')
        val path = base.substringAfter("://").substringAfter('/', "")
        val cur = heightIn(u)
        val out = ArrayList<String>()
        if (cur in 144..1440) for (h in HEIGHTS) if (h > cur) {
            val p2 = QTOK.replace(path) { m -> val n = m.groupValues[1].toInt(); if (n == cur) h.toString() + m.groupValues[2] else m.value }
            if (p2 != path) out += "$host/$p2$q"
        }
        for ((a, b) in NAMED) {
            val rx = Regex("""(?i)(?<=[/_\-.])$a(?=[/_\-.])""")
            if (rx.containsMatchIn(path)) out += "$host/" + rx.replace(path, b) + q
        }
        return out.distinct()
    }
}
