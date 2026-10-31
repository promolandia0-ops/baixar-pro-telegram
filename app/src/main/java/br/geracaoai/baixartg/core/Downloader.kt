package br.geracaoai.baixartg.core

import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

class DlException(msg: String) : IOException(msg)

/** Baixa vídeos para o celular. progress(baixado, total ou -1). */
object Downloader {
    const val DEFAULT_SERVER = "https://baixar-video-ezck.onrender.com"

    private fun req(url: String, referer: String?) = Request.Builder().url(url).header("Accept", "*/*").apply {
        if (referer != null && referer != url) { header("Referer", referer); try { val u = java.net.URI(referer); header("Origin", "${u.scheme}://${u.authority}") } catch (_: Exception) {} }
    }.build()

    suspend fun download(f: Found, dir: File, server: String, progress: (Long, Long) -> Unit): File = when (f.kind) {
        Kind.DIRECT -> try { direct(f.url, f.page, dir, f.title, progress) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Fallback: o servidor abre a página com a mesma sessão (tokens presos a cookie/IP)
            if (f.page != f.url) viaServer(f.page, dir, server, progress) else throw e
        }
        Kind.HLS -> hls(f.url, f.page, File(dir, safe(f.title) + "_" + System.nanoTime() + ".ts"), progress)
        Kind.SERVER -> viaServer(f.url, dir, server, progress)
    }

    fun safe(t: String) = t.replace(Regex("""[^\p{L}\p{N}\-_. ]+"""), "").trim().take(60).ifBlank { "video" }

    private fun extOf(url: String, ct: String?): String {
        val e = Regex("""\.(mp4|webm|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()
        return e ?: when { ct?.contains("webm") == true -> "webm"; ct?.contains("quicktime") == true -> "mov"; else -> "mp4" }
    }

    suspend fun direct(url: String, referer: String?, dir: File, title: String, progress: (Long, Long) -> Unit): File {
        Net.http.newCall(req(url, referer)).await().use { r ->
            if (!r.isSuccessful) throw DlException("HTTP ${r.code} ao baixar")
            val ct = r.header("Content-Type")
            if (ct != null && ct.contains("text/html", true)) throw DlException("O link devolveu uma página, não um vídeo")
            val out = File(dir, safe(title) + "_" + System.nanoTime() + "." + extOf(url, ct))
            save(r, out, progress); return out
        }
    }

    private suspend fun save(r: Response, out: File, progress: (Long, Long) -> Unit, startAt: Long = 0, total: Long = -1) {
        val body = r.body ?: throw DlException("Resposta vazia")
        val len = if (total > 0) total else body.contentLength()
        var got = startAt
        try {
            out.outputStream().use { os -> got = copy(body.byteStream(), os, got, len, progress) }
        } catch (e: Throwable) { out.delete(); throw e }
        if (got - startAt == 0L) { out.delete(); throw DlException("Arquivo vazio") }
        if (len > 0 && got < len) { out.delete(); throw DlException("Download incompleto") }
    }

    private suspend fun copy(ins: java.io.InputStream, os: OutputStream, start: Long, len: Long, progress: (Long, Long) -> Unit): Long {
        val buf = ByteArray(256 * 1024); var got = start; var last = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = ins.read(buf); if (n < 0) break
            os.write(buf, 0, n); got += n
            val now = System.currentTimeMillis(); if (now - last > 300) { last = now; progress(got, len) }
        }
        progress(got, len); return got
    }

    /** Usa o backend baixar-video (yt-dlp + fallback HTML/KVS). */
    suspend fun viaServer(pageUrl: String, dir: File, server: String, progress: (Long, Long) -> Unit): File {
        val base = server.trimEnd('/')
        val json = "application/json".toMediaType()
        val body = JSONObject().put("url", pageUrl).put("mode", "video").toString().toRequestBody(json)
        val r = Net.slow.newCall(Request.Builder().url("$base/api/download").post(body).build()).await()
        r.use {
            val ct = r.header("Content-Type") ?: ""
            if (ct.contains("application/json")) {
                val j = JSONObject(r.body?.string() ?: "{}")
                if (!j.optBoolean("ok")) throw DlException(j.optString("error", "Servidor não conseguiu").take(200))
                val title = j.optString("title", "video")
                return when (j.optString("method")) {
                    "redirect" -> direct(j.getString("url"), pageUrl, dir, title, progress)
                    "proxy" -> {
                        val pb = JSONObject().put("page_url", pageUrl).toString().toRequestBody(json)
                        Net.slow.newCall(Request.Builder().url("$base/api/proxy").post(pb).build()).await().use { p ->
                            if (!p.isSuccessful) throw DlException("Servidor: " + (p.body?.string()?.let { s -> try { JSONObject(s).optString("error") } catch (_: Exception) { s } } ?: "HTTP ${p.code}").take(200))
                            val out = File(dir, safe(title) + "_" + System.nanoTime() + ".mp4"); save(p, out, progress); out
                        }
                    }
                    else -> throw DlException("Resposta inesperada do servidor")
                }
            }
            if (!r.isSuccessful) throw DlException("Servidor HTTP ${r.code}")
            val name = Regex("""filename="?([^";]+)""").find(r.header("Content-Disposition") ?: "")?.groupValues?.get(1) ?: "video.mp4"
            val out = File(dir, safe(name.substringBeforeLast('.')) + "_" + System.nanoTime() + "." + name.substringAfterLast('.', "mp4"))
            save(r, out, progress); return out
        }
    }

    private suspend fun text(url: String, referer: String?): String = Net.http.newCall(req(url, referer)).await().use { r ->
        if (!r.isSuccessful) throw DlException("HTTP ${r.code} na playlist"); r.body!!.string()
    }
    private suspend fun bytes(url: String, referer: String?): ByteArray = Net.http.newCall(req(url, referer)).await().use { r ->
        if (!r.isSuccessful) throw DlException("HTTP ${r.code} no segmento"); r.body!!.bytes()
    }

    /** HLS simples: escolhe a maior qualidade, junta os segmentos (suporta AES-128 e EXT-X-MAP). */
    suspend fun hls(url: String, referer: String?, out: File, progress: (Long, Long) -> Unit): File {
        var plUrl = url; var pl = text(plUrl, referer)
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val lines = pl.lines(); var best = -1L; var bestUrl: String? = null
            for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""").find(lines[i])?.groupValues?.get(1)?.toLong() ?: 0
                val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > best) { best = bw; bestUrl = Extract.abs(plUrl, u.trim()) }
            }
            plUrl = bestUrl ?: throw DlException("Playlist sem variantes"); pl = text(plUrl, referer)
        }
        if (pl.contains("#EXT-X-KEY") && Regex("""METHOD=(SAMPLE-AES)""").containsMatchIn(pl)) throw DlException("Vídeo protegido (DRM)")
        data class Seg(val url: String, val key: String?, val iv: ByteArray?, val seq: Long)
        val segs = ArrayList<Seg>(); var key: String? = null; var iv: ByteArray? = null; var seq = 0L; var mapUrl: String? = null
        for (l in pl.lines().map { it.trim() }) when {
            l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0
            l.startsWith("#EXT-X-MAP:") -> mapUrl = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1)?.let { Extract.abs(plUrl, it) }
            l.startsWith("#EXT-X-KEY:") -> {
                val m = Regex("""METHOD=([A-Z0-9-]+)""").find(l)?.groupValues?.get(1)
                if (m == "NONE") { key = null; iv = null } else {
                    key = Regex("""URI="([^"]+)"""").find(l)?.groupValues?.get(1)?.let { Extract.abs(plUrl, it) }
                    iv = Regex("""IV=0[xX]([0-9a-fA-F]+)""").find(l)?.groupValues?.get(1)?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                }
            }
            l.isNotEmpty() && !l.startsWith("#") -> { segs += Seg(Extract.abs(plUrl, l) ?: l, key, iv, seq); seq++ }
        }
        if (segs.isEmpty()) throw DlException("Playlist vazia")
        val keys = HashMap<String, ByteArray>()
        var out2 = out
        if (mapUrl != null) out2 = File(out.path.removeSuffix(".ts") + ".mp4")
        try {
            out2.outputStream().buffered().use { os ->
                mapUrl?.let { os.write(bytes(it, referer)) }
                var total = 0L
                segs.forEachIndexed { i, s ->
                    coroutineContext.ensureActive()
                    var data = retry { bytes(s.url, referer) }
                    if (s.key != null) {
                        val k = keys.getOrPut(s.key) { bytes(s.key, referer) }
                        val ivb = s.iv ?: ByteArray(16).also { b -> var v = s.seq; for (j in 15 downTo 8) { b[j] = (v and 0xff).toByte(); v = v shr 8 } }
                        data = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ivb)) }.doFinal(data)
                    }
                    os.write(data); total += data.size
                    // total estimado pela média dos segmentos
                    progress(total, total / (i + 1) * segs.size)
                }
            }
        } catch (e: Throwable) { out2.delete(); throw e }
        return out2
    }

    private suspend fun <T> retry(n: Int = 3, f: suspend () -> T): T {
        var last: Exception? = null
        repeat(n) { try { return f() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; last = e; kotlinx.coroutines.delay(1000) } }
        throw last!!
    }

    /** Pergunta ao servidor se ele (yt-dlp) acha vídeo na página. Retorna o título ou null. */
    suspend fun probe(server: String, pageUrl: String): String? {
        val body = JSONObject().put("url", pageUrl).toString().toRequestBody("application/json".toMediaType())
        Net.slow.newCall(Request.Builder().url(server.trimEnd('/') + "/api/info").post(body).build()).await().use { r ->
            val j = try { JSONObject(r.body?.string() ?: "") } catch (_: Exception) { return null }
            if (!j.optBoolean("ok")) return null
            val ex = j.optString("extractor")
            if (ex.equals("generic", true)) return null // genérico já foi coberto pelo rastreador
            return j.optString("title", pageUrl)
        }
    }
}
