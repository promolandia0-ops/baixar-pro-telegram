package br.geracaoai.baixartg.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * "qt-faststart" em Kotlin puro: move o átomo moov para antes do mdat (sem recodificar),
 * corrigindo os offsets stco/co64. Assim o Telegram consegue tocar em streaming.
 * Retorna true se reescreveu, false se já estava ok ou não deu.
 */
object Faststart {
    private class Box(val type: String, val start: Long, val size: Long)

    private fun boxes(raf: RandomAccessFile): List<Box>? {
        val out = ArrayList<Box>(); var pos = 0L; val len = raf.length(); val h = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos); raf.readFully(h, 0, 8)
            var size = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) { raf.readFully(h, 8, 8); size = ByteBuffer.wrap(h, 8, 8).long }
            else if (size == 0L) size = len - pos
            if (size < 8 || pos + size > len) return null
            out += Box(type, pos, size); pos += size
        }
        return out
    }

    fun process(src: File, dst: File): Boolean {
        RandomAccessFile(src, "r").use { raf ->
            val bs = boxes(raf) ?: return false
            val moov = bs.firstOrNull { it.type == "moov" } ?: return false
            val firstMdat = bs.indexOfFirst { it.type == "mdat" }
            if (firstMdat < 0 || bs.indexOf(moov) < firstMdat) return false
            if (moov.size > Int.MAX_VALUE) return false
            val m = ByteArray(moov.size.toInt()); raf.seek(moov.start); raf.readFully(m)
            if (!patch(ByteBuffer.wrap(m), 8, m.size, moov.size)) return false
            dst.outputStream().buffered(1 shl 20).use { os ->
                val buf = ByteArray(1 shl 20)
                fun copy(b: Box) { raf.seek(b.start); var left = b.size; while (left > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; os.write(buf, 0, n); left -= n } }
                val before = bs.subList(0, firstMdat).filter { it.type != "moov" }
                before.forEach(::copy)
                os.write(m)
                bs.subList(firstMdat, bs.size).filter { it !== moov }.forEach(::copy)
            }
            return true
        }
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex")

    /** Soma `delta` a cada offset de stco/co64 dentro do moov. Falha se estourar 32 bits em stco. */
    private fun patch(b: ByteBuffer, from: Int, to: Int, delta: Long): Boolean {
        var p = from
        while (p + 8 <= to) {
            val size = b.getInt(p).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.ISO_8859_1)
            if (size < 8 || p + size > to) return false
            when (type) {
                in CONTAINERS -> if (!patch(b, p + 8, (p + size).toInt(), delta)) return false
                "stco" -> { val n = b.getInt(p + 12); for (i in 0 until n) { val q = p + 16 + i * 4; val v = (b.getInt(q).toLong() and 0xffffffffL) + delta; if (v > 0xffffffffL) return false; b.putInt(q, v.toInt()) } }
                "co64" -> { val n = b.getInt(p + 12); for (i in 0 until n) { val q = p + 16 + i * 8; b.putLong(q, b.getLong(q) + delta) } }
            }
            p += size.toInt()
        }
        return true
    }
}
