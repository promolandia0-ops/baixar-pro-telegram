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
    // Antes: só mesmo site + mesma duração já contava como repetido. Em site onde muitos vídeos têm a mesma duração
    // (reels de 15 s, cortes de 30 s…) vídeos DIFERENTES eram pulados depois de baixar e "sumiam" da fila.
    // Agora precisa bater também o tamanho do arquivo (±1,5 %) e a resolução — ou o mesmo título.
    private var file: File? = null
    private data class Rec(val host: String, val dur: Long, val title: String, val size: Long = -1, val w: Int = 0, val h: Int = 0)
    private val recs = ArrayList<Rec>()

    fun init(dir: File) {
        file = File(dir, "duracoes.txt")
        try { file?.takeIf { it.exists() }?.readLines()?.forEach { l -> val p = l.split('\t'); if (p.size >= 3) p[1].toLongOrNull()?.let {
            synchronized(recs) { recs += Rec(p[0], it, p[2], p.getOrNull(3)?.toLongOrNull() ?: -1, p.getOrNull(4)?.toIntOrNull() ?: 0, p.getOrNull(5)?.toIntOrNull() ?: 0) } } } } catch (_: Exception) {}
    }

    private fun mesmoTitulo(a: String, b: String): Boolean {
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        return x.length >= 8 && x == y && !TitleFix.isJunk(a)
    }

    fun repeatOf(host: String, durMs: Long, size: Long = -1, w: Int = 0, h: Int = 0, title: String = ""): String? {
        if (host.isBlank() || durMs < 5_000) return null
        return synchronized(recs) {
            recs.lastOrNull { r ->
                r.host == host && kotlin.math.abs(r.dur - durMs) <= 200 && (
                    (r.size > 0 && size > 0 && kotlin.math.abs(r.size - size) <= maxOf(r.size, size) * 0.015 && (r.w == 0 || w == 0 || (r.w == w && r.h == h)))
                        || mesmoTitulo(r.title, title))
            }?.title?.ifBlank { "vídeo já enviado" }
        }
    }

    fun markSent(host: String, durMs: Long, title: String, size: Long = -1, w: Int = 0, h: Int = 0) {
        if (host.isBlank() || durMs < 5_000) return
        val t = title.replace('\t', ' ').replace('\n', ' ').take(120)
        synchronized(recs) { recs += Rec(host, durMs, t, size, w, h) }
        try { file?.appendText("$host\t$durMs\t$t\t$size\t$w\t$h\n") } catch (_: Exception) {}
    }

    fun clear() { synchronized(recs) { recs.clear() }; try { file?.delete() } catch (_: Exception) {} }
}
