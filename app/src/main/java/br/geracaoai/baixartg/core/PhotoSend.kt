package br.geracaoai.baixartg.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.File
import java.io.FileOutputStream

/**
 * Prepara fotos para o Telegram como IMAGEM (sendPhoto), nunca figurinha nem arquivo.
 * WebP/AVIF/HEIC/GIF estático/PNG com alfa viram JPEG q=95 (fundo branco).
 */
object PhotoSend {
    const val MAX_BYTES = 10L * 1024 * 1024
    const val MAX_SUM = 10_000
    const val MAX_RATIO = 20.0
    const val JPEG_Q = 95

    enum class Mode { PHOTO_AS_IS, CONVERT_JPEG, ANIMATION }

    data class Plan(val mode: Mode, val outW: Int = 0, val outH: Int = 0, val pad: Boolean = false)
    data class Prepared(val file: File, val mode: Mode, val w: Int, val h: Int, val temp: Boolean)

    private fun ascii(b: ByteArray, o: Int, n: Int): String {
        if (o < 0 || n < 0 || o + n > b.size) return ""
        return String(b, o, n, Charsets.ISO_8859_1)
    }

    fun sniffHead(f: File, n: Int = 256 * 1024): ByteArray = f.inputStream().use {
        val cap = n.coerceAtMost(f.length().toInt().coerceAtLeast(16))
        val b = ByteArray(cap)
        val r = it.read(b)
        if (r <= 0) ByteArray(0) else if (r == b.size) b else b.copyOf(r)
    }

    fun pngHasAlpha(b: ByteArray): Boolean {
        if (b.size < 26) return false
        if ((b[0].toInt() and 0xFF) != 0x89 || ascii(b, 1, 3) != "PNG") return false
        val ct = b[25].toInt() and 0xFF
        if (ct == 4 || ct == 6) return true
        var i = 8
        while (i + 8 <= b.size) {
            val len = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
                ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 0) break
            val typ = ascii(b, i + 4, 4)
            if (typ == "tRNS") return true
            if (typ == "IEND") break
            val next = i + 12L + len.toLong()
            if (next <= i || next > Int.MAX_VALUE) break
            i = next.toInt()
        }
        return false
    }

    fun isAnimatedGif(b: ByteArray): Boolean {
        if (b.size < 13 || ascii(b, 0, 3) != "GIF") return false
        var i = 13
        val packed = b[10].toInt() and 0xFF
        if (packed and 0x80 != 0) i += 3 * (1 shl ((packed and 7) + 1))
        var frames = 0
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x3B -> break
                0x2C -> {
                    frames++
                    if (frames >= 2) return true
                    if (i + 10 >= b.size) break
                    val ip = b[i + 9].toInt() and 0xFF
                    i += 10
                    if (ip and 0x80 != 0) i += 3 * (1 shl ((ip and 7) + 1))
                    if (i >= b.size) break
                    i++
                    while (i < b.size) {
                        val n = b[i].toInt() and 0xFF
                        if (n == 0) { i++; break }
                        i += 1 + n
                    }
                }
                0x21 -> {
                    i += 2
                    while (i < b.size) {
                        val n = b[i].toInt() and 0xFF
                        if (n == 0) { i++; break }
                        i += 1 + n
                    }
                }
                else -> i++
            }
        }
        return false
    }

    fun fitsPhoto(bytes: Long, w: Int, h: Int): Boolean {
        if (bytes !in 1..MAX_BYTES) return false
        if (w <= 0 || h <= 0) return true
        if (w + h > MAX_SUM) return false
        if (minOf(w, h) > 0 && maxOf(w, h).toDouble() / minOf(w, h) > MAX_RATIO) return false
        return true
    }

    /** Escala se a soma passar de 10.000; preenche se a proporção passar de 20; escala de novo se precisar. */
    fun fitSize(w: Int, h: Int): Triple<Int, Int, Boolean> {
        if (w <= 0 || h <= 0) return Triple(w, h, false)
        var ww = w
        var hh = h
        fun scaleToSum() {
            val sum = ww + hh
            if (sum > MAX_SUM) {
                val f = MAX_SUM.toDouble() / sum
                ww = (ww * f).toInt().coerceAtLeast(1)
                hh = (hh * f).toInt().coerceAtLeast(1)
            }
        }
        scaleToSum()
        val mn = minOf(ww, hh)
        val mx = maxOf(ww, hh)
        val pad = mn > 0 && mx.toDouble() / mn > MAX_RATIO
        if (pad) {
            val need = kotlin.math.ceil(mx / MAX_RATIO).toInt().coerceAtLeast(1)
            if (ww == mx) hh = need else ww = need
            scaleToSum()
        }
        return Triple(ww, hh, pad)
    }

    fun plan(ext: String?, bytes: Long, w: Int, h: Int, hasAlpha: Boolean, animatedGif: Boolean): Plan {
        if (animatedGif && ext == "gif") return Plan(Mode.ANIMATION, w, h)
        val (fw, fh, pad) = fitSize(w, h)
        val sameSize = (w <= 0 || h <= 0) || (fw == w && fh == h)
        val direct = ext in setOf("jpg", "jpeg", "png") && !hasAlpha && !pad && sameSize && fitsPhoto(bytes, if (w > 0) w else fw, if (h > 0) h else fh)
        return if (direct) Plan(Mode.PHOTO_AS_IS, w, h, false) else Plan(Mode.CONVERT_JPEG, fw, fh, pad)
    }

    fun planFor(f: File, w: Int, h: Int): Plan {
        val head = sniffHead(f)
        val ext = PhotoGrab.imageExt(head)
        val alpha = ext == "png" && pngHasAlpha(head)
        val anim = ext == "gif" && isAnimatedGif(head)
        return plan(ext, f.length(), w, h, alpha, anim)
    }

    fun prepare(src: File): Prepared {
        val bounds = boundsOf(src)
        val plan = planFor(src, bounds.first, bounds.second)
        if (plan.mode == Mode.PHOTO_AS_IS || plan.mode == Mode.ANIMATION)
            return Prepared(src, plan.mode, bounds.first, bounds.second, false)
        val out = convertJpeg(src, plan)
        val sz = boundsOf(out)
        return Prepared(out, Mode.CONVERT_JPEG, sz.first, sz.second, true)
    }

    private fun boundsOf(f: File): Pair<Int, Int> = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 0 to 0
    } catch (_: Exception) { 0 to 0 }

    private fun decode(f: File): Bitmap? {
        try { BitmapFactory.decodeFile(f.absolutePath)?.let { return it } } catch (_: Exception) {}
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } catch (_: Exception) { null }
    }

    private fun convertJpeg(src: File, plan: Plan): File {
        val bmp = decode(src) ?: throw DlException("Não consegui ler a foto para enviar como imagem")
        try {
            var scaleW = if (plan.outW > 0) plan.outW else bmp.width
            var scaleH = if (plan.outH > 0) plan.outH else bmp.height
            if (scaleW <= 0) scaleW = bmp.width
            if (scaleH <= 0) scaleH = bmp.height
            val out = File(src.parentFile, src.nameWithoutExtension + "_tg.jpg")
            val sumScale0 = if (bmp.width + bmp.height > MAX_SUM) MAX_SUM.toDouble() / (bmp.width + bmp.height) else 1.0
            var contentW = (bmp.width * sumScale0).toInt().coerceAtLeast(1)
            var contentH = (bmp.height * sumScale0).toInt().coerceAtLeast(1)
            repeat(8) {
                if (contentW > scaleW) contentW = scaleW
                if (contentH > scaleH) contentH = scaleH
                val canvasBmp = Bitmap.createBitmap(scaleW, scaleH, Bitmap.Config.ARGB_8888)
                val c = Canvas(canvasBmp)
                c.drawColor(Color.WHITE)
                val left = (scaleW - contentW) / 2
                val top = (scaleH - contentH) / 2
                c.drawBitmap(
                    bmp,
                    android.graphics.Rect(0, 0, bmp.width, bmp.height),
                    android.graphics.Rect(left, top, left + contentW, top + contentH),
                    null,
                )
                FileOutputStream(out).use { canvasBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, it) }
                canvasBmp.recycle()
                if (out.length() in 1..MAX_BYTES && scaleW + scaleH <= MAX_SUM) return out
                scaleW = (scaleW * 0.9).toInt().coerceAtLeast(1)
                scaleH = (scaleH * 0.9).toInt().coerceAtLeast(1)
                contentW = (contentW * 0.9).toInt().coerceAtLeast(1)
                contentH = (contentH * 0.9).toInt().coerceAtLeast(1)
            }
            if (out.exists() && out.length() in 1..MAX_BYTES) return out
            throw DlException("A foto ficou grande demais para enviar como imagem")
        } finally {
            try { bmp.recycle() } catch (_: Exception) {}
        }
    }
}
