package br.geracaoai.baixartg.core

import android.webkit.CookieManager
import br.geracaoai.baixartg.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Diagnóstico de cada FALHA da fila, para copiar e mandar no chat. Sem nome de vídeo, sem link e sem nome de site:
 * sites e servidores viram códigos (site#1a2b, cdn#3c4d). Junta o que aconteceu (rastro de cada tentativa) com
 * testes feitos AGORA: o link ainda abre? com qual origem? a lista HLS abre? e os pedaços? a chave AES? o servidor
 * yt-dlp reconhece a página? Termina com a causa provável.
 */
object FailDoctor {
    private val URL_RE = Regex("""https?://[^\s"'<>)]+""")
    private val HOST_RE = Regex("""\b(?:[a-z0-9-]+\.)+(?:com|net|org|br|io|tv|me|co|app|video|cloud|info|xyz|site|online|live|stream|cdn|ai|dev)\b""", RegexOption.IGNORE_CASE)

    private fun tag(u: String?, kind: String = "site") = if (u.isNullOrBlank()) "?" else SiteHash.short(kind, u)

    /** Tira links, nomes de site e o título do texto. */
    fun anon(s: String, title: String = ""): String {
        var t = URL_RE.replace(s) { tag(it.value, "link") }
        t = HOST_RE.replace(t) { tag(it.value, "host") }
        if (title.length >= 4) t = t.replace(title, "«título»", ignoreCase = true)
        return t
    }

    private data class Probe(val code: Int, val ct: String, val sniff: String?, val body: ByteArray)

    private suspend fun probe(url: String, ref: String?, max: Int, range: Boolean): Probe? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(12_000) {
            try {
                val b = Request.Builder().url(url).header("Accept", "*/*")
                if (range) b.header("Range", "bytes=0-" + (max - 1))
                if (!ref.isNullOrBlank()) { b.header("Referer", ref); Net.originOf(ref)?.let { b.header("Origin", it.trimEnd('/')) } }
                Net.fast.newCall(b.build()).await().use { r ->
                    val out = java.io.ByteArrayOutputStream()
                    r.body?.byteStream()?.use { ins -> val buf = ByteArray(16_384); while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) } }
                    val bytes = out.toByteArray()
                    Probe(r.code, r.header("Content-Type").orEmpty().substringBefore(';'), DetScan.sniff(bytes, r.header("Content-Type")), bytes)
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Probe(-1, e.javaClass.simpleName, null, ByteArray(0)) }
        }
    }

    suspend fun diagnose(t: Pipeline.PItem, n: Int): String {
        val f = t.f
        val title = t.title
        val sb = StringBuilder()
        val causes = ArrayList<String>()
        sb.appendLine("— falha $n —")
        sb.appendLine("app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME} • site ${tag(t.page)}" + (if (f != null) " • vídeo ${f.kind} em ${tag(f.url, "cdn")} • qualidade ${if (f.quality > 0) "${f.quality}p" else "?"}" else " • sem link do vídeo (não achou na página)"))
        sb.appendLine("tentativas: " + t.tried.split(',').filter { it.isNotBlank() }.joinToString(" → ") { Pipeline.stepName(it) }.ifBlank { "nenhuma" })
        sb.appendLine("mensagem: " + anon(t.msg.ifBlank { "?" }, title))

        // o que aconteceu, passo a passo (rastro)
        val ev = Trail.of(listOfNotNull(f?.url, t.page, f?.page))
        if (ev.isNotEmpty()) {
            sb.appendLine("rastro:")
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale("pt", "BR"))
            ev.takeLast(25).forEach { e -> sb.appendLine("  ${fmt.format(java.util.Date(e.t))} ${e.step}${if (e.detail.isNotBlank()) ": " + anon(e.detail, title) else ""}") }
        } else sb.appendLine("rastro: (nenhum — item de antes da v1.9.27 ou da memória)")

        if (f == null) {
            causes += "a página não entregou o link do vídeo (precisa achar de novo com play ou pelo detetive)"
        } else {
            val signed = DetLearn.isSigned(f.url)
            sb.appendLine("link: ${if (signed) "TEMPORÁRIO (tem token/validade)" else "fixo"} • cookies para o servidor do vídeo: ${cookieCount(f.url)} • para o site: ${cookieCount(t.page)}")
            if (f.kind == Kind.HLS || f.kind == Kind.DIRECT) {
                val play = try { PlayCapture.bestFor(f)?.referer } catch (_: Exception) { null }
                val pageO = Net.originOf(f.page)
                val refs = linkedMapOf<String, String?>("a gravada no item" to f.ref, "a que o player usou" to play, "a página" to f.page, "o endereço do site" to pageO, "o servidor do vídeo" to Net.originOf(f.url), "nenhuma" to "")
                var okRef: String? = null; var okProbe: Probe? = null
                val codes = ArrayList<Int>()
                sb.appendLine("teste agora (origem → resposta):")
                for ((name, r) in refs.entries.distinctBy { it.value }) {
                    if (r == null) continue
                    val p = probe(f.url, r, 200_000, range = true)
                    val line = if (p == null) "sem resposta (12 s)" else if (p.code < 0) "erro de conexão (${p.ct})" else "HTTP ${p.code} ${p.ct.ifBlank { "" }} → ${p.sniff ?: "?"}"
                    sb.appendLine("  $name: $line")
                    if (p != null) codes += p.code
                    if (okRef == null && p != null && p.code in 200..299 && (DetScan.isVideoSniff(p.sniff))) { okRef = r; okProbe = p }
                }
                if (okRef == null) {
                    causes += when {
                        codes.any { it == 403 || it == 401 || it == 410 } && signed -> "o link temporário EXPIROU ou é preso à sessão (HTTP ${codes.filter { it in setOf(401, 403, 410) }.distinct().joinToString("/")}): precisa pegar um link novo na página na hora de baixar"
                        codes.any { it == 403 || it == 401 } -> "o servidor do vídeo RECUSA o app com todas as origens (HTTP ${codes.filter { it == 401 || it == 403 }.distinct().joinToString("/")}): ele exige algo que só o player manda (cookie/token/cabeçalho) — o caminho é baixar pelo navegador"
                        codes.any { it == 404 } -> "o link não existe mais (HTTP 404)"
                        codes.all { it < 0 } -> "sem conexão com o servidor do vídeo"
                        else -> "o link não devolve vídeo (devolve página/erro)"
                    }
                } else {
                    sb.appendLine("abre com: " + refs.entries.first { it.value == okRef }.key)
                    if (okRef != f.ref) causes += "o item estava com a origem errada; abre com ${refs.entries.first { it.value == okRef }.key} (a v1.9.33 já escolhe a origem certa sozinha)"
                    val p = okProbe!!
                    if (p.sniff == "hls") {
                        val txt = String(p.body, Charsets.UTF_8)
                        val info = DetScan.hlsInfo(txt)
                        sb.appendLine("lista HLS: ${if (info.master) "principal com ${info.variants} qualidade(s) até ${info.maxHeight}p" else "de uma qualidade só (${QualityUp.heightIn(f.url).takeIf { it > 0 }?.let { "${it}p" } ?: "?"})"}${if (info.aes) " • AES-128" else ""}${if (info.drm) " • DRM" else ""}")
                        if (info.drm) causes += "o vídeo tem DRM (não dá para baixar)"
                        if (info.master && info.maxHeight in 1 until Prefs.minHeight) causes += "a maior qualidade do vídeo é ${info.maxHeight}p (abaixo do mínimo ${Prefs.minHeight}p)"
                        segTest(f.url, txt, okRef, sb, causes)
                    }
                }
            }
            if (t.page.startsWith("http") && !Extract.isVideoUrl(t.page)) {
                val r = withTimeoutOrNull(25_000) { Downloader.probe(Prefs.server, t.page) }
                sb.appendLine("servidor yt-dlp com a página: " + if (r != null) "reconhece" else "não reconhece (" + anon(Downloader.lastProbeError ?: "sem resposta", title).take(140) + ")")
            }
        }
        val msg = t.msg.lowercase()
        when {
            "abaixo de" in msg -> causes += "o arquivo baixado tinha resolução abaixo do mínimo (${Prefs.minHeight}p)"
            "página, não um vídeo" in msg || "html" in msg -> causes += "o servidor devolveu uma PÁGINA no lugar do vídeo (login caiu ou link expirou)"
            "timeout" in msg || "tempo" in msg -> causes += "o download demorou demais (conexão lenta ou o servidor segura)"
            "espaço" in msg || "space" in msg -> causes += "faltou espaço no celular"
        }
        sb.appendLine("CAUSA PROVÁVEL: " + causes.distinct().ifEmpty { listOf("não deu para saber só com estes testes") }.joinToString("; "))
        return sb.toString().trimEnd()
    }

    private suspend fun segTest(base: String, m3u8: String, ref: String?, sb: StringBuilder, causes: MutableList<String>) {
        fun firstUri(t: String) = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        var pl = m3u8; var plUrl = base
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val v = firstUri(pl)?.let { DetScan.resolve(plUrl, it) } ?: return
            val p = probe(v, ref, 400_000, range = false) ?: run { sb.appendLine("qualidade: sem resposta"); return }
            sb.appendLine("lista da qualidade: HTTP ${p.code} → ${p.sniff ?: "?"}")
            if (p.code !in 200..299) { causes += "a lista principal abre, mas a da qualidade não (HTTP ${p.code})"; return }
            pl = String(p.body, Charsets.UTF_8); plUrl = v
        }
        Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(pl)?.groupValues?.get(1)?.let { k ->
            val ku = DetScan.resolve(plUrl, k) ?: return@let
            if (ku.startsWith("http")) {
                val p = probe(ku, ref, 4_096, range = false)
                sb.appendLine("chave AES: ${if (p == null) "sem resposta" else "HTTP ${p.code}, ${p.body.size} bytes"}")
                if (p != null && (p.code !in 200..299 || p.body.size != 16)) causes += "a chave de criptografia não abre para o app (HTTP ${p.code}) — o player usa um token/cookie para ela"
            }
        }
        val seg = firstUri(pl)?.let { DetScan.resolve(plUrl, it) } ?: return
        val p = probe(seg, ref, 8_192, range = true)
        sb.appendLine("primeiro pedaço: ${if (p == null) "sem resposta" else "HTTP ${p.code} → ${p.sniff ?: "?"}"}" + " (em ${tag(seg, "cdn")})")
        if (p != null && p.code !in 200..299) causes += "a lista abre, mas os PEDAÇOS são recusados (HTTP ${p.code}) — o servidor dos pedaços pede outra origem/cookie"
    }

    private fun cookieCount(u: String?): Int = try { (CookieManager.getInstance().getCookie(u ?: "") ?: "").split(';').count { it.isNotBlank() } } catch (_: Exception) { 0 }
}
