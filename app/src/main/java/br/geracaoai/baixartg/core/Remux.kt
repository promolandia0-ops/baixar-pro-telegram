package br.geracaoai.baixartg.core

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Copia as faixas (vídeo/áudio) de .ts / fMP4 para um MP4 normal — SEM recodificar (qualidade 100% original). */
object Remux {
    fun toMp4(inputs: List<File>, out: File) {
        if (inputs.all { TsDemux.isTs(it) }) { try { tsToMp4(inputs, out); return } catch (e: Exception) { out.delete(); if (e is UnsupportedOperationException) { /* cai no extrator do Android */ } else throw e } }
        extractorToMp4(inputs, out)
    }

    /** Formato (csd/SPS/PPS, taxa etc.) vem do MediaExtractor; as amostras vêm do nosso TsDemux (quadro a quadro). */
    private fun tsToMp4(inputs: List<File>, out: File) {
        var vFmt: MediaFormat? = null; var aFmt: MediaFormat? = null; var aFile: File? = null
        for (f in inputs) {
            val ex = MediaExtractor()
            try { ex.setDataSource(f.path)
                for (t in 0 until ex.trackCount) { val fmt = ex.getTrackFormat(t); val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/") && vFmt == null) vFmt = fmt
                    if (m.startsWith("audio/") && aFmt == null) { aFmt = fmt; aFile = f } }
            } finally { ex.release() }
        }
        if (vFmt == null) throw UnsupportedOperationException("sem vídeo")
        val dem = inputs.map { TsDemux(it) }
        try {
            dem.forEach { it.probe() }
            if (aFmt != null && dem.none { it.audioPid >= 0 && it.isAacAudio }) throw UnsupportedOperationException("áudio não-AAC")
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                isAvc = vFmt!!.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
                val vt = muxer.addTrack(vFmt!!)
                val at = aFmt?.let { muxer.addTrack(it) } ?: -1
                val audioDem = aFile?.let { inputs.indexOf(it) } ?: -1
                muxer.start(); started = true
                // cabeça de cada demuxer + tempo inicial comum
                val heads = arrayOfNulls<TsDemux.Sample>(dem.size)
                val pending = Array(dem.size) { ArrayDeque<TsDemux.Sample>() }
                var t0 = Long.MAX_VALUE
                for (i in dem.indices) { var seenV = false; var seenA = false; var k = 0
                    while (k++ < 400 && !(seenV && seenA)) { val smp = dem[i].next() ?: break; pending[i] += smp
                        if (smp.ptsUs >= 0) { t0 = minOf(t0, smp.ptsUs); if (smp.video) seenV = true else seenA = true } } }
                if (t0 == Long.MAX_VALUE) t0 = 0
                fun pull(i: Int): TsDemux.Sample? = pending[i].removeFirstOrNull() ?: dem[i].next()
                for (i in dem.indices) heads[i] = pull(i)
                var buf = ByteBuffer.allocateDirect(4 shl 20)
                val info = MediaCodec.BufferInfo()
                val lastPts = longArrayOf(-1, -1)
                while (true) {
                    var bi = -1; var bt = Long.MAX_VALUE
                    for (i in heads.indices) { val h = heads[i] ?: continue; val t = if (h.ptsUs < 0) Long.MIN_VALUE else h.ptsUs; if (t < bt) { bt = t; bi = i } }
                    if (bi < 0) break
                    val smp = heads[bi]!!; heads[bi] = pull(bi)
                    val track = if (smp.video) vt else if (at >= 0 && (audioDem < 0 || audioDem == bi)) at else -1
                    if (track < 0 || smp.data.isEmpty()) continue
                    val data = if (smp.video) annexB4(smp.data) else smp.data
                    val li = if (smp.video) 0 else 1
                    var pts = if (smp.ptsUs < 0) maxOf(lastPts[li], 0) else maxOf(0L, smp.ptsUs - t0)
                    if (!smp.video && pts <= lastPts[li]) pts = lastPts[li] + 1
                    if (buf.capacity() < data.size) buf = ByteBuffer.allocateDirect(data.size + (1 shl 20))
                    buf.clear(); buf.put(data); buf.flip()
                    info.set(0, data.size, pts, if (smp.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buf, info); lastPts[li] = pts
                }
            } finally {
                try { if (started) muxer.stop() } catch (_: Exception) {}
                try { muxer.release() } catch (_: Exception) {}
            }
        } finally { dem.forEach { try { it.close() } catch (_: Exception) {} } }
    }

    /**
     * Reescreve o quadro com códigos de início de 4 bytes (00 00 00 01) e sem AUD.
     * O MediaMuxer do Android corta 1 byte da NAL quando o próximo início tem só 3 bytes (00 00 01).
     * Os bytes das NALs ficam idênticos.
     */
    fun annexB4(d: ByteArray): ByteArray {
        val starts = ArrayList<Int>(); var i = 0
        while (i + 2 < d.size) { if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++ }
        if (starts.isEmpty()) return d
        val o = java.io.ByteArrayOutputStream(d.size + starts.size)
        for (k in starts.indices) {
            val st = starts[k]; var en = if (k + 1 < starts.size) starts[k + 1] - 3 else d.size
            if (k + 1 < starts.size) while (en > st && d[en - 1].toInt() == 0) en--
            if (en <= st) continue
            if ((d[st].toInt() and 0x1F) == 9 && (d[st].toInt() and 0x80) == 0 && isAvc) continue // AUD
            o.write(0); o.write(0); o.write(0); o.write(1); o.write(d, st, en - st)
        }
        return o.toByteArray()
    }
    @Volatile private var isAvc = true

    private fun extractorToMp4(inputs: List<File>, out: File) {
        val exs = ArrayList<MediaExtractor>(); val map = ArrayList<IntArray>()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            var maxIn = 1 shl 20; var hasVideo = false; var hasAudio = false
            for (f in inputs) {
                val ex = MediaExtractor(); ex.setDataSource(f.path)
                val m = IntArray(ex.trackCount) { -1 }
                for (t in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(t); val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    val isV = mime.startsWith("video/"); val isA = mime.startsWith("audio/")
                    if ((isV && hasVideo) || (isA && hasAudio) || (!isV && !isA)) continue
                    if (isV) { hasVideo = true; if (fmt.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(fmt.getInteger(MediaFormat.KEY_ROTATION)) }
                    if (isA) hasAudio = true
                    if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = maxOf(maxIn, fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    ex.selectTrack(t); m[t] = muxer.addTrack(fmt)
                }
                exs += ex; map += m
            }
            if (!hasVideo) throw IllegalStateException("Sem faixa de vídeo")
            muxer.start(); started = true
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 8 shl 20))
            val info = MediaCodec.BufferInfo()
            // tempo inicial comum (TS costuma começar em ~10s)
            val t0 = exs.map { it.sampleTime }.filter { it >= 0 }.minOrNull() ?: 0L
            val last = LongArray(64) { Long.MIN_VALUE }
            while (true) {
                // intercala pelas faixas: pega a amostra com menor tempo entre os arquivos
                var bi = -1; var bt = Long.MAX_VALUE
                for (i in exs.indices) { val t = exs[i].sampleTime; if (t >= 0 && t < bt) { bt = t; bi = i } }
                if (bi < 0) break
                val ex = exs[bi]; val tr = ex.sampleTrackIndex; val dst = map[bi][tr]
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n >= 0 && dst >= 0) {
                    var pts = maxOf(0L, ex.sampleTime - t0)
                    info.set(0, n, pts, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(dst, buf, info); last[dst] = pts
                }
                ex.advance()
            }
        } finally {
            try { if (started) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            exs.forEach { try { it.release() } catch (_: Exception) {} }
        }
    }

    /** Remuxa e deixa pronto pra streaming (moov no início). Devolve o MP4 final; apaga as entradas. */
    fun finish(video: File, audio: File?): File {
        val tmp = File(video.parentFile, video.name.substringBefore(".hls.") + ".remux.mp4")
        val out = File(video.parentFile, video.name.substringBefore(".hls.") + ".mp4")
        toMp4(listOfNotNull(video, audio?.takeIf { it.exists() }), tmp)
        if (tmp.length() < 1024) { tmp.delete(); throw IllegalStateException("Remux vazio") }
        if (Faststart.process(tmp, out)) tmp.delete() else tmp.renameTo(out)
        video.delete(); audio?.delete()
        return out
    }
}
