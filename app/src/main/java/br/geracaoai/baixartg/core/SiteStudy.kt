package br.geracaoai.baixartg.core

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ESTUDAR ESTE SITE: enquanto o vídeo toca, grava COMO o player busca o vídeo (cada pedido: tipo, método, origem,
 * cabeçalhos especiais, Range), o que as respostas trazem (lista HLS principal/da qualidade, chave, JSON com vídeos),
 * quais players e quadros existem, e TESTA cada pedido importante do jeito do app (igual ao navegador, só com a página
 * como origem, sem origem). Gera um CÓDIGO para colar no chat, com a estrutura e sem nada pessoal:
 *  - site e servidores viram rótulos (SITE, SITE.sub1, H1…); só nomes de empresas de vídeo conhecidas ficam (vimeocdn…);
 *  - partes do endereço que não são técnicas viram marcadores ({w1}, {id2}…); o mesmo valor ganha o mesmo marcador,
 *    então dá para ver que o código da página aparece no link do vídeo sem saber qual é;
 *  - parâmetros: só o nome; cabeçalhos e cookies: só os nomes. Nenhum título, nenhum valor.
 */
object SiteStudy {
    @Volatile var active = false
        private set
    data class Req(val url: String, val method: String, val hdrs: Map<String, String>, val main: Boolean, val src: String, val t: Long)
    private val reqs = ConcurrentLinkedQueue<Req>()
    @Volatile private var t0 = 0L

    fun start() { reqs.clear(); t0 = System.currentTimeMillis(); active = true }
    fun stop() { active = false }
    fun count() = reqs.size

    fun req(u: String, method: String?, hdrs: Map<String, String>?, main: Boolean, src: String) {
        if (!active || !u.startsWith("http") || reqs.size >= 4000) return
        reqs += Req(u, method ?: "GET", hdrs ?: emptyMap(), main, src, System.currentTimeMillis() - t0)
    }

    // ============================================================ anonimizador
    private class Anon(page: String) {
        val pageHost = host(page)
        val pageReg = reg(pageHost)
        val hosts = LinkedHashMap<String, String>()
        val toks = LinkedHashMap<String, String>()
        var nTok = 0

        fun host(u: String) = try { (java.net.URI(u).host ?: "").lowercase() } catch (_: Exception) { "" }
        fun reg(h: String): String { val p = h.split('.'); return if (p.size >= 3 && p[p.size - 2].length <= 3 && p.last().length == 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".") }

        private val VENDOR = Regex("""(?i)(vimeocdn\.com|vimeo\.com|pandavideo\.com(?:\.br)?|b-cdn\.net|mediadelivery\.net|cloudfront\.net|akamaized\.net|akamaihd\.net|cloudflarestream\.com|videodelivery\.net|mux\.com|wistia\.(?:com|net)|jwplayer\.com|jwpcdn\.com|brightcove(?:cdn)?\.(?:com|net)|boltdns\.net|kaltura\.com|googlevideo\.com|youtube\.com|ytimg\.com|converteai\.net|hotmart\.com|spotlightr\.com|sproutvideo\.com|vidyard\.com|fastly\.net|llnwd\.net|edgesuite\.net|bunnycdn\.com|gumlet\.io|vdocipher\.com|smartplayer\.io|scaleup\.com\.br|amazonaws\.com|googleapis\.com|azureedge\.net|cdn77\.org|cachefly\.net|gcore\.com|jsdelivr\.net|cloudflare\.com)$""")

        fun hostLabel(h0: String): String {
            val h = h0.lowercase()
            hosts[h]?.let { return it }
            val v = VENDOR.find(h)?.value
            val lab = when {
                h == pageHost -> "SITE"
                reg(h) == pageReg -> "SITE.sub" + (hosts.values.count { it.startsWith("SITE.sub") } + 1)
                v != null -> { val sub = h.removeSuffix(v).trimEnd('.'); (if (sub.isBlank()) "" else sub.split('.').joinToString(".") { l -> if (l.any { it.isDigit() } || l.length > 12) tok(l) else l } + ".") + v }
                else -> "H" + (hosts.values.count { Regex("""^H\d+$""").matches(it) } + 1)
            }
            hosts[h] = lab
            return lab
        }

        private val TECH = Regex("""(?i)^(?:hls|dash|video|videos|vid|embed|player|players|play|playback|playlist|master|index|manifest|main|media|medias|stream|streams|streaming|segment|segments|seg|chunk|chunks|frag|fragment|init|key|keys|drm|license|api|v\d{1,2}|graphql|json|config|configs|source|sources|file|files|content|contents|asset|assets|vod|live|audio|subtitles?|captions?|thumb|thumbs|thumbnail|poster|preview|watch|lesson|lessons|aula|aulas|course|courses|curso|cursos|module|modules|modulo|modulos|class|classes|share|e|v|p|s|static|public|storage|cdn|uploads?|download|redirect|token|auth|session|sign|signed|url|urls|get|load|info|data|meta|metadata|track|tracks|quality|qualities|levels?|rendition|renditions|\d{3,4}p|hd|sd|fhd|uhd|low|high|mid|mp4|ts|m3u8|mpd|m4s|m4v|m4a|aac|webm|mov|bin|vtt|srt|exp|acl|hmac)$""")
        private val FILE_EXT = Regex("""(?i)^(.*?)\.(m3u8|mpd|ts|m4s|mp4|m4v|m4a|aac|webm|mov|key|json|bin|vtt|js|html?|php|aspx?)$""")

        fun tok(v: String): String {
            toks[v]?.let { return it }
            val kind = when {
                Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE).matches(v) -> "uuid"
                Regex("""^\d+$""").matches(v) -> "n${v.length}"
                Regex("""^[0-9a-f]+$""", RegexOption.IGNORE_CASE).matches(v) && v.length >= 8 -> "hex${v.length}"
                Regex("""^[A-Za-z0-9_\-]+={0,2}$""").matches(v) && v.length >= 16 && v.any { it.isDigit() } -> "tk${v.length}"
                Regex("""^[a-z]+(?:-[a-z0-9]+)+$""").matches(v) -> "slug"
                else -> "w${v.length}"
            }
            nTok++
            val lab = "{${kind}#$nTok}"
            toks[v] = lab
            return lab
        }

        fun seg(s0: String): String {
            val s = try { java.net.URLDecoder.decode(s0, "UTF-8") } catch (_: Exception) { s0 }
            if (s.isEmpty()) return ""
            if (TECH.matches(s)) return s
            FILE_EXT.matchEntire(s)?.let { m ->
                val base = m.groupValues[1]
                val b = when {
                    base.isEmpty() -> ""
                    TECH.matches(base) -> base
                    Regex("""^(?:[a-z_\-]*?)(\d{1,6})$""", RegexOption.IGNORE_CASE).matches(base) && base.trimEnd { it.isDigit() }.let { it.isEmpty() || TECH.matches(it.trimEnd('-', '_')) } ->
                        base.trimEnd { it.isDigit() } + "{num}"
                    else -> tok(base)
                }
                return b + "." + m.groupValues[2].lowercase()
            }
            Regex("""^(\d{3,4})p$""").matchEntire(s)?.let { return s }
            return tok(s)
        }

        fun url(u: String): String = try {
            val x = java.net.URI(u)
            val h = hostLabel(x.host ?: "?")
            val path = (x.rawPath ?: "").split('/').joinToString("/") { seg(it) }
            val q = (x.rawQuery ?: "").split('&').filter { it.isNotBlank() }.joinToString("&") { kv ->
                val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
                k + "=" + (if (v.isEmpty()) "" else if (v.length <= 6 && Regex("""^[a-z0-9]+$""").matches(v) && !Regex("""^\d{4,}$""").matches(v)) v else tok(try { java.net.URLDecoder.decode(v, "UTF-8") } catch (_: Exception) { v }))
            }
            h + path + (if (q.isNotBlank()) "?$q" else "")
        } catch (_: Exception) { "?" }

        fun refRole(ref: String?, page: String, frames: Set<String>): String = when {
            ref.isNullOrBlank() -> "nenhuma"
            ref.substringBefore('#') == page.substringBefore('#') -> "página"
            host(ref) == pageHost -> if (ref.trimEnd('/').count { it == '/' } <= 2) "site(raiz)" else "outra página do site"
            frames.any { host(it) == host(ref) } -> "player(" + hostLabel(host(ref)) + ")"
            else -> hostLabel(host(ref)) + if (ref.trimEnd('/').count { it == '/' } <= 2) "(raiz)" else ""
        }
    }

    private val STD_HDR = setOf("accept", "accept-language", "accept-encoding", "user-agent", "referer", "origin", "range", "sec-fetch-dest", "sec-fetch-mode",
        "sec-fetch-site", "sec-fetch-user", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "upgrade-insecure-requests", "x-requested-with", "cache-control", "pragma", "if-none-match", "if-modified-since", "dnt", "priority")

    private fun type(r: Req): String {
        val u = r.url.substringBefore('?').lowercase()
        val dest = r.hdrs.entries.firstOrNull { it.key.equals("sec-fetch-dest", true) }?.value.orEmpty()
        return when {
            u.endsWith(".m3u8") || "m3u8" in r.url.lowercase() -> "lista-hls"
            u.endsWith(".mpd") -> "lista-dash"
            u.endsWith(".ts") || u.endsWith(".m4s") || Extract.isSegmentUrl(r.url) -> "pedaço"
            u.endsWith(".key") || Regex("""/keys?/|/drm/|license""").containsMatchIn(u) -> "chave"
            u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m4v") || u.endsWith(".mov") -> "arquivo-vídeo"
            r.main || dest == "document" -> "página"
            dest == "iframe" -> "quadro"
            dest == "video" || dest == "audio" -> "mídia"
            u.endsWith(".js") || dest == "script" -> "script"
            Regex("""\.(png|jpe?g|gif|webp|svg|ico|avif)$""").containsMatchIn(u) || dest == "image" -> "imagem"
            u.endsWith(".css") || dest == "style" || Regex("""\.(woff2?|ttf|otf)$""").containsMatchIn(u) || dest == "font" -> "estilo"
            dest == "empty" -> "api"
            else -> "outro"
        }
    }

    private fun gz(s: String): String {
        val bo = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bo).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    data class Result(val code: String, val summary: String)

    // ============================================================ montar o código
    suspend fun build(web: WebView, page: String, log: (String) -> Unit): Result {
        val a = Anon(page)
        val out = JSONObject().put("v", 1).put("app", br.geracaoai.baixartg.BuildConfig.VERSION_NAME).put("pagina", a.url(page))
        val all = reqs.toList()
        log("Lendo os players e quadros…")
        // quadros, players, <video>
        val scans = try { DetBridge.ask(web, "scan", 2_500) } catch (_: Exception) { emptyList() }
        val frames = LinkedHashSet<String>()
        val jf = JSONArray()
        for (o in scans) {
            val f = o.optString("f"); if (f.startsWith("http")) frames += f
            val j = JSONObject().put("quadro", if (o.optInt("top") == 1) "principal" else a.url(f))
            o.optJSONArray("players")?.let { j.put("players", it) }
            o.optJSONArray("videos")?.let { vs -> val ja = JSONArray(); for (i in 0 until minOf(vs.length(), 6)) { val v = vs.optJSONObject(i) ?: continue
                ja.put(JSONObject().put("src", if (v.optInt("blob") == 1) "blob (MSE)" else if (v.optString("s").startsWith("http")) a.url(v.optString("s")) else "vazio")
                    .put("tam", "${v.optInt("w")}x${v.optInt("h")}").put("dur", v.optInt("d"))) }; j.put("videos", ja) }
            o.optJSONArray("iframes")?.let { fs -> val ja = JSONArray(); for (i in 0 until minOf(fs.length(), 12)) { val x = fs.optJSONObject(i) ?: continue
                val s = x.optString("s").ifBlank { x.optString("ds") }; if (s.startsWith("http")) ja.put(a.url(s) + " ${x.optInt("w")}x${x.optInt("h")}") }; j.put("iframes", ja) }
            if (o.optString("drm").isNotBlank()) j.put("drm_pedido", o.optString("drm"))
            if (o.optInt("mse") == 1) j.put("mse", true)
            j.put("senha_visivel", o.optInt("pw"))
            jf.put(j)
        }
        out.put("quadros", jf)
        all.filter { it.main || it.hdrs.entries.any { e -> e.key.equals("sec-fetch-dest", true) && e.value == "iframe" } }.forEach { frames += it.url }

        // pedidos (agrupados pelo formato)
        log("Organizando ${all.size} pedidos…")
        data class G(val shape: String, val ty: String, var n: Int, val first: Req)
        val groups = LinkedHashMap<String, G>()
        for (r in all) {
            val ty = type(r)
            if (ty in setOf("imagem", "estilo")) continue
            if (ty == "script" && !Regex("""(?i)player|hls|dash|shaka|video|vimeo|panda|jw|plyr|clappr|bitmovin|stream""").containsMatchIn(r.url)) continue
            val shape = a.url(r.url)
            val k = ty + "|" + shape.replace(Regex("""\{(?:n|tk|hex)\d+#\d+\}"""), "{*}")
            val g = groups[k]
            if (g == null) groups[k] = G(shape, ty, 1, r) else g.n++
        }
        val order = listOf("página", "quadro", "lista-hls", "lista-dash", "chave", "arquivo-vídeo", "mídia", "pedaço", "api", "script", "outro")
        val jr = JSONArray()
        groups.values.sortedWith(compareBy({ order.indexOf(it.ty).let { i -> if (i < 0) 99 else i } }, { it.first.t })).take(160).forEach { g ->
            val r = g.first
            val custom = r.hdrs.keys.filter { it.lowercase() !in STD_HDR }
            val rng = r.hdrs.entries.firstOrNull { it.key.equals("range", true) }?.value
            jr.put(JSONObject().put("tipo", g.ty).put("end", g.shape).put("vezes", g.n).put("ms", r.t).put("metodo", r.method)
                .put("origem", a.refRole(r.hdrs.entries.firstOrNull { it.key.equals("referer", true) }?.value, page, frames))
                .apply { if (custom.isNotEmpty()) put("cabecalhos_especiais", JSONArray(custom)); if (rng != null) put("range", if (rng.startsWith("bytes=0-")) "início" else "meio") }
                .put("via", r.src))
        }
        out.put("pedidos", jr)

        // respostas lidas pelo agente
        val jn = JSONArray()
        DetBridge.netSince(t0).take(40).forEach { n ->
            val b = n.body.trimStart()
            val desc = when {
                b.startsWith("#EXTM3U") -> DetScan.hlsInfo(b).let { i -> if (i.master) "hls principal: ${i.variants} qualidades até ${i.maxHeight}p" else "hls da qualidade: ${Regex("#EXTINF").findAll(b).count()} pedaços" + (if (i.aes) ", AES" else "") + (if (i.drm) ", DRM" else "") }
                b.startsWith("{") || b.startsWith("[") -> DetScan.urlsIn(b, n.url, 30, withImages = false, deep = false).let { hs -> "json com ${hs.size} endereço(s) de vídeo" + if (hs.isNotEmpty()) ": " + hs.take(4).joinToString(" | ") { a.url(it.url) } else "" }
                else -> "texto"
            }
            jn.put(a.url(n.url) + " → " + desc)
        }
        out.put("respostas", jn)

        // cookies (só nomes)
        val jc = JSONObject()
        (setOf(page) + frames + all.filter { type(it) in setOf("lista-hls", "pedaço", "chave", "arquivo-vídeo") }.map { it.url }.take(6)).forEach { u ->
            val c = try { CookieManager.getInstance().getCookie(u) } catch (_: Exception) { null }
            val names = c?.split(';')?.mapNotNull { it.trim().substringBefore('=').ifBlank { null } }?.distinct().orEmpty()
            jc.put(a.hostLabel(a.host(u)), JSONArray(names.take(20)))
        }
        out.put("cookies", jc)

        // testes: cada pedido importante do jeito do app
        log("Testando os pedidos do vídeo do jeito do app…")
        val keyReqs = ArrayList<Req>()
        listOf("lista-hls", "lista-dash", "chave", "arquivo-vídeo", "mídia", "pedaço").forEach { ty -> keyReqs += all.filter { type(it) == ty }.distinctBy { a.url(it.url).replace(Regex("""\{(?:n|tk|hex)\d+#\d+\}"""), "{*}") }.take(if (ty == "lista-hls") 3 else 2) }
        val jt = JSONArray()
        for (r in keyReqs.take(12)) {
            val shape = a.url(r.url)
            val modes = linkedMapOf<String, Pair<Map<String, String>, String?>>(
                "igual ao navegador" to (r.hdrs to r.hdrs.entries.firstOrNull { it.key.equals("referer", true) }?.value),
                "origem = página" to (emptyMap<String, String>() to page),
                "sem origem" to (emptyMap<String, String>() to ""),
            )
            val jm = JSONObject()
            for ((name, mh) in modes) {
                val res = test(r.url, mh.first, mh.second)
                jm.put(name, res)
            }
            jt.put(JSONObject().put("end", shape).put("tipo", type(r)).put("resultado", jm))
        }
        out.put("testes", jt)

        // o caminho do app: origem que ele escolheria e qualidade
        val master = all.firstOrNull { type(it) == "lista-hls" }
        if (master != null) {
            val f = Found(master.url, page, "", Kind.HLS, ref = master.hdrs.entries.firstOrNull { it.key.equals("referer", true) }?.value ?: page)
            val pr = withTimeoutOrNull(40_000) { Downloader.pickReferer(f) }
            out.put("app_origem_escolhida", when (pr) { null -> "nenhuma abriu"; "" -> "nenhuma"; page -> "página"; else -> a.refRole(pr, page, frames) })
        }
        out.put("pedidos_total", all.size)

        val json = out.toString()
        val code = "BPT1:" + gz(json)
        val sum = buildString {
            appendLine("${all.size} pedidos gravados • ${groups.values.count { it.ty == "lista-hls" }} lista(s) HLS • ${groups.values.count { it.ty == "pedaço" }} tipo(s) de pedaço • ${groups.values.count { it.ty == "chave" }} chave(s) • ${groups.values.count { it.ty == "arquivo-vídeo" }} arquivo(s) de vídeo")
            append("players: " + scans.flatMap { o -> o.optJSONArray("players")?.let { p -> (0 until p.length()).map { p.optString(it) } }.orEmpty() }.distinct().joinToString().ifBlank { "nenhum conhecido" })
        }
        return Result(code, sum)
    }

    private suspend fun test(url: String, hdrs: Map<String, String>, ref: String?): String = withContext(Dispatchers.IO) {
        withTimeoutOrNull(12_000) {
            try {
                val req = if (hdrs.isNotEmpty()) MseDownloader.buildRequest(url, hdrs, ref) else Request.Builder().url(url).header("Accept", "*/*").header("Range", "bytes=0-65535").apply {
                    if (!ref.isNullOrBlank()) { header("Referer", ref); Net.originOf(ref)?.let { header("Origin", it.trimEnd('/')) } } else header("Referer", "")
                }.build()
                Net.fast.newCall(req).await().use { r ->
                    val head = r.body?.byteStream()?.use { s -> val buf = ByteArray(65_536); var n = 0; while (n < buf.size) { val k = s.read(buf, n, buf.size - n); if (k < 0) break; n += k }; buf.copyOf(n) } ?: ByteArray(0)
                    val sn = DetScan.sniff(head, r.header("Content-Type"))
                    "HTTP ${r.code} → ${sn ?: "?"}" + if (sn == "hls") DetScan.hlsInfo(String(head, Charsets.UTF_8)).let { i -> if (i.master) " (principal até ${i.maxHeight}p)" else " (qualidade)" } else ""
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "erro: ${e.javaClass.simpleName}" }
        } ?: "sem resposta (12 s)"
    }
}
