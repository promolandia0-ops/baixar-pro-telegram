package br.geracaoai.baixartg.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Qualidade máxima de verdade: recebe o link que o player pediu (muitas vezes 360p, porque o player começa baixo) e
 * procura a lista principal HLS (com todas as qualidades) ou o mesmo arquivo em 1080/720…; só aceita depois de baixar o
 * começo e conferir. Usa os cookies do login e a mesma origem (Referer) do link original.
 */
object QualityBoost {
    private suspend fun fetch(url: String, ref: String?, max: Int = 512_000, headOnly: Boolean = false): Pair<Int, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url).header("Accept", "*/*")
            if (headOnly) b.header("Range", "bytes=0-4095")
            if (ref != null && ref != url) { b.header("Referer", ref); Net.originOf(ref)?.let { b.header("Origin", it.trimEnd('/')) } }
            Net.fast.newCall(b.build()).await().use { r ->
                val out = java.io.ByteArrayOutputStream()
                if (r.isSuccessful) r.body?.byteStream()?.use { ins -> val buf = ByteArray(16_384); while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) } }
                r.code to out.toByteArray()
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
    }

    private fun noQ(u: String) = u.substringBefore('#').substringBefore('?')

    /** Lista principal (master) de um link de qualidade única. null se já é a principal ou não achou. */
    suspend fun masterFor(url: String, ref: String?, playlist: String? = null, seen: List<String> = emptyList()): Pair<String, Int>? {
        val pl = playlist ?: fetch(url, ref)?.takeIf { it.first in 200..299 }?.second?.let { String(it, Charsets.UTF_8) } ?: return null
        if (!pl.trimStart().startsWith("#EXTM3U") || pl.contains("#EXT-X-STREAM-INF")) return null
        val cur = QualityUp.heightIn(url)
        // 1º as listas que o próprio player pediu nesta página (a principal ou a de 1080 costuma estar ali); depois os palpites
        val seenFirst = seen.filter { Trail.host(it) == Trail.host(url) } + seen.filter { Trail.host(it) != Trail.host(url) }
        for (c in (seenFirst + QualityUp.hlsCandidates(url)).distinct().take(40)) {
            val (code, b) = fetch(c, ref) ?: continue
            if (code !in 200..299) continue
            val t = String(b, Charsets.UTF_8)
            if (!t.trimStart().startsWith("#EXTM3U")) continue
            if (t.contains("#EXT-X-STREAM-INF")) {
                val info = DetScan.hlsInfo(t)
                if (info.drm || info.audioOnly) continue
                val lines = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { DetScan.resolve(c, it) }.map { noQ(it) }.toList()
                val belongs = noQ(url) in lines
                if (belongs || info.maxHeight > cur) return c to info.maxHeight
            } else if (t.contains("#EXTINF") && QualityUp.heightIn(c) > cur && (c in QualityUp.sameWithHeight(url) || (c in seen && cur > 0))) return c to QualityUp.heightIn(c)
        }
        return null
    }

    /** Versão maior do mesmo vídeo (HLS principal ou arquivo em resolução maior), conferida. */
    suspend fun better(f: Found, seen: List<String> = emptyList()): Found? {
        val ref = f.ref ?: f.page.ifBlank { null }
        if (f.kind == Kind.HLS || Extract.isHls(f.url)) {
            val (u, h) = masterFor(f.url, ref, null, seen) ?: return null
            return f.copy(url = u, kind = Kind.HLS, quality = h)
        }
        if (f.kind == Kind.DIRECT) {
            val cur = QualityUp.heightIn(f.url)
            for (c in QualityUp.sameWithHeight(f.url)) {
                val (code, b) = fetch(c, ref, 4096, headOnly = true) ?: continue
                if (code in 200..299 && DetScan.isVideoSniff(DetScan.sniff(b))) return f.copy(url = c, quality = QualityUp.heightIn(c).takeIf { it > cur } ?: f.quality)
            }
        }
        return null
    }
}
