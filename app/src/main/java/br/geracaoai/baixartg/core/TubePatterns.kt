package br.geracaoai.baixartg.core

/**
 * Padrões técnicos de players de sites de vídeo (sem nada específico de um site):
 *
 * - KVS (Kernel Video Sharing, usado por milhares de sites): `video_url: 'function/0/https://…/get_file/…'` vem
 *   embaralhado; o hash do caminho é reordenado com o `license_code` da página. Antes o app só cortava o prefixo
 *   e ficava com o link ainda embaralhado (erro 403/404).
 * - Players que montam o link juntando pedaços de texto em variáveis (`var a="htt"; var b="ps://…"; media_0=a+b;`).
 * - Chamadas de player com o link como argumento (`setVideoUrlHigh('…')`, `setVideoHLS('…')`, `.src('…')`, `file:'…'`).
 * - Listas de qualidades em JSON (`mediaDefinitions`, `sources`, `videoUrl`, `"720p":["…"]`).
 *
 * Só lê texto; não faz rede. Devolve links absolutos (sem filtro de anúncio/prévia: quem chama decide).
 */
object TubePatterns {

    fun extract(pageUrl: String, html: String): List<String> {
        val out = LinkedHashSet<String>()
        val text = unescape(html)
        try { out += kvs(text) } catch (_: Exception) {}
        try { out += concatenated(text) } catch (_: Exception) {}
        try { out += calls(text) } catch (_: Exception) {}
        try { out += jsonLists(text) } catch (_: Exception) {}
        return out.mapNotNull { abs(pageUrl, it) }.filter { isMedia(it) }.distinct()
    }

    // ---------- KVS ----------
    private val KVS_VAR = Regex("""(video_(?:alt_)?url\d*)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val KVS_LICENSE = Regex("""license_code\s*[:=]\s*['"]\$?(\d{6,})['"]""", RegexOption.IGNORE_CASE)

    fun kvs(text: String): List<String> {
        val lic = KVS_LICENSE.find(text)?.groupValues?.get(1)
        val res = ArrayList<String>()
        for (m in KVS_VAR.findAll(text)) {
            val raw = m.groupValues[2].trim()
            if (raw.isBlank() || raw.startsWith("{") ) continue
            val u = if (raw.startsWith("function/", true)) {
                if (lic == null) continue
                kvsRealUrl(raw, lic) ?: continue
            } else raw
            res += u
        }
        return res
    }

    /** Token do license_code (mesmo cálculo do player KVS). */
    fun kvsLicenseToken(license: String): IntArray {
        val lic = license.replace("$", "")
        val values = lic.map { it - '0' }
        val mod = lic.replace('0', '1')
        val center = mod.length / 2
        val front = mod.substring(0, center + 1).toBigInteger()
        val back = mod.substring(center).toBigInteger()
        val diff = (front - back).abs().multiply(4.toBigInteger()).toString()
        val modLicense = diff.substring(0, minOf(diff.length, center + 1))
        val out = ArrayList<Int>()
        for ((index, ch) in modLicense.withIndex()) {
            val current = ch - '0'
            for (offset in 0 until 4) out += (values[index + offset] + current) % 10
        }
        return out.toIntArray()
    }

    /** `function/0/https://host/get_file/1/<hash32…>/…` → link real (hash reordenado). */
    fun kvsRealUrl(videoUrl: String, license: String): String? {
        val m = Regex("""^function/\d+/(.+)$""", RegexOption.IGNORE_CASE).find(videoUrl) ?: return videoUrl
        val url = m.groupValues[1]
        val schemeEnd = url.indexOf("://"); if (schemeEnd < 0) return null
        val pathStart = url.indexOf('/', schemeEnd + 3); if (pathStart < 0) return null
        val qIdx = url.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) url.length else it }
        val head = url.substring(0, pathStart)
        val parts = url.substring(pathStart, qIdx).split('/').toMutableList()
        val tail = url.substring(qIdx)
        if (parts.size < 4) return null
        val token = kvsLicenseToken(license)
        val hashLen = 32
        val h = parts[3]
        if (h.length < hashLen || token.size < hashLen) return null
        val hash = h.substring(0, hashLen)
        val idx = IntArray(hashLen) { it }
        var accum = 0
        for (src in hashLen - 1 downTo 0) {
            accum += token[src]
            val dest = (src + accum) % hashLen
            val t = idx[src]; idx[src] = idx[dest]; idx[dest] = t
        }
        parts[3] = buildString { for (i in idx) append(hash[i]) } + h.substring(hashLen)
        return head + parts.joinToString("/") + tail
    }

    // ---------- link montado com pedaços de texto ----------
    private val COMMENTS = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val ASSIGN = Regex("""(?:var|let|const)?\s*([A-Za-z_$][\w$]*)\s*=\s*((?:"[^"\n]*"|'[^'\n]*'|[A-Za-z_$][\w$]*)(?:\s*\+\s*(?:"[^"\n]*"|'[^'\n]*'|[A-Za-z_$][\w$]*))*)\s*;""")
    private val TERM = Regex(""""([^"\n]*)"|'([^'\n]*)'|([A-Za-z_$][\w$]*)""")

    fun concatenated(text: String): List<String> {
        if (!text.contains('+')) return emptyList()
        val src = COMMENTS.replace(text, "")
        val vars = HashMap<String, String>()
        val res = ArrayList<String>()
        for (m in ASSIGN.findAll(src)) {
            val name = m.groupValues[1]
            val expr = m.groupValues[2]
            val sb = StringBuilder()
            var ok = true
            for (t in TERM.findAll(expr)) {
                when {
                    t.groups[1] != null -> sb.append(t.groupValues[1])
                    t.groups[2] != null -> sb.append(t.groupValues[2])
                    else -> { val v = vars[t.groupValues[3]]; if (v == null) { ok = false; break } else sb.append(v) }
                }
            }
            if (!ok) continue
            val v = sb.toString()
            if (v.length > 4000) continue
            vars[name] = v
            if (expr.contains('+') && v.startsWith("http", true) && isMedia(v)) res += v
        }
        return res
    }

    // ---------- chamadas de player ----------
    private val CALLS = Regex(
        """(?:setVideoUrl(?:High|Low)?|setVideoHLS|setVideoUrlHD|\.src|loadSource|setSrc|playVideo)\s*\(\s*['"]([^'"]+)['"]""" +
        """|["']?(?:file|src|source|videoUrl|video_url|hls|hlsUrl|mp4|stream|streamUrl|contentUrl|url)["']?\s*:\s*["']([^"']+\.(?:m3u8|mp4|webm|mpd)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE)

    fun calls(text: String): List<String> = CALLS.findAll(text).mapNotNull { m ->
        (m.groups[1]?.value ?: m.groups[2]?.value)?.trim()?.takeIf { it.isNotBlank() }
    }.toList()

    // ---------- listas de qualidades em JSON ----------
    private val QUALITY_LIST = Regex("""["'](?:\d{3,4}p?|hd|sd|high|low|full ?hd|4k)["']\s*:\s*\[\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val VIDEO_URL_KEY = Regex(""""videoUrl"\s*:\s*"([^"]+)"""")

    fun jsonLists(text: String): List<String> =
        (QUALITY_LIST.findAll(text).map { it.groupValues[1] } + VIDEO_URL_KEY.findAll(text).map { it.groupValues[1] }).toList()

    // ---------- util ----------
    private val MEDIA = Regex("""(?i)\.(mp4|m3u8|webm|mpd|mov|m4v)(?:$|[?#/&])|/get_file/|/get_video|/videoplayback""")
    fun isMedia(u: String) = MEDIA.containsMatchIn(u)

    fun unescape(s: String): String =
        s.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("\\u0026", "&").replace("\\u003D", "=")
            .replace("\\u003d", "=").replace("&amp;", "&")

    private fun abs(base: String, u: String): String? {
        val t = u.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (t.startsWith("//")) return "https:$t"
        if (t.startsWith("/")) return try { java.net.URI(base).resolve(t).toString() } catch (_: Exception) { null }
        return null
    }
}
