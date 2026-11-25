package br.geracaoai.baixartg.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * WebView oculto, preso à janela da Activity (com tamanho real, para lazy-load/rolagem funcionarem),
 * atrás do conteúdo e sem receber toques. Usado por todas as abas.
 *  - listing(): renderiza, espera desafio anti-bot passar, rola várias vezes, coleta cards do DOM (inclusive shadow DOM).
 *  - render(play=true): dá play e intercepta pedidos de mídia.
 * Cookies ficam no CookieManager, o mesmo que o OkHttp usa (WebCookies), então um desafio resolvido aqui vale lá.
 */
object JsRender {
    @Volatile private var activity: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())
    /**
     * Páginas ocultas ficam na MESMA janela (e no mesmo processo de renderização) do navegador visível, então
     * cada uma tocando vídeo pesa na tela. A fila de envio tem pool próprio (até 10), independente do navegador visível.
     */
    private val gate = Semaphore(3)
    /** WebViews próprios da fila de envio. */
    private val queueGate = Semaphore(3)
    /** Quantos WebViews ocultos estão vivos agora (diagnóstico). */
    val alive = java.util.concurrent.atomic.AtomicInteger(0)
    val aliveQueue = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var lastUnavailable = false

    const val PLAY_JS_SCRIPT = """
        (function(){
          function q(s){try{return document.querySelectorAll(s)}catch(e){return []}}
          // textContent (não força recalcular o layout da página, que é dividida com o navegador visível)
          function lbl(el){return ((el.textContent||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).replace(/\s+/g,' ').trim()}
          function consent(){
            var re=/^(aceitar|aceito|aceitar todos|accept|accept all|allow all|concordo|agree|i agree|entendi|got it|continuar|continue|fechar|close|dismiss|ok)$/i;
            q('button,[role=button],a[class*=close],[class*=consent] a,[class*=cookie] a').forEach(function(el){var t=lbl(el);if(t.length<25&&re.test(t)){try{el.click()}catch(e){}}});
          }
          function playText(){
            var re=/^(assistir|assista|assistir agora|assistir v[ií]deo|watch|watch now|watch video|play|play video|reproduzir|ver v[ií]deo|ver iniciar|iniciar come[cç]ar|start)$/i;
            var all=q('button,a,[role=button],[class*=play],[class*=watch],[class*=assistir]'),k=0;
            Array.prototype.slice.call(all,0,400).forEach(function(el){
              var t=lbl(el);if(!t||t.length>30||!re.test(t))return;
              if(el.tagName==='A'){var h=el.getAttribute('href')||'';if(window.__bptNav)return;
                if(h&&h.indexOf('http')===0&&h.indexOf(location.host)<0)return; window.__bptNav=1;}
              try{el.click()}catch(e){}
            });
          }
          function go(){
            // no máximo 1 vez a cada 1,5 s e só nos primeiros ~25 s (antes: ~3x por segundo, sem fim)
            var now=Date.now(); window.__bptT0=window.__bptT0||now;
            if(window.__bptLast&&now-window.__bptLast<1400)return; window.__bptLast=now;
            if(now-window.__bptT0>25000){if(window.__bptPlay){clearInterval(window.__bptPlay)}return}
            q('video,audio').forEach(function(v){
              try{v.muted=true;v.defaultMuted=true;v.playsInline=true;v.setAttribute('playsinline','');var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}
            });
            var sels=['.jw-display-icon-display','.jw-icon-display','.vjs-big-play-button','.ytp-large-play-button','.vjs-poster',
              '[aria-label="Play"]','[aria-label="Reproduzir"]','[aria-label*="Play"]','[aria-label*="Assistir"]','[title="Play"]','[title="Reproduzir"]',
              'button.play','.play-button','.play-btn','.btn-play','.vp-preview','.plyr__control--overlaid','.big-play','.play-overlay','[class*=big-play]','[class*=play-overlay]',
              '.mejs__overlay-play','.fp-play','.html5-play-button','.media-poster','.video-play','[data-plyr=play]'];
            sels.forEach(function(s){q(s).forEach(function(el){try{el.click()}catch(e){}})});
            q('.jwplayer,.video-js,.plyr,video').forEach(function(el){try{el.click()}catch(e){}});
            consent(); playText();
            // pula anúncio pré-roll se o player mostrar o botão
            var skip=/^(skip(\s*ad)?|pular(\s*an[uú]ncio)?|omitir(\s*an[uú]ncio)?|skip\s*to\s*content)$/i;
            q('button,a,[role=button],[class*=skip],[class*=Skip]').forEach(function(el){
              var t=lbl(el); if(t&&t.length<24&&skip.test(t)){try{el.click()}catch(e){}}
            });
          }
          go();
          if(!window.__bptPlay){window.__bptPlay=setInterval(go, 1200);}
          return 'ok';
        })()
    """

    const val HOOK_JS = MseHookScript.SCRIPT

    fun bind(act: Activity) { activity = WeakReference(act) }
    fun unbind(act: Activity) { if (activity?.get() === act) activity = null }
    fun hostActivity(): Activity? = activity?.get()?.takeIf { !it.isDestroyed && !it.isFinishing }
    fun available(): Boolean = hostActivity() != null

    /** Container que nunca consome toques (passam para a tela de verdade). */
    private class PassThrough(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
    }

    suspend fun render(url: String, timeoutMs: Long = 12_000, scroll: Boolean = false, play: Boolean = false): JsPage? =
        if (scroll && !play) listing(url, timeoutMs.coerceAtLeast(25_000)) else page(url, timeoutMs, play)

    /** Listagem: espera desafio, rola até estabilizar e coleta cards. */
    suspend fun listing(url: String, maxMs: Long = 40_000): JsPage? = drive(url, maxMs + 3_000, true) { wv, hits, done ->
        val start = System.currentTimeMillis()
        var lastSig = ""; var stable = 0; var ticks = 0; var challengeSeen = false
        fun collect() {
            wv.evaluateJavascript(JsScripts.COLLECT) { raw ->
                val c = JsScripts.parseCollected(raw)
                wv.evaluateJavascript("(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()") { rawHtml ->
                    val html = unescapeJs(rawHtml).orEmpty() + (c?.let { JsScripts.cardsHtml(it) } ?: "")
                    done(JsPage(html.ifBlank { null }, mediaOf(hits, url), challenge = challengeSeen && (c?.challenge != false), cards = c?.let { JsScripts.synthCount(it) } ?: -1, collected = c))
                }
            }
        }
        fun tick() {
            if (System.currentTimeMillis() - start > maxMs) { collect(); return }
            ticks++
            wv.evaluateJavascript("(function(){var t=document.title||'';return (/just a moment|attention required|checking your browser|verify you are human|um momento/i.test(t)||!!document.querySelector('#challenge-form,#challenge-stage,[name=cf-turnstile-response]'))?'CH':'ok'})()") { ch ->
                if (ch?.contains("CH") == true) { challengeSeen = true; stable = 0; wv.postDelayed({ tick() }, 1_000); return@evaluateJavascript }
                wv.evaluateJavascript(AgeGate.BYPASS_JS) { agRaw ->
                    try {
                        val ag = org.json.JSONObject(unescapeJs(agRaw) ?: agRaw ?: "{}")
                        if (ag.optInt("clicked") == 1) {
                            stable = 0
                            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                wv.evaluateJavascript(JsScripts.SCROLL_STEP) { sig ->
                    if (sig == lastSig && wv.progress >= 100) stable++ else stable = 0
                    lastSig = sig ?: ""
                    if (ticks >= 4 && stable >= 3) collect() else wv.postDelayed({ tick() }, 800)
                }
            }
        }
        wv.postDelayed({ tick() }, 1_500)
    }

    /** Página com play para a fila de envio (pool separado). */
    suspend fun renderQueue(url: String, timeoutMs: Long): JsPage? =
        // pool próprio, até 10 páginas ocultas, mesmo com o navegador aberto ou depois de sair dele
        page(url, timeoutMs.coerceAtLeast(18_000), true, queueGate, slotWait = 15_000, queue = true)
    /** Pega uma vaga sem risco de vazar: tenta até o prazo; false = sem vaga. */
    private suspend fun acquire(s: Semaphore, waitMs: Long): Boolean {
        val end = System.currentTimeMillis() + waitMs
        while (true) {
            if (s.tryAcquire()) return true
            if (System.currentTimeMillis() >= end) return false
            kotlinx.coroutines.delay(150)
        }
    }
    @Volatile var browserVisible = false
    private val queueSoloGate = Semaphore(1)
    /** Com o navegador na tela: no máximo 1 página oculta NO TOTAL (navegador + fila), porque todas dividem o motor de páginas. */
    private val visibleGate = Semaphore(2)

    private suspend fun page(url: String, timeoutMs: Long, play: Boolean, pool: Semaphore = gate, slotWait: Long = 20_000, queue: Boolean = false): JsPage? {
        val wait = if (play) timeoutMs.coerceIn(8_000L, 25_000L) else timeoutMs
        return drive(url, wait + 3_000, !play, pool, slotWait, skipVisibleGate = queue) { wv, hits, done ->
            var start = System.currentTimeMillis()
            var firstHitAt = 0L
            var ageGateSeen = false
            var ageGateClicked = false
            fun finish() {
                wv.evaluateJavascript("(function(){try{var a=window.__bptReqLog||[];window.__bptReqLog=[];return JSON.stringify({m:window.__bptMseActive?1:0,r:a})}catch(e){return '{}'}})()") { mraw ->
                    try {
                        val ms = unescapeJs(mraw) ?: mraw ?: "{}"
                        val mo = org.json.JSONObject(ms)
                        if (mo.optInt("m") == 1) MseCapture.recordMse(url, "sb_js", "video/mp4")
                        mo.optJSONArray("r")?.let { MseCapture.ingestJsReqLog(url, it.toString()) }
                    } catch (_: Exception) {}
                    wv.evaluateJavascript(JsScripts.COLLECT) { raw ->
                        val c = JsScripts.parseCollected(raw)
                        if (c?.challenge == true) MseCapture.recordChallenge(url)
                        c?.videos?.forEach { v -> if (Extract.mediaKindOf(v) != null && !Extract.isSegmentUrl(v) && !MediaPick.isAdUrl(v)) synchronized(hits) { hits += v } }
                        val snap = MseCapture.snapshot(url)
                        wv.evaluateJavascript("(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()") { rawHtml ->
                            val media = mediaOf(hits, url)
                            val hasMseOrVideo = media.isNotEmpty() || snap.hasMse || snap.manifests.isNotEmpty() || snap.rangesOrDirect.isNotEmpty()
                            val agBypassed: Boolean? = if (ageGateSeen || ageGateClicked) (ageGateClicked && (hasMseOrVideo || (c?.videos?.isNotEmpty() == true))) else null
                            done(JsPage(unescapeJs(rawHtml), media, challenge = c?.challenge == true, collected = c,
                                apis = synchronized(hits) { hits.filter { it.startsWith("api:") }.map { it.removePrefix("api:") } }.take(12),
                                mse = snap.hasMse || snap.manifests.any { MseCapture.isDash(it) },
                                specialHeader = snap.hasSpecialHeader,
                                ageGateBypassed = agBypassed))
                        }
                    }
                }
            }
            var mseSeenAt = 0L
            fun tick() {
                if (wv.progress >= 30) {
                    wv.evaluateJavascript(AgeGate.BYPASS_JS) { agRaw ->
                        try {
                            val ag = org.json.JSONObject(unescapeJs(agRaw) ?: agRaw ?: "{}")
                            if (ag.optInt("detected") == 1) ageGateSeen = true
                            if (ag.optInt("clicked") == 1) {
                                ageGateSeen = true
                                if (!ageGateClicked) {
                                    ageGateClicked = true
                                    start = maxOf(start, System.currentTimeMillis() - (wait / 2))
                                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                if (play && wv.progress >= 40) { wv.evaluateJavascript(HOOK_JS) { }; wv.evaluateJavascript(PLAY_JS_SCRIPT) { } }
                wv.evaluateJavascript("(function(){try{var a=window.__bptReqLog||[];window.__bptReqLog=[];return JSON.stringify({net:window.__bptNet||[],m:window.__bptMseActive?1:0,r:a})}catch(e){return '{}'}})()") { raw ->
                    val jsStr = unescapeJs(raw) ?: raw?.trim() ?: "{}"
                    try {
                        val o = org.json.JSONObject(jsStr)
                        val a = o.optJSONArray("net")
                        if (a != null) for (i in 0 until a.length()) {
                            val u = a.optString(i)
                            if (u.startsWith("http") && !MediaPick.isAdUrl(u)) synchronized(hits) { hits += u }
                            else if (u.startsWith("mse:")) MseCapture.recordMse(url, "sb_js", u.removePrefix("mse:"))
                        }
                        if (o.optInt("m") == 1) MseCapture.recordMse(url, "sb_js", "video/mp4")
                        o.optJSONArray("r")?.let { MseCapture.ingestJsReqLog(url, it.toString()) }
                    } catch (_: Exception) {}
                }

                val urls = synchronized(hits) { hits.filter { !it.startsWith("api:") } }
                val cands = urls.map { MediaPick.Cand(it, pageUrl = url) }
                val now = System.currentTimeMillis()
                if (cands.any { !MediaPick.isLikelyAd(it) } && firstHitAt == 0L) firstHitAt = now
                val snap = MseCapture.snapshot(url)
                if ((snap.hasMse || snap.manifests.isNotEmpty()) && mseSeenAt == 0L) mseSeenAt = now
                val waited = now - start
                val gotMain = firstHitAt > 0 && now - firstHitAt >= 1_500L
                val gotMse = mseSeenAt > 0 && now - mseSeenAt >= 1_800L
                if (gotMain || gotMse || (play && MediaPick.readyToPick(cands, waited)) || now - start >= wait || (!play && wv.progress >= 100 && now - start > 2_500)) finish()
                else wv.postDelayed({ tick() }, 500)
            }
            wv.postDelayed({ tick() }, 800)
        }
    }

    private fun mediaOf(hits: Set<String>, url: String): List<Found> {
        val raw = synchronized(hits) { hits.filter { !it.startsWith("api:") && !Extract.isSegmentUrl(it) } }
        val nonPrev = raw.any { !Extract.isPreviewVideo(it) }
        return raw.filterNot { nonPrev && Extract.isPreviewVideo(it) }.mapNotNull { hu ->
            val kind = Extract.mediaKindOf(hu) ?: return@mapNotNull null
            Found(hu, url, Extract.mediaTitle(hu, ""), kind, ref = url, extra = "")
        }
    }

    /** Cria o WebView na janela, roda [drive] e devolve o JsPage (ou null em timeout/sem Activity). */
    private suspend fun drive(url: String, hardMs: Long, block: Boolean, pool: Semaphore = gate, slotWait: Long = 20_000, skipVisibleGate: Boolean = false, body: (WebView, MutableSet<String>, (JsPage) -> Unit) -> Unit): JsPage? {
        if (!url.startsWith("http", true)) return null
        if (!available()) { lastUnavailable = true; return null }
        lastUnavailable = false
        if (!acquire(pool, slotWait)) return null
        val vis = false
        return try {
            val act = activity?.get()?.takeIf { !it.isDestroyed && !it.isFinishing } ?: return null
            alive.incrementAndGet(); if (pool === queueGate) aliveQueue.incrementAndGet()
            try { withTimeoutOrNull(hardMs) {
                suspendCancellableCoroutine { cont ->
                    val finished = AtomicBoolean(false)
                    var holder: PassThrough? = null
                    var view: WebView? = null
                    fun cleanup() {
                        main.post {
                            try { view?.stopLoading(); (holder?.parent as? ViewGroup)?.removeView(holder); view?.destroy() } catch (_: Exception) {}
                            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                        }
                    }
                    fun done(p: JsPage?) {
                        if (!finished.compareAndSet(false, true)) return
                        cleanup()
                        if (cont.isActive) cont.resume(p)
                    }
                    cont.invokeOnCancellation { if (finished.compareAndSet(false, true)) cleanup() }
                    main.post {
                        try {
                            val hits = LinkedHashSet<String>()
                            val wv = buildView(act, hits, url, block)
                            view = wv
                            val box = PassThrough(act)
                            // transparente: o Android não desenha (nem compõe na GPU) a página oculta; ela continua
                            // "na tela" para o site carregar e tocar normalmente
                            box.alpha = 0f
                            holder = box
                            val dm = act.resources.displayMetrics
                            box.addView(wv, FrameLayout.LayoutParams(dm.widthPixels.coerceAtLeast(1280), dm.heightPixels.coerceAtLeast(720)))
                            val root = act.window?.decorView as? ViewGroup
                            if (root != null) root.addView(box, 0, ViewGroup.LayoutParams(dm.widthPixels.coerceAtLeast(1280), dm.heightPixels.coerceAtLeast(720)))
                            else { wv.measure(1080, 2200); wv.layout(0, 0, 1080, 2200) }
                            try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}
                            wv.loadUrl(url, mapOf("Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"))
                            body(wv, hits) { p -> done(p) }
                        } catch (_: Exception) { done(null) }
                    }
                }
            } } finally { alive.decrementAndGet(); if (pool === queueGate) aliveQueue.decrementAndGet() }
        } finally { pool.release(); if (vis) visibleGate.release() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private val API_PATH = Regex("""(?i)/(api|ajax|player|playback|video|videos|stream|source|sources|manifest|config|embed|media|play)s?(/|\.json|\?|$)|\.json(\?|$)|[?&](action|ajax)=""")
    private val STATIC = Regex("""(?i)\.(css|js|mjs|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|eot|map|avif)(\?|$)""")

    private fun buildView(act: Activity, hits: MutableSet<String>, pageUrl: String, block: Boolean): WebView {
        val wv = WebView(act)
        val s = wv.settings
        s.javaScriptEnabled = true
        try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        try { wv.resumeTimers() } catch (_: Exception) {}
        try { s.javaScriptCanOpenWindowsAutomatically = false } catch (_: Exception) {}
        try { s.userAgentString = Net.userAgent } catch (_: Exception) {}
        wv.isFocusable = false
        wv.isClickable = false
        Quiet.hidden(wv)
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, MseHookScript.SCRIPT, setOf("*")) } catch (_: Exception) {}
        }
        fun isHit(u: String): Boolean {
            if (u.isBlank() || u.startsWith("blob:", true) || u.startsWith("data:", true)) return false
            if (MediaPick.isAdUrl(u)) return false
            if (Extract.isPhotoUrl(u) && !Extract.isVideoUrl(u)) return false
            return Extract.mediaKindOf(u) != null || Extract.isHls(u) || Extract.isDash(u)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: android.webkit.WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean =
                CrashLog.rendererGone("renderizador", view, detail)
            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                try { view.evaluateJavascript(MseHookScript.SCRIPT) {} } catch (_: Exception) {}
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                val hdrs = request.requestHeaders ?: emptyMap()
                if (u.startsWith("http", true) && (MseCapture.hasSpecialHeaders(hdrs) || Extract.isDash(u) || Extract.isHls(u) || Extract.isSegmentUrl(u) || hdrs.keys.any { it.equals("Range", true) })) {
                    MseCapture.recordRequest(pageUrl, u, request.method ?: "GET", hdrs, source = "jsrender")
                }
                if (isHit(u)) {
                    synchronized(hits) { hits += u }
                    PlayCapture.record(u, pageUrl, hdrs["Referer"] ?: pageUrl)
                }
                else if (br.geracaoai.baixartg.web.AdBlock.shouldBlock(u, pageUrl, request.isForMainFrame)) return br.geracaoai.baixartg.web.AdBlock.empty()
                else if (!block && !request.isForMainFrame && u.startsWith("http") && !STATIC.containsMatchIn(u.substringBefore('#')) && API_PATH.containsMatchIn(u) &&
                    !br.geracaoai.baixartg.web.AdBlock.isAdHost(request.url?.host.orEmpty())) {
                    synchronized(hits) { if (hits.count { it.startsWith("api:") } < 20) hits += "api:$u" }
                }
                return super.shouldInterceptRequest(v, request)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url?.toString().orEmpty()
                val sch = request.url?.scheme?.lowercase().orEmpty()
                if (sch != "http" && sch != "https") return true
                // página oculta: bloqueia redirecionamento/popup de anúncio
                return br.geracaoai.baixartg.web.AdBlock.blockNavigation(target, pageUrl, request.hasGesture(), request.isRedirect)
            }
        }
        return wv
    }

    private fun unescapeJs(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try { (org.json.JSONTokener(raw).nextValue() as? String)?.ifBlank { null } } catch (_: Exception) { null }
    }
}
