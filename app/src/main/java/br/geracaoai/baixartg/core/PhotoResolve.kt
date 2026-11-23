package br.geracaoai.baixartg.core

import org.jsoup.Jsoup
import kotlin.math.abs

/**
 * Resolve e baixa a foto completa (não a miniatura quadrada da grade), seguindo o link do card
 * (<a href> que envolve a <img>) e tentando reescritas de URL, comparando dimensões reais pelos bytes.
 * Roda dentro do mesmo slot do item (sem camada separada em segundo plano).
 */
object PhotoResolve {
    data class LoadedPhoto(
        val url: String,
        val bytes: ByteArray,
        val ext: String,
        val w: Int,
        val h: Int,
    )

    private val BAD_URL = Regex(
        """(?i)(?:sprites?|favicons?|emojis?|spacer|blank|pixel|1x1|beacon|tracking|loading|loader|spinner|placeholder|avatar|logo|badge|\.svg(?:[?#]|$)|doubleclick|googlesyndication|adservice)"""
    )
    private val BAD_CLS = Regex("""(?i)(?:^|[\s_-])(?:avatar|logo|sprite|emoji|icon|favicon|badge|ad|ads|advert|sponsor)(?:[\s_-]|$)""")

    fun isSmallSquare(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val mx = maxOf(w, h)
        return mx <= 800 && abs(w - h) <= mx * 0.05
    }

    /** Extrai dimensões reais direto dos bytes (JPEG/PNG/WebP/GIF, compatível com JVM e Android). */
    fun dimensionsOf(b: ByteArray): Pair<Int, Int> {
        if (b.size < 10) return 0 to 0
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24le(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
        fun ascii(o: Int, n: Int) = if (o + n <= b.size) String(CharArray(n) { (b[o + it].toInt() and 0xFF).toChar() }) else ""

        // PNG
        if (b.size >= 24 && u8(0) == 0x89 && ascii(1, 3) == "PNG") {
            val w = (u16be(16) shl 16) or u16be(18)
            val h = (u16be(20) shl 16) or u16be(22)
            if (w > 0 && h > 0) return w to h
        }
        // GIF
        if (b.size >= 10 && (ascii(0, 6) == "GIF87a" || ascii(0, 6) == "GIF89a")) {
            val w = u16le(6); val h = u16le(8)
            if (w > 0 && h > 0) return w to h
        }
        // WebP
        if (b.size >= 30 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
            when (ascii(12, 4)) {
                "VP8 " -> {
                    val w = u16le(26) and 0x3FFF
                    val h = u16le(28) and 0x3FFF
                    if (w > 0 && h > 0) return w to h
                }
                "VP8L" -> {
                    val bits = u16le(21) or (u16le(23) shl 16)
                    val w = (bits and 0x3FFF) + 1
                    val h = ((bits shr 14) and 0x3FFF) + 1
                    if (w > 0 && h > 0) return w to h
                }
                "VP8X" -> {
                    val w = u24le(24) + 1
                    val h = u24le(27) + 1
                    if (w > 0 && h > 0) return w to h
                }
            }
        }
        // JPEG
        if (b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8) {
            var pos = 2
            while (pos + 4 <= b.size) {
                if (u8(pos) != 0xFF) { pos++; continue }
                while (pos < b.size && u8(pos) == 0xFF) pos++
                if (pos >= b.size) break
                val marker = u8(pos++)
                if (marker == 0xD9 || marker == 0xDA) break
                if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD7) continue
                if (pos + 2 > b.size) break
                val len = u16be(pos)
                if (len < 2 || pos + len > b.size) break
                val isSof = marker in 0xC0..0xC3 || marker in 0xC5..0xC7 || marker in 0xC9..0xCB || marker in 0xCD..0xCF
                if (isSof && pos + 7 <= b.size) {
                    val h = u16be(pos + 3)
                    val w = u16be(pos + 5)
                    if (w > 0 && h > 0) return w to h
                }
                pos += len
            }
        }
        // Fallback Android (ex.: HEIC/AVIF/BMP em runtime)
        try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth to opts.outHeight
        } catch (_: Throwable) {}
        return 0 to 0
    }

    /**
     * Extrai candidatas de foto em ordem de prioridade a partir da página/visualizador aberta pelo card.
     */
    fun extractViewerCandidates(pageUrl: String, html: String): List<String> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val high = LinkedHashSet<String>()
        val mid = LinkedHashSet<String>()
        val low = LinkedHashSet<String>()

        fun addUrl(target: LinkedHashSet<String>, u: String) {
            val clean = u.trim()
            if (!clean.startsWith("http", ignoreCase = true) || BAD_URL.containsMatchIn(clean)) return
            PhotoGrab.upgradeVariants(clean).forEach { target += it }
            target += clean
        }

        // 1) Links diretos para imagem (.jpg/.png/.webp/.avif) no visualizador
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (href.startsWith("http", true) && Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href)) {
                addUrl(high, href)
            }
        }

        // 2) Atributos de alta resolução nas <img> (ignorando ícones/avatares/capas de vídeo)
        for (img in doc.select("img")) {
            if (img.parents().any { it.tagName() == "video" }) continue
            if (BAD_CLS.containsMatchIn(img.className() + " " + img.id())) continue
            val wAttr = img.attr("width").toIntOrNull() ?: 0
            val hAttr = img.attr("height").toIntOrNull() ?: 0
            if (wAttr in 1 until 150 && hAttr in 1 until 150) continue

            val hiAttrs = listOf(
                "data-full", "data-full-url", "data-orig-file", "data-original",
                "data-large", "data-large-file", "data-zoom-image", "data-zoom-src",
                "data-hi-res-src", "data-highres"
            )
            for (attr in hiAttrs) {
                val v = img.absUrl(attr).ifBlank { img.attr(attr) }
                if (v.startsWith("http", true)) addUrl(high, v)
            }
            val ss = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            if (ss.isNotBlank()) {
                val bestSs = bestFromSrcset(pageUrl, ss)
                if (bestSs.isNotBlank()) addUrl(high, bestSs)
            }
            for (attr in listOf("data-src", "data-lazy-src", "data-url")) {
                val v = img.absUrl(attr).ifBlank { img.attr(attr) }
                if (v.startsWith("http", true)) addUrl(mid, v)
            }
            val src = img.absUrl("src").ifBlank { img.attr("src") }
            if (src.startsWith("http", true)) {
                if (wAttr >= 500 || hAttr >= 500) addUrl(high, src) else addUrl(low, src)
            }
        }

        // 3) og:image / twitter:image / image_src (quando não é página exclusiva de vídeo)
        val hasVideoMeta = doc.selectFirst("meta[property=og:video],meta[property=og:video:url],video") != null
        if (!hasVideoMeta) {
            for (sel in listOf(
                "meta[property=og:image]" to "content",
                "meta[name=og:image]" to "content",
                "meta[property=twitter:image]" to "content",
                "meta[name=twitter:image]" to "content",
                "link[rel=image_src]" to "href"
            )) {
                val u = doc.selectFirst(sel.first)?.absUrl(sel.second).orEmpty()
                if (u.startsWith("http", true)) addUrl(mid, u)
            }
        }

        return (high + mid + low).toList()
    }

    private fun bestFromSrcset(baseUrl: String, ss: String): String {
        var best = ""
        var bestW = -1
        for (part in ss.split(',')) {
            val tokens = part.trim().split(Regex("\\s+"))
            if (tokens.isEmpty() || tokens[0].isBlank()) continue
            val desc = tokens.getOrNull(1) ?: "1x"
            val score = if (desc.endsWith("w", true)) desc.dropLast(1).toIntOrNull() ?: 0
            else ((desc.removeSuffix("x").removeSuffix("X").toFloatOrNull() ?: 1f) * 1000).toInt()
            if (score >= bestW) {
                bestW = score
                best = try { java.net.URI(baseUrl).resolve(tokens[0]).toString() } catch (_: Exception) { tokens[0] }
            }
        }
        return best
    }

    /**
     * Escolhe a melhor imagem baixada comparando dimensões reais pelos bytes.
     * Rejeita miniaturas quadradas pequenas quando houver um candidato maior (ou de outra proporção).
     */
    fun pickBest(cands: List<LoadedPhoto>): LoadedPhoto? {
        if (cands.isEmpty()) return null
        val nonTiny = cands.filter { maxOf(it.w, it.h) == 0 || maxOf(it.w, it.h) >= 150 }.ifEmpty { cands }
        val nonSmallSquare = nonTiny.filter { !isSmallSquare(it.w, it.h) }
        val pool = if (nonSmallSquare.isNotEmpty()) {
            // mantém só os não-quadrados-pequenos se algum deles tiver área real >= ou comparável
            val bestFull = nonSmallSquare.maxByOrNull { it.w.toLong() * it.h.toLong() }
            if (bestFull != null && bestFull.w > 0 && bestFull.h > 0) nonSmallSquare else nonTiny
        } else nonTiny
        return pool.maxWithOrNull(compareBy<LoadedPhoto>({ it.w.toLong() * it.h.toLong() }, { it.bytes.size }))
    }

    /**
     * Fluxo completo dentro do próprio slot da foto:
     * 1) Se o card tinha um link (<a href>), abre a página/visualizador e extrai as imagens de lá;
     * 2) Também testa reescritas de URL de miniatura (crop/thumb/_s/resize);
     * 3) Compara dimensões reais por bytes e devolve a maior imagem inteira (só usa a miniatura se nada maior existir).
     */
    suspend fun resolveAndFetch(
        url: String,
        pageUrl: String,
        referer: String?,
        fetchHtml: suspend (String, String?) -> String?,
        fetchBytes: suspend (String, String?) -> ByteArray?,
    ): LoadedPhoto? {
        val orderedUrls = LinkedHashSet<String>()
        val refForPage = referer ?: pageUrl
        val hasViewerLink = pageUrl.isNotBlank() &&
            pageUrl.substringBefore('#') != (referer ?: "").substringBefore('#') &&
            !Extract.isPhotoUrl(pageUrl)

        if (hasViewerLink) {
            val html = try { fetchHtml(pageUrl, refForPage) } catch (_: Exception) { null }
            if (!html.isNullOrBlank()) {
                extractViewerCandidates(pageUrl, html).forEach { orderedUrls += it }
            }
        }
        PhotoGrab.upgradeVariants(url).forEach { orderedUrls += it }
        orderedUrls += url

        val loaded = ArrayList<LoadedPhoto>()
        val seen = HashSet<String>()
        var attempts = 0
        for (candUrl in orderedUrls) {
            if (attempts >= 8) break
            if (!seen.add(candUrl)) continue
            attempts++
            val candRef = if (hasViewerLink && candUrl != url) pageUrl else (referer ?: pageUrl)
            val bytes = try { fetchBytes(candUrl, candRef) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: continue
            val ext = PhotoGrab.imageExt(bytes) ?: continue
            val (w, h) = dimensionsOf(bytes)
            val lp = LoadedPhoto(candUrl, bytes, ext, w, h)
            loaded += lp
            // já achou imagem grande não-quadrada direto da página/original: não precisa gastar mais requests
            if (candUrl != url && !isSmallSquare(w, h) && maxOf(w, h) >= 1200) break
        }
        return pickBest(loaded)
    }
}
