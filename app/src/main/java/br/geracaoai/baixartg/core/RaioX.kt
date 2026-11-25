package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Raio-X do site: faz no celular a mesma investigação que seria feita com o link na mão, e devolve só códigos.
 *
 * 1. A página antes do play (players, <video>, iframes, configs no código, proteções, fotos).
 * 2. Grava tudo o que o navegador pede enquanto o vídeo toca (lista HLS/DASH, MP4, pedaços, APIs, chave, licença DRM).
 * 3. Pega o pedido de vídeo principal e REPETE fora do navegador tirando uma peça de cada vez
 *    (cookie, Referer, cabeçalhos do player, navegador, Range) e com Origin — descobre exatamente o que o servidor exige.
 * 4. Lê a resposta (CORS, partes, tamanho, CDN), a validade do link e repete depois de ~40 s (o link vence?).
 * 5. Testa o caminho do próprio app (HTML sem navegador, servidor yt-dlp) e as maiores fotos da página.
 *
 * O relatório nunca tem link, nome de site, título, valores de cookie/token nem login: sites e servidores viram códigos.
 */
object RaioX {
    data class Req(val url: String, val method: String, val headers: Map<String, String>, val main: Boolean, val at: Long, val src: String)

    @Volatile var recording = false
        private set
    private val reqs = ConcurrentLinkedQueue<Req>()

    /** Chamado pelo WebView do Navegador para cada pedido (barato quando não está gravando). */
    fun onRequest(url: String, method: String?, headers: Map<String, String>?, main: Boolean) {
        if (!recording || !url.startsWith("http", true) || reqs.size > 4000) return
        reqs += Req(url, method ?: "GET", headers.orEmpty(), main, System.currentTimeMillis(), "rede")
    }

    fun start() { reqs.clear(); recording = true }
    fun stop(): List<Req> { recording = false; return reqs.toList() }

    // ---------- classificação ----------
    enum class T(val label: String) { HLS("lista HLS (m3u8)"), DASH("lista DASH (mpd)"), VIDEO("vídeo direto"), SEG("pedaço"), KEY("chave"), LICENSE("licença DRM"), API("API/JSON"), IMAGE("imagem"), PAGE("página"), OTHER("outro") }

    private val KEY_RE = Regex("""(?i)(\.key(?:$|[?#])|/key[s]?/|[?&]key=|aes)""")
    private val LIC_RE = Regex("""(?i)(widevine|playready|fairplay|/license|/drm/|getlicense|lic\?)""")
    private val API_RE = Regex("""(?i)(/api/|/ajax|\.json(?:$|[?#])|graphql|/player/|/embed/|/video[s]?/[^/]*\?|/source[s]?\b|/stream[s]?\b|get_?file|/media/)""")
    private val IMG_RE = Regex("""(?i)\.(jpe?g|png|webp|gif|avif|bmp)(?:$|[?#])""")
    private val SKIP_RE = Regex("""(?i)\.(css|woff2?|ttf|otf|eot|svg|ico)(?:$|[?#])|google-analytics|googletagmanager|doubleclick|facebook\.net|/collect\?|/pixel|/beacon""")

    fun classify(r: Req): T {
        val u = r.url.substringBefore('#')
        val acc = r.headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
        return when {
            r.main -> T.PAGE
            Extract.isHls(u) -> T.HLS
            Extract.isDash(u) -> T.DASH
            LIC_RE.containsMatchIn(u) -> T.LICENSE
            Extract.isSegmentUrl(u) -> T.SEG
            KEY_RE.containsMatchIn(u) && !IMG_RE.containsMatchIn(u) && !Extract.isVideoUrl(u) -> T.KEY
            Extract.isVideoUrl(u) || Extract.mediaKindOf(u) == Kind.DIRECT -> T.VIDEO
            IMG_RE.containsMatchIn(u) || acc.startsWith("image/", true) -> T.IMAGE
            SKIP_RE.containsMatchIn(u) -> T.OTHER
            acc.contains("json", true) || API_RE.containsMatchIn(u) -> T.API
            else -> T.OTHER
        }
    }

    // ---------- JS de leitura da página (só leitura, devolve dados técnicos) ----------
    const val SNAP_JS = """(function(){try{
var q=function(s){try{return document.querySelectorAll(s)}catch(e){return []}};
var o={};
var P=[['jwplayer','jwplayer'],['videojs','videojs'],['Hls','hls.js'],['shaka','shaka'],['Plyr','plyr'],['flowplayer','flowplayer'],['Clappr','clappr'],['dashjs','dash.js'],['MediaElementPlayer','mediaelement'],['fluidPlayer','fluidplayer'],['Artplayer','artplayer'],['DPlayer','dplayer'],['bitmovin','bitmovin'],['THEOplayer','theoplayer'],['YT','youtube-api'],['Vimeo','vimeo-api']];
o.players=[];P.forEach(function(p){try{if(window[p[0]])o.players.push(p[1])}catch(e){}});
try{if(window.flashvars&&(window.flashvars.video_url||window.flashvars.video_alt_url))o.players.push('kvs')}catch(e){}
if(q('media-player,mux-player,video-js').length)o.players.push('web-component');
var err={1:'abortado',2:'rede',3:'decodificação',4:'fonte não suportada'};
o.videos=[];q('video').forEach(function(v,i){if(i>5)return;var s=v.currentSrc||v.src||'';
o.videos.push({src:s.indexOf('blob:')===0?'blob':s.indexOf('http')===0?'http':s?'outro':'nenhuma',url:s.indexOf('http')===0?s:'',rs:v.readyState,ns:v.networkState,paused:v.paused,t:Math.round(v.currentTime||0),d:isFinite(v.duration)?Math.round(v.duration):-1,w:v.videoWidth||0,h:v.videoHeight||0,err:v.error?(err[v.error.code]||v.error.code):'',drm:!!v.mediaKeys,sources:v.querySelectorAll('source').length,poster:!!v.poster,cors:v.crossOrigin||''})});
o.iframes=[];q('iframe').forEach(function(f){var r=f.getBoundingClientRect();if(o.iframes.length<8&&/^https?:/i.test(f.src||''))o.iframes.push({u:f.src,big:r.width>=200&&r.height>=120})});
o.og=!!document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
o.ld=0;q('script[type="application/ld+json"]').forEach(function(s){if(/VideoObject/i.test(s.textContent||''))o.ld++});
var txt='';q('script:not([src])').forEach(function(s){if(txt.length<2000000)txt+=(s.textContent||'')+'\n'});
function c(re){var m=txt.match(re);return m?m.length:0}
o.code={m3u8:c(/\.m3u8/gi),mp4:c(/\.mp4/gi),mpd:c(/\.mpd/gi),file:c(/["']?file["']?\s*:/gi),sources:c(/sources\s*[:=]\s*\[/gi),setup:c(/\.setup\s*\(/g),atob:c(/atob\(/g),evalpack:c(/eval\(function\(p,a,c,k,e/g)};
o.ext=q('script[src]').length;
var body=((document.body&&document.body.innerText)||'').slice(0,30000);
o.login=0;q('input[type=password]').forEach(function(p){var r=p.getBoundingClientRect();if(r.width>0&&r.height>0)o.login++});
o.age=/(verifica[cç][aã]o de idade|maior de 18|tenho 18|are you 18|over 18|age verification|i am 18|18\+)/i.test(body);
o.cf=/just a moment|attention required|checking your browser|verify you are human|um momento/i.test(document.title||'')||!!document.querySelector('#challenge-form,#challenge-stage,[name=cf-turnstile-response],.g-recaptcha,.h-captcha');
o.consent=/cookie|consentimento|lgpd|gdpr|consent/i.test(body.slice(0,6000));
o.paywall=/(assine|assinatura|subscribe|premium|members only|somente membros|faça login para|sign in to watch|login to watch)/i.test(body);
var im=q('img'),lazy=0,srcset=0,top=[];
im.forEach(function(x){if(x.getAttribute('data-src')||x.getAttribute('data-lazy-src')||x.getAttribute('data-original')||x.loading==='lazy')lazy++;if(x.srcset)srcset++;
var w=x.naturalWidth||0,h=x.naturalHeight||0,s=x.currentSrc||x.src||'';if(/^https?:/i.test(s)&&w*h>0)top.push({u:s,w:w,h:h})});
top.sort(function(a,b){return b.w*b.h-a.w*a.h});
var bg=0;q('[style*="background"]').forEach(function(e){if(/url\(/i.test(e.getAttribute('style')||''))bg++});
var links=0;q('a[href]').forEach(function(a){if(/\.(jpe?g|png|webp|gif)(\?|$)/i.test(a.href))links++});
var sizes={p:0,m:0,g:0};top.forEach(function(t){var L=Math.max(t.w,t.h);if(L<400)sizes.p++;else if(L<1000)sizes.m++;else sizes.g++});
o.imgs={n:im.length,lazy:lazy,srcset:srcset,bg:bg,links:links,sizes:sizes,top:top.slice(0,6)};
o.mse=window.__bptMseActive?1:0;
return JSON.stringify(o);
}catch(e){return JSON.stringify({erro:String(e)})}})()"""

    /** Tudo que a página baixou desde que abriu (inclusive antes da gravação), pelo próprio navegador. */
    const val PERF_JS = """(function(){try{var a=[];performance.getEntriesByType('resource').forEach(function(e){if(a.length<600)a.push({u:(e.name||'').slice(0,800),t:e.initiatorType||'',s:e.transferSize||0,b:e.decodedBodySize||0})});return JSON.stringify(a)}catch(e){return '[]'}})()"""

    // ---------- teste fora do navegador ----------
    /** Cliente "limpo": sem os cookies automáticos do app, para o teste controlar cada peça. */
    private val bare: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    data class Res(val code: Int, val ct: String, val sniff: String?, val len: Long, val acao: String, val ranges: String, val server: String,
                   val setCookie: Int, val body: ByteArray, val err: String = "") {
        val ok get() = code in 200..299 && (DetScan.isVideoSniff(sniff) || ct.startsWith("video/", true) || ct.contains("mpegurl", true) || ct.contains("dash", true))
        fun line(): String = if (code < 0) "erro de conexão ($err)" else
            "HTTP $code ${ct.ifBlank { "?" }} → ${sniff ?: "?"}${if (len > 0) " • ${fmtSize(len)}" else ""}"
    }

    private suspend fun hit(url: String, headers: Map<String, String>, max: Int = 65_536): Res = withContext(Dispatchers.IO) {
        withTimeoutOrNull(16_000) {
            try {
                val b = Request.Builder().url(url)
                for ((k, v) in headers) if (v.isNotEmpty()) try { b.header(k, v) } catch (_: Exception) {}
                bare.newCall(b.build()).await().use { r ->
                    val out = java.io.ByteArrayOutputStream()
                    try { r.body?.byteStream()?.use { ins -> val buf = ByteArray(16_384); while (out.size() < max) { val n = ins.read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) } } } catch (_: Exception) {}
                    val bytes = out.toByteArray()
                    val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: r.header("Content-Length")?.toLongOrNull() ?: -1L
                    val srv = listOfNotNull(r.header("Server"), r.header("cf-ray")?.let { "cloudflare" }, r.header("x-amz-cf-id")?.let { "cloudfront" },
                        r.header("x-bunny-cache")?.let { "bunny" }, r.header("x-fastly-request-id")?.let { "fastly" }, r.header("akamai-grn")?.let { "akamai" })
                        .joinToString("/") { it.lowercase().replace(Regex("[^a-z0-9./ -]"), "").take(20) }
                    Res(r.code, r.header("Content-Type").orEmpty().substringBefore(';').trim(), DetScan.sniff(bytes, r.header("Content-Type")), total,
                        r.header("Access-Control-Allow-Origin").orEmpty().let { if (it == "*") "*" else if (it.isBlank()) "" else "site específico" },
                        r.header("Accept-Ranges").orEmpty(), srv, r.headers("Set-Cookie").size, bytes)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; Res(-1, "", null, -1, "", "", "", 0, ByteArray(0), e.javaClass.simpleName) }
        } ?: Res(-1, "", null, -1, "", "", "", 0, ByteArray(0), "sem resposta em 16 s")
    }

    private fun fmtSize(n: Long) = when { n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9); n >= 1_000_000 -> "%.1f MB".format(n / 1e6); n >= 1_000 -> "${n / 1000} KB"; else -> "$n B" }
    private fun tag(u: String?, kind: String) = if (u.isNullOrBlank()) "?" else SiteHash.short(kind, u)
    private val STD = setOf("user-agent", "accept", "accept-language", "accept-encoding", "referer", "origin", "range", "cookie", "connection", "host",
        "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "upgrade-insecure-requests",
        "x-requested-with", "cache-control", "pragma", "if-none-match", "if-modified-since", "dnt", "priority")

    private fun hdr(m: Map<String, String>, k: String) = m.entries.firstOrNull { it.key.equals(k, true) }?.value

    /** Pedido idêntico ao do player (cabeçalhos que o navegador mostrou + cookies do navegador). */
    private fun baseline(url: String, playerHeaders: Map<String, String>, page: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((k, v) in playerHeaders) if (!k.equals("Cookie", true) && !k.equals("Host", true) && !k.equals("Content-Length", true)) m[k] = v
        if (hdr(m, "User-Agent") == null) m["User-Agent"] = Net.userAgent
        if (hdr(m, "Accept") == null) m["Accept"] = "*/*"
        if (hdr(m, "Referer") == null && page.startsWith("http")) m["Referer"] = page
        val ck = try { Net.cookies?.cookieFor(url) } catch (_: Exception) { null }
        if (!ck.isNullOrBlank()) m["Cookie"] = ck
        if (hdr(m, "Range") == null) m["Range"] = "bytes=0-65535"
        return m
    }

    private fun without(m: Map<String, String>, vararg keys: String) = LinkedHashMap(m.filterKeys { k -> keys.none { it.equals(k, true) } })
    private fun withH(m: Map<String, String>, k: String, v: String) = LinkedHashMap(without(m, k)).also { it[k] = v }

    // ---------- análise completa ----------
    fun interface Progress { fun step(s: String) }

    suspend fun analyze(page: String, eval: suspend (String) -> String?, tapPlay: suspend () -> Unit, progress: Progress, waitTokenTest: Boolean = true): String {
        val sb = StringBuilder()
        val verdict = ArrayList<String>()
        val started = System.currentTimeMillis()
        sb.appendLine("=== RAIO-X === app v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME} • página ${tag(page, "site")}")

        // 1) página antes do play
        progress.step("Lendo a página…")
        val before = parse(eval(SNAP_JS))
        sb.appendLine()
        sb.appendLine("[1] PÁGINA ANTES DO PLAY")
        describePage(before, page, sb, verdict)

        // 2) grava a rede enquanto o player toca
        start()
        val perfBefore = try { JSONArray(eval(PERF_JS) ?: "[]") } catch (_: Exception) { JSONArray() }
        try {
            progress.step("Dando play e gravando o que o player pede…")
            tapPlay()
            for (i in 1..14) {
                delay(1_000)
                if (i == 3 || i == 7) tapPlay()
                progress.step("Gravando o que o player pede… ${i}/14 s")
            }
        } finally { stop() }
        val net = reqs.toList()
        val after = parse(eval(SNAP_JS))
        val perfAfter = try { JSONArray(eval(PERF_JS) ?: "[]") } catch (_: Exception) { JSONArray() }
        val perfUrls = LinkedHashMap<String, String>()
        for (arr in listOf(perfBefore, perfAfter)) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> val u = o.optString("u"); if (u.startsWith("http")) perfUrls[u] = o.optString("t") }
        val all = LinkedHashMap<String, Req>()
        for (r in net) if (!all.containsKey(r.url)) all[r.url] = r
        for ((u, t) in perfUrls) if (!all.containsKey(u)) all[u] = Req(u, "GET", emptyMap(), false, 0L, "navegador($t)")

        sb.appendLine()
        sb.appendLine("[2] DEPOIS DO PLAY (o que o navegador pediu)")
        sb.appendLine("pedidos gravados: ${net.size} pela rede do app + ${perfUrls.size} pela lista do navegador")
        val byType = all.values.groupBy { classify(it) }
        for (t in T.values()) {
            val l = byType[t].orEmpty(); if (l.isEmpty() || t == T.OTHER) continue
            val hosts = l.groupingBy { tag(it.url, "cdn") }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key}×${it.value}" }
            sb.appendLine("  ${t.label}: ${l.size} • em $hosts")
        }
        describeVideos(after, sb, verdict)
        if (after.optInt("mse") == 1 || after.optJSONArray("videos")?.let { a -> (0 until a.length()).any { a.optJSONObject(it)?.optString("src") == "blob" } } == true)
            sb.appendLine("o player monta o vídeo por blob (MSE): o arquivo chega em pedaços, não num link único")
        val apis = byType[T.API].orEmpty()
        if (apis.isNotEmpty()) sb.appendLine("APIs chamadas (podem devolver o link do vídeo): " + apis.take(6).joinToString(", ") { tag(it.url, "api") + shapeShort(it.url) })
        if (byType[T.LICENSE].orEmpty().isNotEmpty()) verdict += "o vídeo usa DRM (pedido de licença visto): não dá para baixar o arquivo, só gravar a tela"

        // 3) pedido principal e teste peça por peça
        val cands = (byType[T.HLS].orEmpty() + byType[T.DASH].orEmpty() + byType[T.VIDEO].orEmpty())
            .filter { !MediaPick.isAdUrl(it.url) && !Extract.isPreviewVideo(it.url) }
        val pickedUrl = MediaPick.pick(cands.map { MediaPick.Cand(it.url, pageUrl = page) })?.url
        val main = cands.firstOrNull { it.url == pickedUrl }
            ?: cands.firstOrNull { classify(it) == T.HLS } ?: cands.firstOrNull { classify(it) == T.DASH } ?: cands.firstOrNull()
            ?: after.optJSONArray("videos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } }.firstOrNull() }
                ?.let { Req(it, "GET", emptyMap(), false, 0L, "<video>") }
        sb.appendLine()
        sb.appendLine("[3] PEDIDO DE VÍDEO PRINCIPAL")
        if (main == null) {
            sb.appendLine("nenhum pedido de vídeo/lista foi visto em 14 s de play")
            if (byType[T.SEG].orEmpty().isNotEmpty()) sb.appendLine("…mas houve ${byType[T.SEG]!!.size} pedaços: a lista deve vir por API/JS (ou service worker)")
            verdict += if ((after.optJSONArray("iframes")?.length() ?: 0) > 0) "o player fica dentro de um iframe de outro site: abra o vídeo do iframe (ou o detetive) e rode o Raio-X lá"
            else "o player não pediu vídeo nenhum (talvez precise de toque real no play, login, idade ou o vídeo é de outro jeito)"
        } else {
            val t = classify(main)
            val ph = main.headers
            sb.appendLine("tipo: ${t.label} • servidor ${tag(main.url, "cdn")} • visto por: ${main.src} • ${if (Trail.host(main.url) == Trail.host(page)) "mesmo site da página" else "servidor diferente da página"}")
            sb.appendLine("cabeçalhos que o player mandou: " + (if (ph.isEmpty()) "(não capturados — o pedido veio pela lista do navegador)" else ph.keys.map { it.lowercase() }.sorted().joinToString(", ")))
            val extra = ph.keys.filter { it.lowercase() !in STD }
            if (extra.isNotEmpty()) sb.appendLine("  cabeçalhos ESPECIAIS do player: ${extra.joinToString(", ") { it.lowercase() }}")
            hdr(ph, "Referer")?.let { sb.appendLine("  Referer do player: ${refKind(it, page, main.url)}") }
            sb.appendLine("  Origin no pedido do player: ${if (hdr(ph, "Origin") != null) "sim" else "não"} • Range: ${hdr(ph, "Range") ?: "não"}")
            val ckCount = (try { Net.cookies?.cookieFor(main.url) } catch (_: Exception) { null }).orEmpty().split(';').count { it.isNotBlank() }
            sb.appendLine("cookies do navegador para esse servidor: $ckCount")
            sb.appendLine("forma do link: " + shape(main.url))

            progress.step("Repetindo o pedido do player, tirando uma peça de cada vez…")
            val base = baseline(main.url, ph, page)
            val tests = linkedMapOf(
                "igual ao player" to base,
                "sem cookie" to without(base, "Cookie"),
                "sem Referer" to without(base, "Referer"),
                "com Origin" to withH(base, "Origin", (Net.originOf(hdr(base, "Referer") ?: page) ?: page).trimEnd('/')),
                "sem cabeçalhos especiais" to LinkedHashMap(base.filterKeys { it.lowercase() in STD }),
                "navegador de computador" to withH(base, "User-Agent", DESKTOP_UA),
                "sem Range" to without(base, "Range"),
                "como o app baixa (Referer=página, sem extras)" to LinkedHashMap<String, String>().apply {
                    put("User-Agent", Net.userAgent); put("Accept", "*/*"); put("Referer", page); put("Range", "bytes=0-65535")
                    hdr(base, "Cookie")?.let { put("Cookie", it) }
                },
                "nada (sem cookie, sem Referer)" to without(base, "Cookie", "Referer", "Origin"),
            )
            val res = LinkedHashMap<String, Res>()
            sb.appendLine("teste fora do navegador (o que mudou → resposta):")
            for ((name, h) in tests) {
                val r = hit(main.url, h)
                res[name] = r
                sb.appendLine("  ${if (r.ok) "✔" else "✘"} $name: ${r.line()}")
            }
            val b0 = res["igual ao player"]!!
            sb.appendLine("resposta do servidor: CDN ${b0.server.ifBlank { "?" }} • CORS ${b0.acao.ifBlank { "não libera" }} • partes (Range) ${if (b0.code == 206 || b0.ranges.contains("bytes")) "aceita" else "não"} • manda cookie novo: ${b0.setCookie}")
            if (!b0.ok) errText(b0)?.let { sb.appendLine("texto do erro: $it") }

            // veredito do teste
            if (b0.ok) {
                val need = ArrayList<String>()
                if (res["sem cookie"]?.ok == false) need += "o COOKIE do site"
                if (res["sem Referer"]?.ok == false) need += "o REFERER (página de origem)"
                if (res["sem cabeçalhos especiais"]?.ok == false) need += "os cabeçalhos especiais do player (${extra.joinToString(", ") { it.lowercase() }})"
                if (res["navegador de computador"]?.ok == false) need += "o mesmo navegador (User-Agent)"
                if (res["sem Range"]?.ok == false) need += "o pedido em partes (Range)"
                verdict += if (need.isEmpty()) "o servidor do vídeo NÃO exige nada especial: o link baixa direto" else "o servidor exige: ${need.joinToString(" + ")}"
                if (res["com Origin"]?.ok == false) verdict += "mandar o cabeçalho Origin FAZ o servidor recusar (o app já não manda desde a v1.9.37)"
                if (res["como o app baixa (Referer=página, sem extras)"]?.ok == false) verdict += "do jeito que o app pede hoje NÃO funciona: precisa copiar o pedido do player (corrigir no app)"
            } else {
                verdict += when {
                    res.values.any { it.ok } -> "só funciona com: " + res.filter { it.value.ok }.keys.joinToString(" / ")
                    b0.code == 403 || b0.code == 401 -> "nem o pedido idêntico ao do player funciona fora do navegador (HTTP ${b0.code}): link de uso único, preso à sessão do navegador ou ao 'jeito' do navegador (TLS). Caminho: baixar dentro do navegador"
                    b0.code == 404 || b0.code == 410 -> "o link já não existe (HTTP ${b0.code}): vence muito rápido ou é de uso único"
                    b0.code < 0 -> "o servidor do vídeo não respondeu ao app (${b0.err})"
                    else -> "o servidor responde HTTP ${b0.code} sem vídeo"
                }
            }

            // HLS: lista da qualidade, chave e pedaços
            if (t == T.HLS && b0.ok) hlsDeep(main.url, base, sb, verdict)

            // 4) validade: repete depois de um tempo
            val exp = LinkExpiry.expiresAt(main.url)
            if (exp != null) sb.appendLine("validade no link: vence em ${(exp - System.currentTimeMillis()) / 1000} s")
            if (waitTokenTest && b0.ok) {
                for (s in 40 downTo 1) { if (s % 5 == 0) progress.step("Esperando ${s}s para ver se o link vence…"); delay(1_000) }
                val again = hit(main.url, base)
                sb.appendLine("o mesmo pedido ~40 s depois: ${if (again.ok) "✔" else "✘"} ${again.line()}")
                if (!again.ok) verdict += "o link VENCE em menos de 1 minuto: o app precisa pegar o link e baixar na hora"
                val again2 = hit(main.url, base)
                if (again.ok && !again2.ok) verdict += "o link parece de USO LIMITADO (falhou ao repetir)"
            }
        }

        // 5) caminho do próprio app
        sb.appendLine()
        sb.appendLine("[4] CAMINHO DO APP")
        progress.step("Testando o caminho do app…")
        val html = try { withTimeoutOrNull(15_000) { PageResolver.fetch(page, null) } } catch (e: Exception) { if (e is CancellationException) throw e; null }
        if (html == null) sb.appendLine("HTML sem navegador: não abriu (bloqueio, anti-robô ou login)")
        else {
            val pr = try { Extract.parse(page, html) } catch (e: Exception) { if (e is CancellationException) throw e; null }
            val v = pr?.videos.orEmpty()
            sb.appendLine("HTML sem navegador: ${html.length / 1000} KB • vídeos achados sem navegador: ${v.size}" + (if (v.isNotEmpty()) " (" + v.groupingBy { it.kind.name }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" } + ")" else ""))
            if (v.isEmpty() && main != null) sb.appendLine("  → o link só aparece depois do play/JS (o app precisa do navegador para achar)")
        }
        sb.appendLine("servidor yt-dlp: ${Downloader.ServerHealth.status()}")
        if (!Downloader.ServerHealth.isDown()) {
            val pt = try { withTimeoutOrNull(30_000) { Downloader.probe(br.geracaoai.baixartg.Prefs.server, page) } } catch (e: Exception) { if (e is CancellationException) throw e; null }
            sb.appendLine("  reconhece a página: " + if (pt != null) "sim" else "não (" + FailDoctor.anon(Downloader.lastProbeError ?: "sem resposta").take(100) + ")")
        }

        // 6) fotos
        sb.appendLine()
        sb.appendLine("[5] FOTOS")
        photos(after.optJSONObject("imgs") ?: before.optJSONObject("imgs"), page, sb, verdict)

        sb.appendLine()
        sb.appendLine("VEREDITO:")
        if (verdict.isEmpty()) sb.appendLine("- nada conclusivo; mande este relatório")
        verdict.distinct().forEach { sb.appendLine("- $it") }
        sb.appendLine("(Raio-X levou ${(System.currentTimeMillis() - started) / 1000} s)")
        return FailDoctor.anon(sb.toString().trimEnd())
    }

    private fun parse(s: String?): JSONObject {
        if (s.isNullOrBlank()) return JSONObject()
        return try { JSONObject(s) } catch (_: Exception) {
            try { JSONObject(org.json.JSONTokener(s).nextValue() as String) } catch (_: Exception) { JSONObject() }
        }
    }

    private fun describePage(o: JSONObject, page: String, sb: StringBuilder, verdict: MutableList<String>) {
        if (o.length() == 0) { sb.appendLine("não consegui ler a página (JavaScript bloqueado ou página ainda carregando)"); return }
        val pl = o.optJSONArray("players")
        sb.appendLine("players: " + (if (pl == null || pl.length() == 0) "nenhum conhecido" else (0 until pl.length()).joinToString(", ") { pl.optString(it) }))
        val vs = o.optJSONArray("videos")
        sb.appendLine("<video> na página: ${vs?.length() ?: 0}")
        val ifr = o.optJSONArray("iframes")
        if (ifr != null && ifr.length() > 0) sb.appendLine("iframes: " + (0 until ifr.length()).mapNotNull { ifr.optJSONObject(it) }.joinToString(", ") {
            val u = it.optString("u"); tag(u, "emb") + (if (Trail.host(u) == Trail.host(page)) "(mesmo site)" else "") + (if (it.optBoolean("big")) "[grande]" else "")
        })
        sb.appendLine("og:video: ${if (o.optBoolean("og")) "sim" else "não"} • JSON-LD de vídeo: ${o.optInt("ld")}")
        o.optJSONObject("code")?.let { c -> sb.appendLine("no código da página: .m3u8×${c.optInt("m3u8")} .mp4×${c.optInt("mp4")} .mpd×${c.optInt("mpd")} file:×${c.optInt("file")} sources:[×${c.optInt("sources")} setup()×${c.optInt("setup")} atob()×${c.optInt("atob")} código empacotado×${c.optInt("evalpack")} • scripts externos ${o.optInt("ext")}") }
        val prot = ArrayList<String>()
        if (o.optInt("login") > 0) prot += "campo de senha (login)"
        if (o.optBoolean("age")) prot += "aviso de idade"
        if (o.optBoolean("cf")) prot += "verificação anti-robô"
        if (o.optBoolean("paywall")) prot += "texto de assinatura/login para assistir"
        if (o.optBoolean("consent")) prot += "aviso de cookies"
        sb.appendLine("proteções/avisos: " + prot.ifEmpty { listOf("nenhum") }.joinToString(", "))
        if (o.optBoolean("cf")) verdict += "a página está numa verificação anti-robô: passe por ela no navegador e rode de novo"
        if (o.optInt("login") > 0) verdict += "a página pede login: entre (o app salva o login) e rode de novo"
    }

    private fun describeVideos(o: JSONObject, sb: StringBuilder, verdict: MutableList<String>) {
        val vs = o.optJSONArray("videos") ?: return
        for (i in 0 until vs.length()) {
            val v = vs.optJSONObject(i) ?: continue
            sb.appendLine("  vídeo ${i + 1}: fonte ${v.optString("src")} • ${if (v.optBoolean("paused")) "pausado" else "tocando"} em ${v.optInt("t")}/${v.optInt("d").let { if (it < 0) "?" else "$it" }}s • ${v.optInt("w")}x${v.optInt("h")} • pronto=${v.optInt("rs")}/4" +
                (if (v.optString("err").isNotBlank()) " • ERRO ${v.optString("err")}" else "") + (if (v.optBoolean("drm")) " • DRM" else "") +
                (if (v.optInt("sources") > 0) " • <source>×${v.optInt("sources")}" else "") + (if (v.optString("cors").isNotBlank()) " • crossorigin" else ""))
            if (v.optBoolean("drm")) verdict += "o <video> está com DRM ativo: não dá para baixar o arquivo"
            if (v.optString("err").isNotBlank()) verdict += "o próprio player deu erro (${v.optString("err")}): o vídeo não toca nem no navegador do app"
        }
    }

    private suspend fun hlsDeep(url: String, base: Map<String, String>, sb: StringBuilder, verdict: MutableList<String>) {
        val full = hit(url, without(base, "Range"), 400_000)
        val txt = String(full.body, Charsets.UTF_8)
        if (!txt.contains("#EXTM3U")) return
        val info = DetScan.hlsInfo(txt)
        sb.appendLine("lista HLS: ${if (info.master) "principal com ${info.variants} qualidade(s), até ${info.maxHeight}p" else "de uma qualidade"}${if (info.aes) " • AES-128" else ""}${if (info.drm) " • DRM" else ""}")
        if (info.drm) verdict += "a lista HLS tem DRM: não dá para baixar"
        fun first(t: String) = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        var pl = txt; var plUrl = url
        if (info.master) {
            val v = first(pl)?.let { DetScan.resolve(plUrl, it) } ?: return
            val r = hit(v, without(base, "Range"), 400_000)
            sb.appendLine("  lista da qualidade: ${r.line()} (${tag(v, "cdn")})")
            if (!r.ok) { verdict += "a lista principal abre, mas a da qualidade não (HTTP ${r.code})"; return }
            pl = String(r.body, Charsets.UTF_8); plUrl = v
        }
        Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(pl)?.groupValues?.get(1)?.let { k ->
            val ku = DetScan.resolve(plUrl, k) ?: return@let
            if (!ku.startsWith("http")) return@let
            val a = hit(ku, without(base, "Range"), 4096); val b = hit(ku, without(base, "Range", "Cookie"), 4096)
            sb.appendLine("  chave AES: com cookie HTTP ${a.code} (${a.body.size} bytes) • sem cookie HTTP ${b.code}")
            if (a.code !in 200..299 || a.body.size != 16) verdict += "a chave de criptografia não abre fora do navegador (HTTP ${a.code})"
        }
        val seg = first(pl)?.let { DetScan.resolve(plUrl, it) } ?: return
        val s0 = hit(seg, base, 8192); val s1 = hit(seg, without(base, "Cookie"), 8192); val s2 = hit(seg, without(base, "Referer"), 8192)
        sb.appendLine("  primeiro pedaço (${tag(seg, "cdn")}): igual ao player ${s0.line()} • sem cookie HTTP ${s1.code} • sem Referer HTTP ${s2.code}")
        if (s0.code !in 200..299) verdict += "a lista abre, mas os PEDAÇOS são recusados (HTTP ${s0.code})"
        else {
            if (s1.code !in 200..299) verdict += "os pedaços exigem o cookie"
            if (s2.code !in 200..299) verdict += "os pedaços exigem o Referer"
        }
        sb.appendLine("  pedaços na lista: ${pl.lines().count { it.isNotBlank() && !it.startsWith("#") }}")
    }

    private suspend fun photos(im: JSONObject?, page: String, sb: StringBuilder, verdict: MutableList<String>) {
        if (im == null) { sb.appendLine("não consegui ler as imagens"); return }
        val sz = im.optJSONObject("sizes")
        sb.appendLine("<img>: ${im.optInt("n")} • carregamento atrasado (lazy): ${im.optInt("lazy")} • com várias resoluções (srcset): ${im.optInt("srcset")} • fundos CSS: ${im.optInt("bg")} • links para imagem: ${im.optInt("links")}")
        if (sz != null) sb.appendLine("tamanho das imagens carregadas: pequenas(<400px) ${sz.optInt("p")} • médias ${sz.optInt("m")} • grandes(≥1000px) ${sz.optInt("g")}")
        val top = im.optJSONArray("top") ?: return
        if (top.length() == 0) { sb.appendLine("nenhuma imagem carregada com tamanho real (as fotos podem estar como fundo CSS, em canvas ou só ao abrir)"); return }
        for (i in 0 until minOf(3, top.length())) {
            val o = top.optJSONObject(i) ?: continue
            val u = o.optString("u"); if (!u.startsWith("http")) continue
            val ck = try { Net.cookies?.cookieFor(u) } catch (_: Exception) { null }
            val h1 = linkedMapOf("User-Agent" to Net.userAgent, "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8", "Referer" to page).also { if (!ck.isNullOrBlank()) it["Cookie"] = ck }
            val a = hit(u, h1, 3_000_000); val b = hit(u, mapOf("User-Agent" to Net.userAgent), 3_000_000)
            fun dims(r: Res): String {
                val op = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                try { android.graphics.BitmapFactory.decodeByteArray(r.body, 0, r.body.size, op) } catch (_: Exception) {}
                return if (op.outWidth > 0) "${op.outWidth}x${op.outHeight}" else "não é imagem"
            }
            val alt = try { PhotoGrab.fallback(u) } catch (_: Exception) { null }
            sb.appendLine("  foto ${i + 1} (${o.optInt("w")}x${o.optInt("h")} na tela, ${tag(u, "img")}): com página+cookie HTTP ${a.code} ${dims(a)} ${a.body.size / 1000}KB • sem nada HTTP ${b.code}" +
                (if (alt != null && alt != u) " • existe versão maior provável" else ""))
            if (a.code !in 200..299) verdict += "as fotos não abrem fora da página (HTTP ${a.code})"
            else if (b.code !in 200..299) verdict += "as fotos exigem a página de origem/cookie (o app já manda)"
        }
    }

    private fun refKind(ref: String, page: String, media: String) = when {
        ref.substringBefore('#') == page.substringBefore('#') -> "a página"
        Net.originOf(ref) == Net.originOf(page) -> "o site (outra página)"
        Trail.host(ref) == Trail.host(media) -> "o próprio servidor do vídeo"
        else -> "outro site (${tag(ref, "emb")}, provavelmente o player embutido)"
    }

    private fun shapeShort(u: String): String {
        val names = u.substringAfter('?', "").split('&').filter { it.isNotBlank() }.map { it.substringBefore('=') }.take(4)
        return if (names.isEmpty()) "" else "(?${names.joinToString(",")})"
    }

    fun shape(u: String): String {
        val q = u.substringAfter('?', "").substringBefore('#')
        val names = q.split('&').filter { it.isNotBlank() }.map { it.substringBefore('=').lowercase().take(20) }.distinct()
        val ext = Regex("""\.([a-z0-9]{2,4})(?:$|[?#])""", RegexOption.IGNORE_CASE).find(u.substringBefore('?'))?.groupValues?.get(1)?.lowercase() ?: "sem extensão"
        val tok = Regex("/(?:[a-f0-9]{24,}|[A-Za-z0-9_-]{32,})/").containsMatchIn(u.substringBefore('?'))
        return "arquivo .$ext • campos: ${if (names.isEmpty()) "nenhum" else names.joinToString(", ")}${if (tok) " • token no caminho" else ""}" +
            (LinkExpiry.expiresAt(u)?.let { " • tem data de validade" } ?: "")
    }

    private fun errText(r: Res): String? {
        if (r.body.isEmpty()) return null
        var t = String(r.body.copyOf(minOf(r.body.size, 1500)), Charsets.UTF_8)
        if (r.ct.contains("html", true)) t = t.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
        t = FailDoctor.anon(t).replace(Regex("[A-Za-z0-9_\\-+/=]{20,}"), "…").replace(Regex("\\d{6,}"), "#").replace(Regex("\\s+"), " ").trim()
        return t.take(200).ifBlank { null }
    }
}
