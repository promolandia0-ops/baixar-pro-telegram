package br.geracaoai.baixartg.core

import java.io.File

/**
 * Qualidade e repetidos pelo PRÓPRIO arquivo baixado (antes de enviar ao Telegram):
 *  - resolução real (lê só as informações do arquivo, sem decodificar o vídeo — não briga com o navegador);
 *  - repetido pela duração: mesmo site + mesma duração (±0,2 s) de um vídeo já enviado = o mesmo vídeo, mesmo que o
 *    link, a qualidade ou o nome sejam outros. Funciona com o navegador aberto.
 */
object VideoQuality {
    data class Meta(val durMs: Long, val w: Int, val h: Int) { val short get() = if (w > 0 && h > 0) minOf(w, h) else 0 }

    fun read(f: File): Meta? = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            fun n(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            Meta(n(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION),
                n(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
                n(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt())
        } finally { try { r.release() } catch (_: Exception) {} }
    } catch (_: Throwable) { null }

    // ---------------- repetidos pela duração ----------------
    private var file: File? = null
    private data class Rec(val host: String, val dur: Long, val title: String)
    private val recs = ArrayList<Rec>()

    fun init(dir: File) {
        file = File(dir, "duracoes.txt")
        try { file?.takeIf { it.exists() }?.readLines()?.forEach { l -> val p = l.split('\t'); if (p.size >= 3) p[1].toLongOrNull()?.let { synchronized(recs) { recs += Rec(p[0], it, p[2]) } } } } catch (_: Exception) {}
    }

    fun repeatOf(host: String, durMs: Long): String? {
        if (host.isBlank() || durMs < 5_000) return null
        return synchronized(recs) { recs.lastOrNull { it.host == host && kotlin.math.abs(it.dur - durMs) <= 200 }?.title?.ifBlank { "vídeo já enviado" } }
    }

    fun markSent(host: String, durMs: Long, title: String) {
        if (host.isBlank() || durMs < 5_000) return
        val t = title.replace('\t', ' ').replace('\n', ' ').take(120)
        synchronized(recs) { recs += Rec(host, durMs, t) }
        try { file?.appendText("$host\t$durMs\t$t\n") } catch (_: Exception) {}
    }

    fun clear() { synchronized(recs) { recs.clear() }; try { file?.delete() } catch (_: Exception) {} }
}
