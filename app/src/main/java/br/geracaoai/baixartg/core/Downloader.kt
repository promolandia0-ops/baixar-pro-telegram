package br.geracaoai.baixartg.core

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        if (f.kind == Kind.MEGA) return Mega.download(f, dir, progress)
        return if (Bunkr.applies(f)) bunkr(f, dir, progress) else when (f.kind) {
            Kind.DIRECT -> try { retry { direct(f.url, ref, dir, f.title, progress) } } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Fallback: o servidor abre a página com a mesma sessão (tokens presos a cookie/IP)
                if (f.page != f.url) viaServer(f.page, dir, server, progress) else throw e
            }
            Kind.HLS -> hls(f.url, ref, dir, f.title, progress, maxHeight = maxHeight)
            Kind.SERVER -> viaServer(f.url, dir, server, progress)
            Kind.MEGA -> Mega.download(f, dir, progress)
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

    /** Partes em paralelo quando o servidor aceita HTTP Range; abaixo disso (ou sem Range) baixa em uma conexão, com retomada. */
    const val RANGE_PARTS = 8
    const val RANGE_MIN = 1_000_000L

    /** Fatias inclusivas [start, end] para um arquivo de `size` bytes. */
    fun planRanges(size: Long, parts: Int = RANGE_PARTS): List<LongArray> {
        val n = parts.coerceIn(1, 8)
        if (size <= 0L) return emptyList()
        val chunk = (size + n - 1) / n
        val out = ArrayList<LongArray>()
        var a = 0L
        while (a < size) {
            val b = minOf(a + chunk, size) - 1
            out += longArrayOf(a, b)
            a = b + 1
        }
        return out
    }

    suspend fun direct(url: String, referer: String?, dir: File, title: String, progress: (Long, Long) -> Unit): File {
        var size = -1L; var accept = false; var ext = extOf(url, null)
        try {
            Net.http.newCall(req(url, referer).newBuilder().header("Range", "bytes=0-0").build()).await().use { r ->
                if (!r.isSuccessful && r.code != 206) throw DlException("HTTP ${r.code} ao baixar")
                val ct = r.header("Content-Type")
                if (ct != null && ct.contains("text/html", true)) throw DlException("O link devolveu uma página, não um vídeo")
                ext = extOf(url, ct)
                size = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    ?: if (r.code == 200) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                accept = r.code == 206 || (r.header("Accept-Ranges")?.contains("bytes", true) == true)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e is DlException) throw e
        }
        val out = File(dir, safe(title) + "_" + System.nanoTime() + "." + ext)
        if (accept && size >= RANGE_MIN) {
            try { return ranged(url, referer, out, size, progress) }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                out.delete()
            }
        }
        return single(url, referer, out, progress, size)
    }

    private suspend fun ranged(url: String, referer: String?, out: File, size: Long, progress: (Long, Long) -> Unit): File = coroutineScope {
        val parts = planRanges(size, RANGE_PARTS)
        val files = parts.mapIndexed { i, _ -> File(out.path + ".part$i") }
        try {
            val got = LongArray(parts.size)
            parts.mapIndexed { i, r ->
                async(Dispatchers.IO) {
                    downloadRange(url, referer, files[i], r[0], r[1]) { n ->
                        synchronized(got) { got[i] = n; progress(got.sum(), size) }
                    }
                }
            }.awaitAll()
            java.io.FileOutputStream(out).use { os -> files.forEach { it.inputStream().use { ins -> ins.copyTo(os, 256 * 1024) } } }
            files.forEach { it.delete() }
            if (out.length() == 0L) { out.delete(); throw DlException("Arquivo vazio") }
            progress(out.length(), size); out
        } catch (e: Throwable) {
            files.forEach { it.delete() }; out.delete(); throw e
        }
    }

    private suspend fun downloadRange(url: String, referer: String?, part: File, start: Long, end: Long, onProg: (Long) -> Unit) {
        val want = end - start + 1
        var got = if (part.exists()) part.length() else 0L
        if (got > want) { part.delete(); got = 0L }
        if (got == want) { onProg(got); return }
        var last: Exception? = null
        repeat(3) {
            try {
                val from = start + got
                val r = Net.http.newCall(req(url, referer).newBuilder().header("Range", "bytes=$from-$end").build()).await()
                r.use { resp ->
                    if (resp.code == 200 && got == 0L && start == 0L) throw DlException("Range não suportado")
                    if (resp.code != 206) throw DlException("HTTP ${resp.code} no pedaço")
                    val body = resp.body ?: throw DlException("Resposta vazia")
                    java.io.FileOutputStream(part, got > 0).use { os ->
                        val buf = ByteArray(256 * 1024)
                        val ins = body.byteStream()
                        while (got < want) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), want - got).toInt()); if (n < 0) break
                            os.write(buf, 0, n); got += n; onProg(got)
                        }
                    }
                }
                if (got >= want) return
                last = DlException("Pedaço incompleto")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e; kotlinx.coroutines.delay(1000)
            }
        }
        throw last ?: DlException("Falha no pedaço")
    }

    private suspend fun single(url: String, referer: String?, out: File, progress: (Long, Long) -> Unit, known: Long): File {
        var got = if (out.exists()) out.length() else 0L
        var last: Exception? = null
        repeat(3) {
            try {
                val b = req(url, referer).newBuilder()
                if (got > 0) b.header("Range", "bytes=$got-")
                Net.http.newCall(b.build()).await().use { r ->
                    if (got > 0 && r.code == 200) { got = 0; out.delete() } // servidor ignorou o Range: começa de novo
                    if (!r.isSuccessful && r.code != 206) throw DlException("HTTP ${r.code} ao baixar")
                    val ct = r.header("Content-Type")
                    if (ct != null && ct.contains("text/html", true)) throw DlException("O link devolveu uma página, não um vídeo")
                    val total = when {
                        known > 0 -> known
                        r.code == 206 -> r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                        else -> r.body?.contentLength() ?: -1
                    }
                    save(r, out, progress, startAt = got, total = total)
                    got = out.length()
                    if (total > 0 && got < total) throw DlException("Download incompleto")
                    if (got == 0L) throw DlException("Arquivo vazio")
                    return out
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e; kotlinx.coroutines.delay(1000)
                got = if (out.exists()) out.length() else 0L
            }
        }
        if (out.exists() && out.length() == 0L) out.delete()
        throw last ?: DlException("Falha no download")
    }

    private suspend fun save(r: Response, out: File, progress: (Long, Long) -> Unit, startAt: Long = 0, total: Long = -1) {
        val body = r.body ?: throw DlException("Resposta vazia")
        val len = if (total > 0) total else if (startAt > 0 && body.contentLength() > 0) startAt + body.contentLength() else body.contentLength()
        var got = startAt
        java.io.FileOutputStream(out, startAt > 0).use { os -> got = copy(body.byteStream(), os, got, len, progress) }
        if (got - startAt == 0L && startAt == 0L) { out.delete(); throw DlException("Arquivo vazio") }
        if (len > 0 && got < len) throw DlException("Download incompleto")
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
        val body = session(JSONObject().put("url", pageUrl).put("mode", "video"), pageUrl).toString().toRequestBody(json)
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
                        val pb = session(JSONObject().put("page_url", pageUrl), pageUrl).toString().toRequestBody(json)
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

    /** Envia a sessão do navegador ao servidor (campos "cookies"/"user_agent"/"referer").
     *  O backend passa esses campos ao yt-dlp (cookies da sessão do navegador). */
    fun session(j: JSONObject, pageUrl: String): JSONObject {
        val c = try { Net.cookies?.cookieFor(pageUrl) } catch (_: Exception) { null }
        if (!c.isNullOrBlank()) j.put("cookies", c)
        return j.put("user_agent", Net.userAgent).put("referer", Net.originOf(pageUrl) ?: pageUrl)
    }

    /** Último erro do /api/info (para mostrar no lugar de “Nenhum vídeo encontrado”). */
    @Volatile var lastProbeError: String? = null

    /** Pergunta ao servidor se ele (yt-dlp) acha vídeo na página. Retorna o título ou null. */
    suspend fun probe(server: String, pageUrl: String): String? {
        lastProbeError = null
        val body = session(JSONObject().put("url", pageUrl), pageUrl).toString().toRequestBody("application/json".toMediaType())
        return try {
            Net.slow.newCall(Request.Builder().url(server.trimEnd('/') + "/api/info").post(body).build()).await().use { r ->
                val raw = r.body?.string() ?: ""
                val j = try { JSONObject(raw) } catch (_: Exception) {
                    lastProbeError = "Servidor yt-dlp: resposta inválida (HTTP ${r.code})"; return null
                }
                if (!j.optBoolean("ok")) {
                    lastProbeError = j.optString("error").ifBlank { "Servidor yt-dlp não achou vídeo (HTTP ${r.code})" }.take(300)
                    return null
                }
                val ex = j.optString("extractor")
                if (ex.equals("generic", true)) return null // genérico já foi coberto pelo rastreador
                j.optString("title", pageUrl)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastProbeError = "Não falei com o servidor yt-dlp: ${e.message}".take(300)
            null
        }
    }

    /** Busca no YouTube (ytsearch / busca do canal) via backend. */
    suspend fun search(server: String, pageUrl: String, query: String): List<Found> {
        lastProbeError = null
        val body = session(JSONObject().put("url", pageUrl).put("query", query), pageUrl).toString().toRequestBody("application/json".toMediaType())
        return try {
            Net.slow.newCall(Request.Builder().url(server.trimEnd('/') + "/api/search").post(body).build()).await().use { r ->
                val raw = r.body?.string() ?: ""
                val j = try { JSONObject(raw) } catch (_: Exception) {
                    lastProbeError = if (r.code == 404) "O servidor ainda não tem a busca por nome (atualize o baixar-video)." else "Servidor yt-dlp: resposta inválida (HTTP ${r.code})"
                    return emptyList()
                }
                if (!j.optBoolean("ok")) {
                    lastProbeError = j.optString("error").ifBlank { "Busca no YouTube falhou (HTTP ${r.code})" }.take(300)
                    return emptyList()
                }
                val arr = j.optJSONArray("results") ?: return emptyList()
                val out = ArrayList<Found>()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val u = e.optString("url")
                    if (u.isBlank()) continue
                    val title = e.optString("title", u)
                    val thumb = e.optString("thumbnail").ifBlank { SiteSearch.youtubeThumb(u) }
                    out += Found(u, u, title, Kind.SERVER, thumb = thumb)
                }
                out
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastProbeError = "Não falei com o servidor de busca: ${e.message}".take(300)
            emptyList()
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
