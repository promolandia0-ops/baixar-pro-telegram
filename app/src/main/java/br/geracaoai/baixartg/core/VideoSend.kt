package br.geracaoai.baixartg.core

import java.io.File

/**
 * Decide como enviar o vídeo no Telegram: sempre como VÍDEO (sendVideo), nunca como arquivo.
 * .ts/.mkv/.webm e HLS remontado viram MP4 (faststart) sem recodificar quando dá.
 */
object VideoSend {
    enum class Mode { VIDEO_AS_IS, REMUX_MP4 }

    data class Plan(val mode: Mode, val container: String)

    fun container(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "unknown"
        fun at(i: Int) = if (i in bytes.indices) bytes[i].toInt() and 0xFF else -1
        fun ascii(o: Int, n: Int): String {
            if (o + n > bytes.size) return ""
            return String(bytes, o, n, Charsets.ISO_8859_1)
        }
        if (ascii(4, 4) == "ftyp") {
            val brand = ascii(8, 4).lowercase()
            return when {
                brand.startsWith("qt") -> "mov"
                brand.startsWith("3g") -> "3gp"
                else -> "mp4"
            }
        }
        if (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3) {
            val s = ascii(0, minOf(64, bytes.size))
            return if (s.contains("webm", true)) "webm" else "mkv"
        }
        if (ascii(0, 3) == "FLV") return "flv"
        if (ascii(0, 4) == "RIFF" && ascii(8, 4) == "AVI ") return "avi"
        // MPEG-TS: 0x47 a cada 188 bytes
        if (at(0) == 0x47 && (bytes.size < 188 || at(188) == 0x47 || at(188) == -1)) return "ts"
        val head = ascii(0, minOf(16, bytes.size)).lowercase().trimStart()
        if (head.startsWith("#extm3u")) return "hls"
        val extGuess = ""
        return extGuess.ifBlank { "unknown" }
    }

    fun containerOf(f: File): String {
        val buf = ByteArray(1024)
        val n = f.inputStream().use { it.read(buf) }
        if (n <= 0) return f.extension.lowercase().ifBlank { "unknown" }
        val c = container(if (n == buf.size) buf else buf.copyOf(n))
        return if (c != "unknown") c else f.extension.lowercase().ifBlank { "unknown" }
    }

    fun plan(container: String, ext: String = ""): Plan {
        val c = container.lowercase().ifBlank { ext.lowercase() }
        val asIs = c in setOf("mp4", "mov", "m4v", "3gp")
        return if (asIs) Plan(Mode.VIDEO_AS_IS, c.ifBlank { "mp4" }) else Plan(Mode.REMUX_MP4, c)
    }

    fun planFor(f: File): Plan = plan(containerOf(f), f.extension)

    /** Remuxa para MP4+faststart se o container não for aceito como vídeo nativo. Falhou: devolve o original. */
    fun prepare(src: File): File {
        val p = planFor(src)
        if (p.mode == Mode.VIDEO_AS_IS) {
            val fst = File(src.parentFile, src.nameWithoutExtension + "_fs.mp4")
            return try {
                if (src.extension.lowercase() in setOf("mp4", "m4v", "mov") && Faststart.process(src, fst) && fst.length() > 1024) fst
                else { fst.delete(); src }
            } catch (_: Exception) { fst.delete(); src }
        }
        val tmp = File(src.parentFile, src.nameWithoutExtension + ".remux-tmp.mp4")
        val out = File(src.parentFile, src.nameWithoutExtension + "_tg.mp4")
        return try {
            Remux.toMp4(listOf(src), tmp)
            if (tmp.length() < 1024) throw IllegalStateException("remux vazio")
            if (Faststart.process(tmp, out)) tmp.delete() else tmp.renameTo(out)
            if (out.exists() && out.length() > 1024) out else src
        } catch (_: Exception) {
            tmp.delete(); if (!out.exists() || out.length() < 1024) src else out
        }
    }
}
