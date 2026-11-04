package br.geracaoai.baixartg.core

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
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
        val ref = if (referer != null && referer != url) referer else try { java.net.URI(url).let { "${it.scheme}://${it.authority}/" } } catch (_: Exception) { null }
        if (ref != null) header("Referer", ref)
    }.build()

    suspend fun download(f: Found, dir: File, server: String, maxHeight: Int = 0, progress: (Long, Long) -> Unit): File {
        val ref = f.ref ?: f.page
        return if (Bunkr.applies(f)) bunkr(f, dir, progress) else when (f.kind) {
            Kind.DIRECT -> try { retry { direct(f.url, ref, dir, f.title, progress) } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Fallback: o servidor abre a página com a mesma sessão (tokens presos a cookie/IP)
                if (f.page != f.url) viaServer(f.page, dir, server, progress) else throw e
            }
            Kind.HLS -> hls(f.url, ref, dir, f.title, progress, maxHeight = maxHeight)
            Kind.SERVER -> viaServer(f.url, dir, server, progress)
        }
    }

    /** Arquivo vindo de HLS precisa ser remuxado (.ts/fMP4 -> MP4) sem recodificar. Áudio separado fica em "<arquivo>.audio". */
    fun isHlsOutput(f: File) = f.name.contains(".hls.")
    fun audioOf(f: File) = File(f.path + ".audio")

    /** Bunkr: resolve a página /f/ (ou assina a URL do CDN) e baixa com Referer do site; 3 tentativas com token novo. */
    suspend fun bunkr(f: Found, dir: File, progress: (Long, Long) -> Unit): File {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val m = when {
                    Bunkr.isBunkrPage(f.url) -> Bunkr.resolve(f.url)
                    Bunkr.isCdn(f.url) && (attempt == 1 || !Bunkr.isBunkrPage(f.page)) -> Bunkr.Media(Bunkr.sign(f.url, referer = f.page), Bunkr.origin(f.page), f.title)
                    else -> Bunkr.resolve(f.page)
                }
                return direct(m.url, m.referer, dir, m.title.ifBlank { f.title }, progress)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e; kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        throw last ?: DlException("Bunkr: falhou")
    }

    fun safe(t: String) = t.replace(Regex("""\.(mp4|webm|mov|mkv|m4v|ts|m3u8)$""", RegexOption.IGNORE_CASE), "").replace(Regex("""[^\p{L}\p{N}\-_. ]+"""), "").trim().take(60).ifBlank { "video" }

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

    class Variant(val url: String, val bw: Long, val pixels: Long, val audio: String?, val height: Int = 0)

    /** Escolhe a maior qualidade (resolução, depois bitrate). maxHeight > 0 limita a altura (ajuste "Qualidade"). */
    fun pickVariant(plUrl: String, pl: String, maxHeight: Int = 0): Variant? {
        val all = allVariants(plUrl, pl)
        if (maxHeight > 0) { val ok = all.filter { it.height in 1..maxHeight }; if (ok.isNotEmpty()) return best(ok) }
        return best(all)
    }
    private fun best(l: List<Variant>) = l.maxWithOrNull(compareBy<Variant>({ it.pixels }, { it.bw }))
    private fun allVariants(plUrl: String, pl: String): List<Variant> {
        val lines = pl.lines(); val out = ArrayList<Variant>()
        for (i in lines.indices) if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
            val l = lines[i]
            val bw = Regex("""[^-]BANDWIDTH=(\d+)""").find(",$l")?.groupValues?.get(1)?.toLong() ?: 0
            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(l)
            val px = res?.let { it.groupValues[1].toLong() * it.groupValues[2].toLong() } ?: 0
            val ht = res?.groupValues?.get(2)?.toInt() ?: 0
            val au = Regex("""AUDIO="([^"]+)"""").find(l)?.groupValues?.get(1)
            val u = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            out += Variant(Extract.abs(plUrl, u) ?: continue, bw, px, au, ht)
        }
        return out
    }

    /** Faixa de áudio separada (EXT-X-MEDIA TYPE=AUDIO com URI) do grupo da variante. */
    fun audioRendition(plUrl: String, pl: String, group: String?): String? {
        if (group == null) return null
        val media = pl.lines().filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") && it.contains("GROUP-ID=\"$group\"") && it.contains("URI=") }
        val pick = media.firstOrNull { it.contains("DEFAULT=YES") } ?: media.firstOrNull() ?: return null
        return Regex("""URI="([^"]+)"""").find(pick)?.groupValues?.get(1)?.let { Extract.abs(plUrl, it) }
    }

    /** HLS: maior qualidade, segmentos em paralelo (ordem preservada), AES-128 e EXT-X-MAP. Sem recodificar. */
    suspend fun hls(url: String, referer: String?, dir: File, title: String, progress: (Long, Long) -> Unit, parallel: Int = 8, maxHeight: Int = 0): File {
        var plUrl = url; var pl = text(plUrl, referer)
        var audioUrl: String? = null
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val v = pickVariant(plUrl, pl, maxHeight) ?: throw DlException("Playlist sem variantes")
            audioUrl = audioRendition(plUrl, pl, v.audio)
            plUrl = v.url; pl = text(plUrl, referer)
        }
        val base = File(dir, safe(title) + "_" + System.nanoTime())
        val video = media(plUrl, pl, referer, base.path + ".hls", parallel, progress)
        if (audioUrl != null) {
            val a = media(audioUrl, text(audioUrl, referer), referer, base.path + ".a.hls", parallel) { _, _ -> }
            if (!a.renameTo(audioOf(video))) { a.copyTo(audioOf(video), true); a.delete() }
        }
        return video
    }

    private class Seg(val url: String, val key: String?, val iv: ByteArray?, val seq: Long)

    private suspend fun media(plUrl: String, pl: String, referer: String?, outBase: String, parallel: Int, progress: (Long, Long) -> Unit): File = coroutineScope {
        if (Regex("""METHOD=SAMPLE-AES""").containsMatchIn(pl)) throw DlException("Vídeo protegido (DRM)")
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
        segs.mapNotNull { it.key }.distinct().forEach { keys[it] = retry { bytes(it, referer) } }
        val out = File(outBase + if (mapUrl != null) ".mp4" else ".ts")
        try {
            out.outputStream().buffered(1 shl 20).use { os ->
                mapUrl?.let { os.write(retry { bytes(it, referer) }) }
                var total = 0L; var n = 0
                // janela deslizante: até `parallel` segmentos em voo, gravados na ordem
                val window = ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
                val it = segs.iterator()
                fun launchNext() { if (it.hasNext()) { val s = it.next(); window += async(Dispatchers.IO) { fetchSeg(s, keys, referer) } } }
                repeat(parallel.coerceAtLeast(1)) { launchNext() }
                while (window.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val data = window.removeFirst().await()
                    launchNext()
                    os.write(data); total += data.size; n++
                    progress(total, total / n * segs.size)
                }
            }
        } catch (e: Throwable) { out.delete(); throw e }
        out
    }

    private suspend fun fetchSeg(s: Seg, keys: Map<String, ByteArray>, referer: String?): ByteArray {
        var data = retry { bytes(s.url, referer) }
        if (s.key != null) {
            val k = keys[s.key]!!
            val ivb = s.iv ?: ByteArray(16).also { b -> var v = s.seq; for (j in 15 downTo 8) { b[j] = (v and 0xff).toByte(); v = v shr 8 } }
            data = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ivb)) }.doFinal(data)
        }
        return data
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

    /** Tamanho do arquivo antes de baixar (HEAD, ou GET Range 0-0). -1 = desconhecido (HLS/servidor/sem cabeçalho). */
    suspend fun sizeOf(f: Found): Long {
        if (f.kind != Kind.DIRECT || Bunkr.applies(f)) return -1
        val ref = f.ref ?: f.page
        return try {
            val r0 = req(f.url, ref)
            val h = Net.http.newCall(r0.newBuilder().head().build()).await().use { r ->
                val ct = r.header("Content-Type") ?: ""
                if (r.isSuccessful && !ct.contains("text/html", true)) r.header("Content-Length")?.toLongOrNull() ?: -1 else -1 }
            if (h > 0) h else Net.http.newCall(r0.newBuilder().header("Range", "bytes=0-0").build()).await().use { r ->
                r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; -1 }
    }
}
