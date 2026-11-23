package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Testa a página aberta e monta um relatório anônimo.
 * Só lê: não enfileira, não envia ao Telegram, não grava no histórico.
 */
object SiteAnalyzer {
    const val TOTAL_MS = 60_000L

    data class YtdlpResult(val ok: Boolean, val extractor: String = "", val error: String = "", val httpCode: Int = 0)
    data class HiddenResult(val found: Boolean, val formats: List<String> = emptyList(), val code: String = "NO_MEDIA")
    data class JsSnap(
        val video: Int = 0, val iframe: Int = 0, val source: Int = 0,
        val needsClick: Boolean = false,
        val iframeHosts: List<String> = emptyList(),
        val formatHints: List<String> = emptyList(),
        val mse: Boolean = false, val blob: Boolean = false,
        val drm: Boolean = false, val drmType: String = "none",
        val appErrors: List<String> = emptyList(),
        val pageErrors: Map<String, Int> = emptyMap(),
    )
    data class FetchResult(
        val loaded: Boolean, val code: Int, val ms: Long, val redirects: Int,
        val body: String, val err: String = "",
    )
    data class Outcome(val report: String, val fields: AnonReport.Fields, val tokens: Set<String>)

    fun interface Progress { fun invoke(step: String) }

    /** Script de leitura (só números/enums; roda uma vez no WebView visível). */
    const val ANALYZE_JS = """(function(){
try{
  var D=document, out={v:0,i:0,s:0,click:0,ifr:[],fmt:[],mse:0,blob:0,drm:0,drmt:'none',app:[],page:{}};
  out.v=D.querySelectorAll('video').length;
  out.i=D.querySelectorAll('iframe').length;
  out.s=D.querySelectorAll('source').length;
  function hasPlay(){
    var sels=['button[class*="play" i]','[class*="play-button" i]','[class*="vjs-big-play" i]','[aria-label*="play" i]','[aria-label*="assistir" i]','[class*="overlay" i]','[class*="poster" i]'];
    for(var i=0;i<sels.length;i++){ try{ if(D.querySelector(sels[i])) return 1 }catch(e){} }
    try{ var vs=D.querySelectorAll('video'); for(var j=0;j<vs.length;j++){ if(vs[j].paused && vs[j].readyState<3) return 1 } }catch(e){}
    return 0;
  }
  out.click=hasPlay();
  try{ D.querySelectorAll('iframe').forEach(function(f){ var u=f.src||f.getAttribute('src')||''; if(/^https?:/i.test(u)) out.ifr.push(u) }) }catch(e){}
  function addFmt(u){ if(!u)return; if(/\.m3u8(\?|$)/i.test(u)||/mpegurl/i.test(u)) out.fmt.push('HLS'); else if(/\.mpd(\?|$)/i.test(u)) out.fmt.push('DASH'); else if(/\.mp4(\?|$)/i.test(u)) out.fmt.push('MP4'); else if(/^blob:/i.test(u)) { out.blob=1; out.fmt.push('blob') } }
  try{ D.querySelectorAll('video,source').forEach(function(el){ addFmt(el.currentSrc||el.src||el.getAttribute('src')||'') }) }catch(e){}
  try{ if(window.MediaSource) out.mse=1 }catch(e){}
  try{
    var vs=D.querySelectorAll('video');
    for(var k=0;k<vs.length;k++){ if(vs[k].mediaKeys){ out.drm=1; out.drmt='desconhecido' } }
  }catch(e){}
  try{
    if(navigator.requestMediaKeySystemAccess && !navigator.__bptAnEme){
      var orig=navigator.requestMediaKeySystemAccess.bind(navigator);
      navigator.__bptAnEme=1;
      navigator.requestMediaKeySystemAccess=function(id,cfg){
        out.drm=1;
        if(/widevine/i.test(id||'')) out.drmt='widevine';
        else if(/playready/i.test(id||'')) out.drmt='playready';
        else if(/fairplay/i.test(id||'')) out.drmt='fairplay';
        else out.drmt='desconhecido';
        return orig(id,cfg);
      };
    }
  }catch(e){}
  var hook=window.__bptAnErr;
  if(hook){
    if(hook.app) out.app=hook.app.slice(0,8);
    if(hook.page) out.page=hook.page;
  }
  return JSON.stringify(out);
}catch(e){ return '{"v":0,"i":0,"s":0,"click":0,"ifr":[],"fmt":[],"mse":0,"blob":0,"drm":0,"drmt":"none","app":[],"page":{}}' }
})()"""

    const val HOOK_ERR_JS = """(function(){
try{
  if(window.__bptAnErr) return;
  window.__bptAnErr={app:[],page:{}};
  window.addEventListener('error',function(ev){
    try{
      var msg=(ev&&ev.message)||'';
      var typ=(ev&&ev.error&&ev.error.name)||'UnknownError';
      var st=(ev&&ev.error&&ev.error.stack)||'';
      var src=(ev&&ev.filename)||'';
      if(/bpt-analyze|baixartg|HOOK_JS|PhotoGrab/i.test(src+st)){
        var line=(ev&&ev.lineno)||0;
        if(window.__bptAnErr.app.length<8) window.__bptAnErr.app.push(typ+'@L'+line);
      } else {
        window.__bptAnErr.page[typ]=(window.__bptAnErr.page[typ]||0)+1;
      }
    }catch(x){}
  });
}catch(e){}
})()"""

    fun parseJsSnap(raw: String?): JsSnap {
        if (raw.isNullOrBlank() || raw == "null") return JsSnap()
        return try {
            var v: Any? = org.json.JSONTokener(raw.trim()).nextValue()
            if (v is String) v = org.json.JSONTokener(v).nextValue()
            val o = v as? JSONObject ?: return JsSnap()
            val ifr = o.optJSONArray("ifr")
            val hosts = ArrayList<String>()
            if (ifr != null) for (i in 0 until ifr.length()) hosts += ifr.optString(i)
            val fmt = o.optJSONArray("fmt")
            val fmts = ArrayList<String>()
            if (fmt != null) for (i in 0 until fmt.length()) fmts += fmt.optString(i)
            val app = o.optJSONArray("app")
            val apps = ArrayList<String>()
            if (app != null) for (i in 0 until app.length()) apps += app.optString(i)
            val page = LinkedHashMap<String, Int>()
            o.optJSONObject("page")?.let { p -> p.keys().forEach { k -> page[k] = p.optInt(k) } }
            JsSnap(
                video = o.optInt("v"), iframe = o.optInt("i"), source = o.optInt("s"),
                needsClick = o.optInt("click") == 1,
                iframeHosts = hosts, formatHints = fmts,
                mse = o.optInt("mse") == 1, blob = o.optInt("blob") == 1,
                drm = o.optInt("drm") == 1, drmType = o.optString("drmt", "none"),
                appErrors = apps, pageErrors = page,
            )
        } catch (_: Exception) { JsSnap() }
    }

    fun detectChallenge(html: String, httpCode: Int): Pair<Boolean, String> {
        val h = html.lowercase()
        fun has(r: String) = Regex(r, RegexOption.IGNORE_CASE).containsMatchIn(h)
        if (has("""\b(recaptcha|hcaptcha|h-captcha|cf-turnstile|g-recaptcha|captcha-box|id=["']captcha)\b"""))
            return true to "captcha"
        if (has("""\b(cf-challenge|cf-browser-verification|just a moment|checking your browser|challenge-platform|jschl-answer)\b"""))
            return true to "js_challenge"
        if (httpCode == 451 || has("""\b(not available in (your|this) country|geo-?block|region (blocked|restricted)|não (está|disponível) no seu país)\b"""))
            return true to "geo_block"
        if (has("""\b(are you 18|age[ -]?gate|age[ -]?verif|confirm (your )?age|verificação de idade|você tem 18)\b"""))
            return true to "age_gate"
        if (httpCode == 403 && has("""\b(enable cookies|cookies? (required|disabled)|aceitar cookies|permita cookies)\b"""))
            return true to "cookie_required"
        if (httpCode == 403 && html.length < 8_000 && has("""\b(access denied|forbidden|attention required)\b"""))
            return true to "js_challenge"
        return false to "none"
    }

    fun formatOf(hints: List<String>, urls: List<String>): String {
        fun kind(u: String): String? = when {
            Extract.isHls(u) || u.contains(".m3u8", true) -> "HLS"
            Extract.isDash(u) || u.contains(".mpd", true) -> "DASH"
            u.startsWith("blob:", true) -> "blob"
            u.contains(".mp4", true) || Extract.mediaKindOf(u) == Kind.DIRECT -> "MP4"
            else -> null
        }
        val fromUrl = urls.mapNotNull { kind(it) }
        val all = hints + fromUrl
        return when {
            "HLS" in all -> "HLS"
            "DASH" in all -> "DASH"
            "MSE" in all -> "MSE"
            "blob" in all -> "blob"
            "MP4" in all -> "MP4"
            all.isNotEmpty() -> "desconhecido"
            else -> "none"
        }
    }

    fun inspectHtml(pageUrl: String, html: String): Triple<Int, Int, Int> {
        val doc = try { org.jsoup.Jsoup.parse(html, pageUrl) } catch (_: Exception) { return Triple(0, 0, 0) }
        return Triple(doc.select("video").size, doc.select("iframe").size, doc.select("source").size)
    }

    fun iframeHosts(pageUrl: String, html: String): List<String> {
        val doc = try { org.jsoup.Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        return doc.select("iframe[src]").mapNotNull { it.absUrl("src").ifBlank { it.attr("src") }.takeIf { u -> u.startsWith("http") } }
    }

    fun mediaUrlsInHtml(pageUrl: String, html: String): List<String> {
        val found = try { Extract.parse(pageUrl, html).videos.map { it.url } } catch (_: Exception) { emptyList() }
        return found.filter { it.startsWith("http") }
    }

    fun looksExpiring(url: String): Boolean {
        val q = url.substringBefore('#').substringAfter('?', "").lowercase()
        if (q.isBlank()) return false
        return q.split('&').any { p ->
            val n = p.substringBefore('=')
            n in setOf("exp", "expires", "expiry", "expire", "e", "token", "signature", "sig", "hmac", "policy")
        }
    }

    fun mapThrowable(e: Throwable): String {
        if (e is CancellationException) throw e
        val name = e.javaClass.simpleName
        val msg = (e.message ?: "").lowercase()
        return when {
            name.contains("UnknownHost", true) || "unable to resolve host" in msg || "unknownhost" in msg -> "DNS"
            name.contains("SSL", true) || name.contains("Cert", true) || "ssl" in msg || "certificate" in msg || "trust anchor" in msg -> "SSL"
            name.contains("Timeout", true) || "timed out" in msg || "timeout" in msg -> "TIMEOUT"
            name.contains("Connect", true) || "failed to connect" in msg || "connection refused" in msg -> "CONNECTION"
            else -> "NETWORK"
        }
    }

    fun mapYtdlp(r: YtdlpResult): AnonReport.Attempt {
        if (r.ok) return AnonReport.Attempt(true, "OK")
        val e = r.error.lowercase()
        val code = when {
            r.httpCode == 403 || "http 403" in e || e.contains("\"403\"") || Regex("""\b403\b""").containsMatchIn(e) -> "HTTP_403"
            r.httpCode == 404 || Regex("""\b404\b""").containsMatchIn(e) -> "HTTP_404"
            r.httpCode == 429 || Regex("""\b429\b""").containsMatchIn(e) -> "HTTP_429"
            r.httpCode in 500..599 -> "HTTP_${r.httpCode}"
            "unsupported" in e || "no video formats" in e || "no video" in e -> "UNSUPPORTED"
            "timeout" in e || "timed out" in e -> "TIMEOUT"
            "ssl" in e || "certificate" in e -> "SSL"
            "resolve" in e || "dns" in e -> "DNS"
            r.extractor.isNotBlank() && !r.extractor.equals("generic", true) ->
                "EXTRACTOR_SPECIFIC#" + SiteHash.short("ext", r.extractor).removePrefix("ext#")
            r.extractor.equals("generic", true) -> "EXTRACTOR_GENERIC"
            else -> "EXTRACTOR_ERROR"
        }
        return AnonReport.Attempt(false, code)
    }

    /**
     * Núcleo testável: recebe HTML já baixado, snapshot JS, capturas, resultados de cabeçalho/yt-dlp/oculto.
     * Não inclui URL/título/texto no relatório.
     */
    fun build(
        appVersion: String,
        webViewVersion: String,
        pageUrl: String,
        fetch: FetchResult,
        snap: JsSnap,
        captured: List<String>,
        header: HeaderGuess,
        hidden: HiddenResult,
        ytdlp: YtdlpResult,
        photosOn: Boolean,
        photo: PhotoSnap,
    ): Outcome {
        val html = fetch.body
        val (vHtml, iHtml, sHtml) = if (html.isNotBlank()) inspectHtml(pageUrl, html) else Triple(0, 0, 0)
        val videoCount = maxOf(snap.video, vHtml)
        val iframeCount = maxOf(snap.iframe, iHtml)
        val sourceCount = maxOf(snap.source, sHtml)
        val (chal, chalType) = detectChallenge(html, fetch.code)
        val iframes = (snap.iframeHosts + iframeHosts(pageUrl, html)).distinct()
        val cross = iframes.firstOrNull { !SiteHash.sameHost(pageUrl, it) }
        val htmlMedia = mediaUrlsInHtml(pageUrl, html)
        val allMedia = (captured + htmlMedia).distinct()
        val fmt = formatOf(
            snap.formatHints + (if (snap.mse) listOf("MSE") else emptyList()) + (if (snap.blob) listOf("blob") else emptyList()),
            allMedia,
        )
        val htmlOk = htmlMedia.isNotEmpty()
        val capOk = captured.isNotEmpty()
        val expires = allMedia.firstOrNull()?.let { looksExpiring(it) }
        val title = try { org.jsoup.Jsoup.parse(html, pageUrl).title() } catch (_: Exception) { "" }
        val fields = AnonReport.Fields(
            appVersion = appVersion,
            webViewVersion = webViewVersion,
            pageLoaded = fetch.loaded,
            httpCode = fetch.code,
            loadMs = fetch.ms,
            redirectCount = fetch.redirects,
            challenge = chal,
            challengeType = chalType,
            videoCount = videoCount,
            iframeCount = iframeCount,
            sourceCount = sourceCount,
            needsClick = snap.needsClick,
            iframeCrossOrigin = cross != null,
            iframeCdnHash = cross?.let { SiteHash.short("cdn", it) } ?: "",
            format = if (fmt == "none" && hidden.formats.isNotEmpty()) formatOf(hidden.formats, emptyList()) else fmt,
            linkExpires = header.expires ?: expires,
            needsReferer = header.needsReferer,
            needsCookie = header.needsCookie,
            needsSpecialHeader = header.needsSpecialHeader,
            drm = snap.drm, drmType = snap.drmType,
            htmlDirect = when {
                htmlOk -> AnonReport.Attempt(true, "OK")
                fetch.err.isNotBlank() -> AnonReport.Attempt(false, fetch.err)
                !fetch.loaded -> AnonReport.Attempt(false, if (fetch.code in 100..599) "HTTP_${fetch.code}" else "NO_MEDIA")
                else -> AnonReport.Attempt(false, "NO_MEDIA")
            },
            hiddenAutoplay = AnonReport.Attempt(hidden.found, hidden.code),
            mediaCapture = AnonReport.Attempt(capOk, if (capOk) "OK" else "NO_MEDIA"),
            ytdlp = mapYtdlp(ytdlp),
            photosOn = photosOn,
            imgCount = photo.imgs,
            photoCands = photo.cands,
            photoRejects = photo.rejects,
            photoScriptErrors = photo.scriptErrors,
            appJsErrors = snap.appErrors,
            pageJsErrorCounts = snap.pageErrors,
        )
        val tokens = AnonReport.tokensOf(pageUrl, html, title) +
            captured.flatMap { AnonReport.tokensOf(it, "", "") } +
            iframes.flatMap { AnonReport.tokensOf(it, "", "") }
        val raw = AnonReport.render(fields)
        val report = AnonReport.sanitize(raw, tokens)
        return Outcome(report, fields, tokens)
    }

    data class HeaderGuess(
        val needsReferer: Boolean? = null,
        val needsCookie: Boolean? = null,
        val needsSpecialHeader: Boolean? = null,
        val expires: Boolean? = null,
    )
    data class PhotoSnap(val imgs: Int = 0, val cands: Int = 0, val rejects: Map<String, Int> = emptyMap(), val scriptErrors: Int = 0)

    data class Probe(val status: Int, val ok: Boolean)

    fun guessHeaders(full: Probe, noRef: Probe, noCookie: Probe, altUa: Probe, mediaUrl: String): HeaderGuess {
        fun failed(p: Probe) = !p.ok && p.status in 400..499
        val needsRef = if (full.ok && failed(noRef)) true else if (full.ok && noRef.ok) false else null
        val needsCk = if (full.ok && failed(noCookie)) true else if (full.ok && noCookie.ok) false else null
        val needsUa = if (full.ok && failed(altUa)) true else if (full.ok && altUa.ok) false else null
        return HeaderGuess(needsRef, needsCk, needsUa, looksExpiring(mediaUrl))
    }

    suspend fun fetchPage(url: String, referer: String? = null): FetchResult {
        val t0 = System.currentTimeMillis()
        var current = url
        var hops = 0
        var last = 0
        var body = ""
        val client = Net.fast.newBuilder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        return try {
            repeat(8) {
                currentCoroutineContext().ensureActive()
                val b = Request.Builder().url(current).header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                if (!referer.isNullOrBlank()) b.header("Referer", referer)
                client.newCall(b.build()).await().use { r ->
                    last = r.code
                    if (r.isRedirect) {
                        val loc = r.header("Location").orEmpty()
                        if (loc.isBlank()) return@use
                        hops++
                        current = try { java.net.URI(current).resolve(loc).toString() } catch (_: Exception) { loc }
                        return@repeat
                    }
                    val ct = r.header("Content-Type").orEmpty()
                    if (r.isSuccessful && (ct.isEmpty() || ct.contains("html", true) || ct.contains("text", true) || ct.contains("xml", true))) {
                        body = r.body?.source()?.let { src -> src.request(1_500_000); src.buffer.readUtf8(minOf(src.buffer.size, 1_500_000)) } ?: ""
                    }
                }
                if (last !in 300..399) return@repeat
            }
            FetchResult(last in 200..299, last, System.currentTimeMillis() - t0, hops, body)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FetchResult(false, last, System.currentTimeMillis() - t0, hops, body, mapThrowable(e))
        }
    }

    suspend fun probeMedia(url: String, referer: String?, cookie: String?, ua: String): Probe {
        val client = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build()
        return try {
            val b = Request.Builder().url(url)
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Range", "bytes=0-1")
            if (!referer.isNullOrBlank()) {
                b.header("Referer", referer)
                try {
                    val o = java.net.URI(referer)
                    if (o.scheme != null && o.authority != null) b.header("Origin", "${o.scheme}://${o.authority}")
                } catch (_: Exception) {}
            }
            if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
            client.newCall(b.build()).await().use { r ->
                val ok = r.code in 200..299 || r.code == 206
                Probe(r.code, ok)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Probe(0, false)
        }
    }

    suspend fun defaultYtdlp(pageUrl: String): YtdlpResult {
        val server = try { br.geracaoai.baixartg.Prefs.server } catch (_: Exception) { Downloader.DEFAULT_SERVER }
        val body = try {
            Downloader.session(JSONObject().put("url", pageUrl), pageUrl).toString()
        } catch (_: Exception) { JSONObject().put("url", pageUrl).toString() }
        val reqBody = body.toRequestBody("application/json".toMediaType())
        return try {
            Net.slow.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(22, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(server.trimEnd('/') + "/api/info").post(reqBody).build())
                .await().use { r ->
                    val raw = r.body?.string() ?: ""
                    val j = try { JSONObject(raw) } catch (_: Exception) {
                        return@use YtdlpResult(false, httpCode = r.code, error = "HTTP ${r.code}")
                    }
                    val ok = j.optBoolean("ok")
                    YtdlpResult(ok, j.optString("extractor"), j.optString("error"), r.code)
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            YtdlpResult(false, error = mapThrowable(e))
        }
    }

    suspend fun run(
        pageUrl: String,
        appVersion: String,
        webViewVersion: String,
        photosOn: Boolean,
        snap: JsSnap,
        captured: List<String>,
        progress: Progress = Progress { },
        ytdlpFn: (suspend (String) -> YtdlpResult)? = null,
        hiddenFn: (suspend (String) -> HiddenResult)? = null,
        fetchFn: (suspend (String) -> FetchResult)? = null,
    ): Outcome {
        return try {
            withTimeout(TOTAL_MS) { runInner(pageUrl, appVersion, webViewVersion, photosOn, snap, captured, progress, ytdlpFn, hiddenFn, fetchFn) }
        } catch (e: TimeoutCancellationException) {
            progress.invoke("Tempo esgotado…")
            build(
                appVersion, webViewVersion, pageUrl,
                FetchResult(false, 0, TOTAL_MS, 0, "", "TIMEOUT"),
                snap, captured, HeaderGuess(), HiddenResult(false, emptyList(), "TIMEOUT"),
                YtdlpResult(false, error = "TIMEOUT"), photosOn, PhotoSnap(),
            )
        }
    }

    private suspend fun runInner(
        pageUrl: String,
        appVersion: String,
        webViewVersion: String,
        photosOn: Boolean,
        snap: JsSnap,
        captured: List<String>,
        progress: Progress,
        ytdlpFn: (suspend (String) -> YtdlpResult)?,
        hiddenFn: (suspend (String) -> HiddenResult)?,
        fetchFn: (suspend (String) -> FetchResult)?,
    ): Outcome {
        progress.invoke("Carregando página…")
        val fetch = withTimeoutOrNull(10_000) { (fetchFn ?: { fetchPage(it) })(pageUrl) }
            ?: FetchResult(false, 0, 10_000, 0, "", "TIMEOUT")
        currentCoroutineContext().ensureActive()

        progress.invoke("Procurando vídeo…")
        val htmlMedia = mediaUrlsInHtml(pageUrl, fetch.body)
        val mediaUrl = (captured + htmlMedia).firstOrNull { it.startsWith("http") }
        val cookie = try { Net.cookies?.cookieFor(pageUrl) } catch (_: Exception) { null }
        var header = HeaderGuess()
        if (mediaUrl != null) {
            progress.invoke("Testando cabeçalhos…")
            val ua = Net.userAgent
            header = withTimeoutOrNull(10_000) {
                coroutineScope {
                    val fullD = async { probeMedia(mediaUrl, pageUrl, cookie, ua) }
                    val noRefD = async { probeMedia(mediaUrl, null, cookie, ua) }
                    val noCkD = async { probeMedia(mediaUrl, pageUrl, null, ua) }
                    val altD = async { probeMedia(mediaUrl, pageUrl, cookie, "Mozilla/5.0") }
                    guessHeaders(fullD.await(), noRefD.await(), noCkD.await(), altD.await(), mediaUrl)
                }
            } ?: HeaderGuess()
        }
        currentCoroutineContext().ensureActive()

        progress.invoke("Abrindo página oculta…")
        val hidden = withTimeoutOrNull(14_000) {
            if (hiddenFn != null) hiddenFn(pageUrl)
            else if (JsRender.available()) {
                val js = JsRender.renderQueue(pageUrl, 12_000)
                val fmts = js?.media.orEmpty().mapNotNull {
                    when {
                        Extract.isHls(it.url) -> "HLS"
                        Extract.isDash(it.url) -> "DASH"
                        Extract.mediaKindOf(it.url) == Kind.DIRECT -> "MP4"
                        else -> null
                    }
                }
                if (js?.media.orEmpty().isNotEmpty()) HiddenResult(true, fmts, "OK")
                else HiddenResult(false, emptyList(), "NO_MEDIA")
            } else HiddenResult(false, emptyList(), "SKIPPED")
        } ?: HiddenResult(false, emptyList(), "TIMEOUT")
        currentCoroutineContext().ensureActive()

        progress.invoke("Consultando servidor…")
        val ytdlp = withTimeoutOrNull(22_000) { (ytdlpFn ?: { defaultYtdlp(it) })(pageUrl) }
            ?: YtdlpResult(false, error = "TIMEOUT")

        progress.invoke("Conferindo fotos…")
        val photo = if (photosOn && fetch.body.isNotBlank()) photoFromHtml(pageUrl, fetch.body) else PhotoSnap()

        progress.invoke("Montando relatório…")
        return build(
            appVersion, webViewVersion, pageUrl, fetch, snap,
            captured, header, hidden, ytdlp, photosOn, photo,
        )
    }

    fun photoFromHtml(pageUrl: String, html: String): PhotoSnap {
        val imgs = try { org.jsoup.Jsoup.parse(html, pageUrl).select("img").size } catch (_: Exception) { 0 }
        val rejects = LinkedHashMap<String, Int>()
        val cands = try {
            val list = Photos.fromHtml(pageUrl, html)
            // PhotoGrab.Cand path if Photos is empty
            list.size
        } catch (_: Exception) { 0 }
        try {
            val grab = PhotoGrab.select(emptyList()) { why ->
                val k = AnonReport.safePhotoReason(why)
                rejects[k] = (rejects[k] ?: 0) + 1
            }
            grab.size
        } catch (_: Exception) {}
        // Conta descarte pelo coletor a partir de candidatos HTML visíveis (sem URLs no relatório)
        try {
            val raw = ArrayList<PhotoGrab.Cand>()
            val doc = org.jsoup.Jsoup.parse(html, pageUrl)
            for (img in doc.select("img")) {
                val u = img.absUrl("src").ifBlank { img.attr("src") }
                if (u.startsWith("http")) raw += PhotoGrab.Cand(u, 0, 0, u, "", "img", false)
            }
            val kept = PhotoGrab.select(raw) { why ->
                val k = AnonReport.safePhotoReason(why)
                rejects[k] = (rejects[k] ?: 0) + 1
            }
            return PhotoSnap(imgs, maxOf(cands, kept.size), rejects, 0)
        } catch (_: Exception) {
            return PhotoSnap(imgs, cands, rejects, 0)
        }
    }
}
