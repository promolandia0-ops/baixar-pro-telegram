package br.geracaoai.baixartg.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Impressão digital visual de vídeo, MUITO rigorosa (na dúvida = diferente).
 * Repetido só se: duração ±1 s, mesma proporção, ≥4 quadros úteis comparáveis (de 7) e TODOS com
 * dHash ≤6 E pHash ≤6, e histograma de cor do quadro do meio com correlação ≥0.95.
 * Quadros pretos/uniformes/com pouca variação ficam de fora.
 */
object VisualHash {
    val POS = doubleArrayOf(0.05, 0.20, 0.35, 0.50, 0.65, 0.80, 0.95)
    const val MAX_DIST = 6
    const val MIN_FRAMES = 3
    /** Só 3 momentos (20%, 50%, 80%) e em tamanho pequeno: bem mais leve; os 3 precisam bater. */
    private val SAMPLE = intArrayOf(1, 3, 5)
    /** Retriever em uso agora (1 por vez no app inteiro; se ocupado, a fila não espera). */
    val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    class Fp(val dur: Int, val w: Int, val h: Int, val d: LongArray, val p: LongArray, val ok: BooleanArray, val hist: FloatArray?, val title: String) {
        val pixels get() = w.toLong() * h
        val usable get() = ok.count { it }
    }

    // ---------- hashes de imagem ----------
    private fun gray(b: Bitmap, w: Int, h: Int): DoubleArray {
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== b) s.recycle()
        return DoubleArray(w * h) { i -> val c = px[i]; 0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255) }
    }
    /** Quadro útil: não preto, não uniforme, com variação suficiente. */
    fun usable(b: Bitmap): Boolean {
        val g = gray(b, 32, 32)
        val mean = g.average()
        val sd = sqrt(g.sumOf { (it - mean) * (it - mean) } / g.size)
        return mean > 18 && mean < 240 && sd >= 14
    }
    fun dHash(b: Bitmap): Long {
        val g = gray(b, 9, 8); var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) { h = h shl 1; if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or 1L }
        return h
    }
    private val COS = Array(8) { u -> DoubleArray(32) { x -> cos((2 * x + 1) * u * Math.PI / 64) } }
    fun pHash(b: Bitmap): Long {
        val g = gray(b, 32, 32)
        val tmp = Array(32) { DoubleArray(8) }   // DCT nas linhas (só 8 frequências)
        for (y in 0 until 32) for (u in 0 until 8) { var s = 0.0; for (x in 0 until 32) s += g[y * 32 + x] * COS[u][x]; tmp[y][u] = s }
        val dct = DoubleArray(64)
        for (v in 0 until 8) for (u in 0 until 8) { var s = 0.0; for (y in 0 until 32) s += tmp[y][u] * COS[v][y]; dct[v * 8 + u] = s }
        val vals = dct.copyOfRange(1, 64).sorted(); val med = vals[vals.size / 2]
        var h = 0L
        for (i in 0 until 64) { h = h shl 1; if (i != 0 && dct[i] > med) h = h or 1L }
        return h
    }
    /** Histograma de cor 4x4x4 normalizado. */
    fun hist(b: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(b, 48, 48, true)
        val px = IntArray(48 * 48); s.getPixels(px, 0, 48, 0, 0, 48, 48); if (s !== b) s.recycle()
        val hs = FloatArray(64)
        for (c in px) hs[(((c shr 16) and 255) shr 6) * 16 + (((c shr 8) and 255) shr 6) * 4 + ((c and 255) shr 6)] += 1f
        for (i in hs.indices) hs[i] /= px.size.toFloat()
        return hs
    }
    fun corr(a: FloatArray, b: FloatArray): Double {
        val ma = a.average(); val mb = b.average()
        var n = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; n += x * y; da += x * x; db += y * y }
        return if (da <= 0 || db <= 0) 0.0 else n / sqrt(da * db)
    }
    fun ham(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)
    fun aspectOk(w1: Int, h1: Int, w2: Int, h2: Int): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false
        val a = w1.toDouble() / h1; val b = w2.toDouble() / h2
        return abs(a - b) / maxOf(a, b) <= 0.03
    }

    // ---------- vídeo ----------
    fun fingerprint(file: File, title: String): Fp? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (durMs < 3000) return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = LongArray(POS.size); val p = LongArray(POS.size); val ok = BooleanArray(POS.size)
            var hs: FloatArray? = null
            for (i in SAMPLE) {
                val us = (durMs * POS[i] * 1000).toLong()
                val b = try { r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 96) } catch (_: Exception) { null } ?: continue
                try {
                    if (usable(b)) { d[i] = dHash(b); p[i] = pHash(b); ok[i] = true; if (i == 3) hs = hist(b) }
                } finally { b.recycle() }
            }
            return Fp(Math.round(durMs / 1000.0).toInt(), w, h, d, p, ok, hs, title)
        } catch (_: Exception) { return null } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** true só quando é praticamente certo que é o mesmo vídeo. */
    fun same(a: Fp, b: Fp): Boolean {
        if (abs(a.dur - b.dur) > 1) return false
        if (!aspectOk(a.w, a.h, b.w, b.h)) return false
        if (a.usable < MIN_FRAMES || b.usable < MIN_FRAMES) return false
        var n = 0
        for (i in POS.indices) {
            if (!a.ok[i] || !b.ok[i]) continue
            if (ham(a.d[i], b.d[i]) > MAX_DIST || ham(a.p[i], b.p[i]) > MAX_DIST) return false
            n++
        }
        if (n < MIN_FRAMES) return false
        val ha = a.hist; val hb = b.hist
        if (ha == null || hb == null || corr(ha, hb) < 0.95) return false
        return true
    }

    // ---------- impressões dos já enviados (persistente) + itens da fila em andamento ----------
    private var file: File? = null
    private val sent = ArrayList<Fp>()
    private val pending = HashMap<Long, Fp>()
    private val lock = Any()
    @Volatile private var loaded = false

    fun init(c: Context) { file = File(c.applicationContext.filesDir, "visual_sent.txt") }
    private fun ensure() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try { file?.takeIf { it.exists() }?.forEachLine { decode(it)?.let(sent::add) } } catch (_: Exception) {}
            loaded = true
        }
    }
    private fun encode(f: Fp): String = listOf(f.dur, f.w, f.h,
        f.d.joinToString(",") { java.lang.Long.toHexString(it) }, f.p.joinToString(",") { java.lang.Long.toHexString(it) },
        f.ok.joinToString("") { if (it) "1" else "0" }, f.hist?.joinToString(",") { "%.4f".format(java.util.Locale.US, it) } ?: "",
        f.title.replace('\t', ' ').replace('\n', ' ').take(120)).joinToString("\t")
    private fun decode(l: String): Fp? = try {
        val s = l.split('\t')
        Fp(s[0].toInt(), s[1].toInt(), s[2].toInt(),
            s[3].split(',').map { java.lang.Long.parseUnsignedLong(it, 16) }.toLongArray(),
            s[4].split(',').map { java.lang.Long.parseUnsignedLong(it, 16) }.toLongArray(),
            s[5].map { it == '1' }.toBooleanArray(),
            s[6].takeIf { it.isNotBlank() }?.split(',')?.map { it.toFloat() }?.toFloatArray(), s.getOrElse(7) { "" })
    } catch (_: Exception) { null }

    /** Procura igual entre os enviados e os outros itens da fila; se não achar, reserva este. Devolve o igual (ou null). */
    fun claim(id: Long, f: Fp): Fp? {
        ensure()
        synchronized(lock) {
            if (f.usable < MIN_FRAMES) return null
            sent.firstOrNull { same(f, it) }?.let { return it }
            pending.entries.firstOrNull { it.key != id && same(f, it.value) }?.let { return it.value }
            pending[id] = f
            return null
        }
    }
    fun release(id: Long) { synchronized(lock) { pending.remove(id) } }
    fun markSent(id: Long, f: Fp?) {
        val v = f ?: synchronized(lock) { pending[id] } ?: return
        ensure()
        synchronized(lock) {
            pending.remove(id)
            if (v.usable < MIN_FRAMES) return
            sent += v
            try { file?.appendText(encode(v) + "\n") } catch (_: Exception) {}
        }
    }
}
