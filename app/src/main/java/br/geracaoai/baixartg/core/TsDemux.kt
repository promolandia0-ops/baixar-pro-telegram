package br.geracaoai.baixartg.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Demuxer MPEG-TS próprio (puro Kotlin): entrega cada quadro de vídeo (H.264/H.265 Annex-B) e
 * CADA quadro AAC separado (sem cabeçalho ADTS), com o PTS em microssegundos. Nada é recodificado.
 * (O extrator do Android junta vários quadros AAC numa amostra só, o que estraga o áudio no MP4.)
 */
class TsDemux(file: File) : Closeable {
    class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val ins: InputStream = BufferedInputStream(file.inputStream(), 1 shl 20)
    private val pkt = ByteArray(188)
    private var pmtPid = -1
    var videoPid = -1; private set
    var audioPid = -1; private set
    var videoType = 0; private set
    var audioType = 0; private set
    var sampleRate = 0; private set
    private val pes = HashMap<Int, java.io.ByteArrayOutputStream>()
    private val pesPts = HashMap<Int, Long>()
    private val out = ArrayDeque<Sample>()
    private var aBuf = ByteArray(0); private var aNext = -1L
    private var eof = false
    private val lastRaw = HashMap<Int, Long>(); private val wrap = HashMap<Int, Long>()

    val isAacAudio get() = audioType == 0x0F

    /** Lê até conhecer as faixas (PMT). */
    fun probe(): Boolean { while (pmtPid < 0 || (videoPid < 0 && audioPid < 0)) { if (!readPacket()) return false }; return true }

    fun next(): Sample? {
        while (out.isEmpty()) {
            if (eof) return null
            if (!readPacket()) { eof = true; flushAll() }
        }
        return out.removeFirst()
    }

    private fun readPacket(): Boolean {
        // ressincroniza no byte 0x47
        var b: Int
        do { b = ins.read(); if (b < 0) return false } while (b != 0x47)
        pkt[0] = 0x47
        var off = 1; while (off < 188) { val n = ins.read(pkt, off, 188 - off); if (n < 0) return false; off += n }
        val pusi = (pkt[1].toInt() and 0x40) != 0
        val pid = ((pkt[1].toInt() and 0x1F) shl 8) or (pkt[2].toInt() and 0xFF)
        val afc = (pkt[3].toInt() shr 4) and 3
        var p = 4
        if (afc == 2 || afc == 0) return true
        if (afc == 3) p += 1 + (pkt[4].toInt() and 0xFF)
        if (p >= 188) return true
        when {
            pid == 0 && pusi -> parsePat(p + 1 + (pkt[p].toInt() and 0xFF))
            pid == pmtPid && pusi -> parsePmt(p + 1 + (pkt[p].toInt() and 0xFF))
            pid == videoPid || pid == audioPid -> {
                if (pusi) { flush(pid); startPes(pid, p) } else pes[pid]?.write(pkt, p, 188 - p)
            }
        }
        return true
    }

    private fun parsePat(s: Int) {
        val len = ((pkt[s + 1].toInt() and 0x0F) shl 8) or (pkt[s + 2].toInt() and 0xFF)
        var i = s + 8; val end = minOf(s + 3 + len - 4, 188)
        while (i + 4 <= end) {
            val prog = ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            val pid = ((pkt[i + 2].toInt() and 0x1F) shl 8) or (pkt[i + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPid = pid; return }
            i += 4
        }
    }

    private fun parsePmt(s: Int) {
        val len = ((pkt[s + 1].toInt() and 0x0F) shl 8) or (pkt[s + 2].toInt() and 0xFF)
        val pil = ((pkt[s + 10].toInt() and 0x0F) shl 8) or (pkt[s + 11].toInt() and 0xFF)
        var i = s + 12 + pil; val end = minOf(s + 3 + len - 4, 188)
        while (i + 5 <= end) {
            val type = pkt[i].toInt() and 0xFF
            val pid = ((pkt[i + 1].toInt() and 0x1F) shl 8) or (pkt[i + 2].toInt() and 0xFF)
            val esl = ((pkt[i + 3].toInt() and 0x0F) shl 8) or (pkt[i + 4].toInt() and 0xFF)
            if ((type == 0x1B || type == 0x24) && videoPid < 0) { videoPid = pid; videoType = type }
            else if ((type == 0x0F || type == 0x03 || type == 0x04 || type == 0x81) && audioPid < 0) { audioPid = pid; audioType = type }
            i += 5 + esl
        }
    }

    private fun startPes(pid: Int, p: Int) {
        if (188 - p < 9 || pkt[p].toInt() != 0 || pkt[p + 1].toInt() != 0 || pkt[p + 2].toInt() != 1) { pes.remove(pid); return }
        val flags = pkt[p + 7].toInt() and 0xFF
        val hl = pkt[p + 8].toInt() and 0xFF
        if (flags and 0x80 != 0) pesPts[pid] = unwrap(pid, ts(p + 9)) else pesPts.remove(pid)
        val bo = java.io.ByteArrayOutputStream(if (pid == videoPid) 256 * 1024 else 8 * 1024)
        val st = p + 9 + hl
        if (st < 188) bo.write(pkt, st, 188 - st)
        pes[pid] = bo
    }

    private fun ts(i: Int): Long =
        ((pkt[i].toLong() and 0x0E) shl 29) or ((pkt[i + 1].toLong() and 0xFF) shl 22) or ((pkt[i + 2].toLong() and 0xFE) shl 14) or
            ((pkt[i + 3].toLong() and 0xFF) shl 7) or ((pkt[i + 4].toLong() and 0xFE) shr 1)

    private fun unwrap(pid: Int, raw: Long): Long {
        val last = lastRaw[pid]; var w = wrap[pid] ?: 0L
        if (last != null && raw < last - (1L shl 32)) w += 1L shl 33
        lastRaw[pid] = raw; wrap[pid] = w
        return (raw + w) * 100 / 9
    }

    private fun flushAll() { flush(videoPid); flush(audioPid) }

    private fun flush(pid: Int) {
        val bo = pes.remove(pid) ?: return
        val data = bo.toByteArray(); if (data.isEmpty()) return
        val pts = pesPts[pid] ?: -1L
        if (pid == videoPid) { out += Sample(true, data, pts, isKey(data)); return }
        if (!isAacAudio) { out += Sample(false, data, pts, true); return }
        // AAC/ADTS: separa quadro a quadro
        if (aBuf.isEmpty() && pts >= 0) aNext = pts
        else if (pts >= 0 && aNext < 0) aNext = pts
        aBuf = if (aBuf.isEmpty()) data else aBuf + data
        var i = 0
        while (i + 7 <= aBuf.size) {
            if ((aBuf[i].toInt() and 0xFF) != 0xFF || (aBuf[i + 1].toInt() and 0xF0) != 0xF0) { i++; continue }
            val prot = (aBuf[i + 1].toInt() and 1) == 0
            val sri = (aBuf[i + 2].toInt() shr 2) and 0x0F
            val flen = ((aBuf[i + 3].toInt() and 3) shl 11) or ((aBuf[i + 4].toInt() and 0xFF) shl 3) or ((aBuf[i + 5].toInt() and 0xE0) shr 5)
            val nraw = (aBuf[i + 6].toInt() and 3) + 1
            val hl = if (prot) 9 else 7
            if (flen < hl) { i++; continue }
            if (i + flen > aBuf.size) break
            val sr = RATES.getOrElse(sri) { 44100 }; if (sampleRate == 0) sampleRate = sr
            out += Sample(false, aBuf.copyOfRange(i + hl, i + flen), maxOf(aNext, 0), true)
            aNext += nraw * 1024L * 1_000_000L / sr
            i += flen
        }
        aBuf = if (i >= aBuf.size) ByteArray(0) else aBuf.copyOfRange(i, aBuf.size)
    }

    private fun isKey(d: ByteArray): Boolean {
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                val h = d[i + 3].toInt() and 0xFF
                if (videoType == 0x1B) { if ((h and 0x1F) == 5) return true }
                else { val t = (h shr 1) and 0x3F; if (t in 16..21) return true }
                i += 3
            } else i++
        }
        return false
    }

    override fun close() = ins.close()

    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        fun isTs(f: File): Boolean = try { f.inputStream().use { s -> val b = ByteArray(189 * 2); val n = s.read(b); n >= 377 && b[0].toInt() == 0x47 && b[188].toInt() == 0x47 } } catch (_: Exception) { false }
    }
}
