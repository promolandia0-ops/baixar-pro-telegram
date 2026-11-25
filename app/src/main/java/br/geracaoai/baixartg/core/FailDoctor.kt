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

    private data class Probe(val code: Int, val ct: String, val sniff: String?, val body: ByteArray, val server: String = "")

    private suspend fun probe(url: String, ref: String?, max: Int, range: Boolean, origin: Boolean = false): Probe? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(12_000) {
            try {
                val b = Request.Builder().url(url).header("Accept", "*/*")
                if (range) b.header("Range", "bytes=0-" + (max - 1))
                if (!ref.isNullOrBlank()) { b.header("Referer", ref); if (origin) Net.originOf(ref)?.let { b.header("Origin", it.trimEnd('/')) } }
                Net.fast.newCall(b.build()).await().use { r ->
                    val out = java.io.ByteArrayOutputStream()
                    r.body?.byteStream()?.use { ins -> val buf = ByteArray(16_384); while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) } }
                    val bytes = out.toByteArray()
                    val srv = listOfNotNull(r.header("Server"), r.header("cf-ray")?.let { "cloudflare" }, r.header("x-amz-cf-id")?.let { "cloudfront" }, r.header("x-bunny-cache")?.let { "bunny" })
                        .joinToString("/") { it.lowercase().replace(Regex("[^a-z0-9./ -]"), "").take(24) }.ifBlank { "" }
                    Probe(r.code, r.header("Content-Type").orEmpty().substringBefore(';'), DetScan.sniff(bytes, r.header("Content-Type")), bytes, srv)
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Probe(-1, e.javaClass.simpleName, null, ByteArray(0)) }
        }
    }

    /** Texto da resposta de erro sem nada que identifique: links, sites, tokens e números longos somem. */
    private fun errorText(p: Probe, title: String): String {
        if (p.body.isEmpty() || p.sniff?.let { DetScan.isVideoSniff(it) } == true) return ""
        var t = String(p.body.copyOf(minOf(p.body.size, 1500)), Charsets.UTF_8)
        if (p.ct.contains("html", true)) t = t.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
        t = anon(t, title)
        t = t.replace(Regex("[A-Za-z0-9_\\-+/=]{20,}"), "…").replace(Regex("\\d{6,}"), "#").replace(Regex("\\s+"), " ").trim()
        return t.take(220)
    }

    /** Validade do link: nomes dos campos e, se houver data de vencimento, quanto faltava quando o app foi baixar. */
    private fun linkShape(url: String, foundAt: Long, triedAt: Long): String {
        val q = url.substringAfter('?', "").substringBefore('#')
        val pairs = q.split('&').filter { it.isNotBlank() }.map { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val names = pairs.map { it.first.take(20) }.distinct()
        val sb = StringBuilder("campos: " + (if (names.isEmpty()) "nenhum" else names.joinToString(", ")))
        val pathTok = Regex("/(?:[a-f0-9]{24,}|[A-Za-z0-9_-]{32,})/").containsMatchIn(url.substringBefore('?'))
        if (pathTok) sb.append(" • token no caminho")
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("pt", "BR"))
        val expName = Regex("^(e|exp|expire|expires|expiry|expiration|validto|valid|deadline|ttl|until|end|t|ts|time|timestamp)$")
        for ((k, v) in pairs) {
            if (!expName.matches(k)) continue
            val n = v.toLongOrNull() ?: continue
            val ms = when { n in 1_000_000_000L..9_999_999_999L -> n * 1000; n in 1_000_000_000_000L..9_999_999_999_999L -> n; else -> continue }
            if (ms < 1_500_000_000_000L) continue
            val now = System.currentTimeMillis()
            val parts = ArrayList<String>()
            parts += "campo '$k' = data ${fmt.format(java.util.Date(ms))}"
            if (foundAt > 0) parts += "achado ${fmt.format(java.util.Date(foundAt))} (" + secs(ms - foundAt) + " de validade)"
            if (triedAt > 0) parts += "baixar às ${fmt.format(java.util.Date(triedAt))} → " + if (triedAt > ms) "JÁ VENCIDO" else "faltavam ${secs(ms - triedAt)}"
            parts += "agora → " + if (now > ms) "vencido" else "faltam ${secs(ms - now)}"
            sb.append("\n  validade: " + parts.joinToString(" • "))
        }
        return sb.toString()
    }

    private fun secs(ms: Long): String { val s = ms / 1000; return if (kotlin.math.abs(s) >= 120) "${s / 60} min" else "$s s" }

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
        // só o que é DESTE vídeo (pela página vinham os eventos de todos os vídeos dela)
        val ev = (if (f != null) Trail.of(listOf(f.url)) else emptyList()).ifEmpty { Trail.of(listOf(t.page)) }
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
            if (f.kind == Kind.PHOTO) {
                // Foto: abre a imagem agora com cada origem e mede (tipo, tamanho em bytes e em pixels)
                sb.appendLine("teste da foto agora (origem → resposta):")
                var okAny = false
                val codesP = ArrayList<Int>()
                for ((name, r) in linkedMapOf("a página" to f.page, "o endereço do site" to Net.originOf(f.page), "nenhuma" to "").entries.distinctBy { it.value }) {
                    if (r == null) continue
                    val p = probe(f.url, r, 3_000_000, range = false)
                    if (p == null) { sb.appendLine("  $name: sem resposta (12 s)"); continue }
                    codesP += p.code
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    try { android.graphics.BitmapFactory.decodeByteArray(p.body, 0, p.body.size, o) } catch (_: Exception) {}
                    val dims = if (o.outWidth > 0) "${o.outWidth}x${o.outHeight}" else "não é imagem"
                    sb.appendLine("  $name: HTTP ${p.code} ${p.ct} • ${p.body.size / 1000} KB • $dims")
                    if (p.code in 200..299 && o.outWidth > 0) okAny = true
                    if (!okAny && p.code >= 400) errorText(p, title).takeIf { it.isNotBlank() }?.let { sb.appendLine("    resposta: $it") }
                }
                val alt = try { PhotoGrab.fallback(f.url) } catch (_: Exception) { null }
                sb.appendLine("versão alternativa da foto (maior/original): ${if (alt != null && alt != f.url) "existe (${tag(alt, "cdn")})" else "nenhuma"}")
                sb.appendLine("forma do link: " + linkShape(f.url, 0L, 0L))
                if (!okAny) causes += when {
                    codesP.any { it == 403 || it == 401 } -> "o servidor da foto recusa o app (HTTP 403/401): ela só abre dentro da página (proteção contra link direto)"
                    codesP.any { it == 404 || it == 410 } -> "a foto não existe mais nesse endereço (HTTP 404/410)"
                    codesP.all { it < 0 } -> "sem conexão com o servidor da foto"
                    else -> "o endereço não devolve uma imagem (devolve página/erro)"
                }
            }
            if (f.kind == Kind.HLS || f.kind == Kind.DIRECT) {
                val play = try { PlayCapture.bestFor(f)?.referer } catch (_: Exception) { null }
                val pageO = Net.originOf(f.page)
                val refs = linkedMapOf<String, String?>("a gravada no item" to f.ref, "a que o player usou" to play, "a página" to f.page, "o endereço do site" to pageO, "o servidor do vídeo" to Net.originOf(f.url), "nenhuma" to "")
                var okRef: String? = null; var okProbe: Probe? = null
                val codes = ArrayList<Int>()
                // quando foi achado e quando tentou baixar (pelo rastro)
                val foundAt = ev.firstOrNull { it.step.startsWith("achado") }?.t ?: 0L
                val triedAt = ev.firstOrNull { it.step.startsWith("download: começou") }?.t ?: 0L
                sb.appendLine("forma do link: " + linkShape(f.url, foundAt, triedAt))
                if (foundAt > 0 && triedAt > 0) sb.appendLine("tempo entre achar e baixar: ${secs(triedAt - foundAt)}")
                // o que o player mandou para o servidor do vídeo (só NOMES dos cabeçalhos)
                try {
                    val h = MseCapture.snapshot(f.page.ifBlank { t.page }).let { it.rangesOrDirect + it.manifests + it.segments }
                        .filter { Trail.host(it.url) == Trail.host(f.url) }
                    if (h.isNotEmpty()) sb.appendLine("o player pediu ${h.size}x a esse servidor • cabeçalhos do player: " +
                        h.flatMap { it.headers.keys }.map { it.lowercase() }.distinct().sorted().joinToString(", ").ifBlank { "nenhum além dos comuns" })
                    else sb.appendLine("o player não foi visto pedindo a esse servidor (pedido não capturado)")
                } catch (_: Exception) {}
                var firstErr: Probe? = null
                sb.appendLine("teste agora (origem → resposta), como o player (sem Origin) e com Origin:")
                for ((name, r) in refs.entries.distinctBy { it.value }) {
                    if (r == null) continue
                    for (withO in if (r.isBlank()) listOf(false) else listOf(false, true)) {
                        val p = probe(f.url, r, 200_000, range = true, origin = withO)
                        val line = if (p == null) "sem resposta (12 s)" else if (p.code < 0) "erro de conexão (${p.ct})" else "HTTP ${p.code} ${p.ct.ifBlank { "" }} → ${p.sniff ?: "?"}"
                        sb.appendLine("  $name${if (withO) " + Origin" else ""}: $line")
                        if (p != null) codes += p.code
                        if (p != null && p.code >= 400 && firstErr == null) firstErr = p
                        if (okRef == null && p != null && p.code in 200..299 && (DetScan.isVideoSniff(p.sniff))) { okRef = r; okProbe = p }
                    }
                }
                firstErr?.let { e ->
                    val txt = errorText(e, title)
                    sb.appendLine("resposta de erro do servidor (HTTP ${e.code}${if (e.server.isNotBlank()) ", servidor " + e.server else ""}): " + txt.ifBlank { "(vazia)" })
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

    /** Cabeçalho do "Copiar todos": aparelho, navegador, memória, rede e servidor (nada pessoal). */
    fun envReport(c: android.content.Context): String = buildString {
        appendLine("=== ambiente === app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME}")
        val wv = try { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(c)?.versionName ?: "?" } catch (_: Throwable) { "?" }
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) • WebView $wv")
        try {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val free = c.cacheDir.usableSpace
            appendLine("memória livre ${mi.availMem / 1_000_000} MB de ${mi.totalMem / 1_000_000} MB${if (mi.lowMemory) " (BAIXA)" else ""} • armazenamento livre ${free / 1_000_000_000} GB")
        } catch (_: Exception) {}
        try {
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                nc == null -> "sem rede"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
                nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "outra"
            }
            appendLine("rede: $net${nc?.let { " • ~${it.linkDownstreamBandwidthKbps / 1000} Mbps" } ?: ""}")
        } catch (_: Exception) {}
        appendLine("fila: ${Prefs.parDown} downloads ao mesmo tempo • navegadores ocultos ao mesmo tempo: no máx. ${BrowserMseDownloader.MAX_HIDDEN} • qualidade mínima ${Prefs.minHeight}p")
        appendLine("servidor yt-dlp: ${Downloader.ServerHealth.status()}")
        val l = Pipeline.items.value
        val by = l.groupingBy { it.st.name }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
        appendLine("fila agora: ${l.size} itens ($by)")
    }

    /**
     * Junta falhas iguais (mesmo site, mesmo servidor e mesma causa): mostra o diagnóstico completo da primeira
     * e só a contagem das outras — antes vinham 20 textos idênticos.
     */
    fun groupReports(reports: List<String>): String {
        fun keyOf(r: String): String {
            val lines = r.lines()
            val head = lines.getOrNull(1)?.substringAfter("•")?.trim().orEmpty()
            val cause = lines.lastOrNull { it.startsWith("CAUSA PROVÁVEL") }.orEmpty()
            val msg = lines.firstOrNull { it.startsWith("mensagem:") }.orEmpty()
            return "$head|$cause|$msg"
        }
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (r in reports.filter { it.isNotBlank() }) groups.getOrPut(keyOf(r)) { ArrayList() } += r
        return buildString {
            appendLine("=== resumo: ${reports.count { it.isNotBlank() }} falhas em ${groups.size} tipo(s) ===")
            groups.values.forEachIndexed { i, g ->
                val cause = g.first().lines().lastOrNull { it.startsWith("CAUSA PROVÁVEL") }?.removePrefix("CAUSA PROVÁVEL: ") ?: "?"
                appendLine("tipo ${i + 1}: ${g.size}× — ${cause.take(160)}")
            }
            groups.values.forEachIndexed { i, g ->
                appendLine()
                appendLine("##### tipo ${i + 1} (${g.size} falha${if (g.size > 1) "s" else ""} igual${if (g.size > 1) "is" else ""}) #####")
                append(g.first())
                appendLine()
            }
        }.trimEnd()
    }

    private fun cookieCount(u: String?): Int = try { (CookieManager.getInstance().getCookie(u ?: "") ?: "").split(';').count { it.isNotBlank() } } catch (_: Exception) { 0 }
}
