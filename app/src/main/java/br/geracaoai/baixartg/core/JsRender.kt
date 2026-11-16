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
    private val gate = Semaphore(3)
    /** WebViews próprios da fila de envio: o painel do navegador nunca toma a vez da fila (e vice-versa). */
    private val queueGate = Semaphore(2)
    @Volatile var lastUnavailable = false

    private const val PLAY_JS = """
        (function(){
          function q(s){try{return document.querySelectorAll(s)}catch(e){return []}}
          function lbl(el){return ((el.innerText||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).replace(/\s+/g,' ').trim()}
          function consent(){
            var re=/^(aceitar|aceito|aceitar todos|accept|accept all|allow all|concordo|agree|i agree|entendi|got it|continuar|continue|fechar|close|dismiss|ok)$/i;
            q('button,[role=button],a[class*=close],[class*=consent] a,[class*=cookie] a').forEach(function(el){var t=lbl(el);if(t.length<25&&re.test(t)){try{el.click()}catch(e){}}});
          }
          function playText(){
            var re=/^(assistir|assista|assistir agora|assistir aula|assistir v[ií]deo|watch|watch now|watch video|play|play video|reproduzir|ver v[ií]deo|ver aula|iniciar|iniciar aula|come[cç]ar|start)$/i;
            q('button,a,[role=button],[class*=play],[class*=watch],[class*=assistir]').forEach(function(el){
              var t=lbl(el);if(!t||t.length>30||!re.test(t))return;
              if(el.tagName==='A'){var h=el.getAttribute('href')||'';if(window.__bptNav)return;
                if(h&&h.indexOf('http')===0&&h.indexOf(location.host)<0)return; window.__bptNav=1;}
              try{el.click()}catch(e){}
            });
          }
          function go(){
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
          }
          go();
          if(!window.__bptPlay){window.__bptPlay=setInterval(go, 1200);}
          return 'ok';
        })()
    """

    fun bind(act: Activity) { activity = WeakReference(act) }
    fun unbind(act: Activity) { if (activity?.get() === act) activity = null }
    fun available(): Boolean = activity?.get()?.let { !it.isDestroyed && !it.isFinishing } == true

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
        // com o navegador aberto na tela, a fila usa só 1 WebView oculto por vez (o navegador visível tem prioridade)
        page(url, timeoutMs, true, queueGate)   // vagas próprias da fila; o navegador tem as dele (gate) e nunca espera estas
    @Volatile var browserVisible = false
    private val queueSoloGate = Semaphore(1)

    private suspend fun page(url: String, timeoutMs: Long, play: Boolean, pool: Semaphore = gate): JsPage? {
        val wait = if (play) timeoutMs.coerceIn(8_000L, 18_000L) else timeoutMs
        return drive(url, wait + 3_000, !play, pool) { wv, hits, done ->
            val start = System.currentTimeMillis()
            var firstHitAt = 0L
            fun finish() {
                wv.evaluateJavascript(JsScripts.COLLECT) { raw ->
                    val c = JsScripts.parseCollected(raw)
                    c?.videos?.forEach { v -> if (Extract.mediaKindOf(v) != null) synchronized(hits) { hits += v } }
                    wv.evaluateJavascript("(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()") { rawHtml ->
                        done(JsPage(unescapeJs(rawHtml), mediaOf(hits, url), challenge = c?.challenge == true, collected = c,
                            apis = synchronized(hits) { hits.filter { it.startsWith("api:") }.map { it.removePrefix("api:") } }.take(12)))
                    }
                }
            }
            fun tick() {
                if (play && wv.progress >= 60) wv.evaluateJavascript(PLAY_JS) { }
                val n = synchronized(hits) { hits.count { !it.startsWith("api:") } }
                val now = System.currentTimeMillis()
                if (n > 0 && firstHitAt == 0L) firstHitAt = now
                if ((n > 0 && now - firstHitAt >= 1_500L) || now - start >= wait || (!play && wv.progress >= 100 && now - start > 2_500)) finish()
                else wv.postDelayed({ tick() }, 500)
            }
            wv.postDelayed({ tick() }, 800)
        }
    }

    private fun mediaOf(hits: Set<String>, url: String): List<Found> = synchronized(hits) { hits.filter { !it.startsWith("api:") } }.mapNotNull { hu ->
        val kind = Extract.mediaKindOf(hu) ?: return@mapNotNull null
        Found(hu, url, Extract.mediaTitle(hu, ""), kind, ref = url, extra = "")
    }

    /** Cria o WebView na janela, roda [drive] e devolve o JsPage (ou null em timeout/sem Activity). */
    private suspend fun drive(url: String, hardMs: Long, block: Boolean, pool: Semaphore = gate, body: (WebView, MutableSet<String>, (JsPage) -> Unit) -> Unit): JsPage? {
        if (!url.startsWith("http", true)) return null
        if (!available()) { lastUnavailable = true; return null }
        lastUnavailable = false
        return pool.withPermit {
            val act = activity?.get()?.takeIf { !it.isDestroyed && !it.isFinishing } ?: return@withPermit null
            withTimeoutOrNull(hardMs) {
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
                            holder = box
                            val dm = act.resources.displayMetrics
                            box.addView(wv, FrameLayout.LayoutParams(dm.widthPixels.coerceAtLeast(720), dm.heightPixels.coerceAtLeast(1280)))
                            val root = act.window?.decorView as? ViewGroup
                            if (root != null) root.addView(box, 0, ViewGroup.LayoutParams(dm.widthPixels.coerceAtLeast(720), dm.heightPixels.coerceAtLeast(1280)))
                            else { wv.measure(1080, 2200); wv.layout(0, 0, 1080, 2200) }
                            try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}
                            wv.loadUrl(url, mapOf("Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"))
                            body(wv, hits) { p -> done(p) }
                        } catch (_: Exception) { done(null) }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private val API_PATH = Regex("""(?i)/(api|ajax|player|playback|video|videos|stream|source|sources|manifest|config|embed|media|play|lesson|aula)s?(/|\.json|\?|$)|\.json(\?|$)|[?&](action|ajax)=""")
    private val STATIC = Regex("""(?i)\.(css|js|mjs|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|eot|map|avif)(\?|$)""")

    private fun buildView(act: Activity, hits: MutableSet<String>, pageUrl: String, block: Boolean): WebView {
        val wv = WebView(act)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        try { s.javaScriptCanOpenWindowsAutomatically = false } catch (_: Exception) {}
        try { s.userAgentString = Net.userAgent } catch (_: Exception) {}
        wv.isFocusable = false
        wv.isClickable = false
        fun isHit(u: String): Boolean {
            if (u.isBlank() || u.startsWith("blob:", true) || u.startsWith("data:", true)) return false
            if (Extract.isPhotoUrl(u) && !Extract.isVideoUrl(u)) return false
            return Extract.mediaKindOf(u) != null
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val u = request.url?.toString().orEmpty()
                if (isHit(u)) synchronized(hits) { hits += u }
                else if (block && br.geracaoai.baixartg.web.AdBlock.shouldBlock(u, pageUrl, request.isForMainFrame)) return br.geracaoai.baixartg.web.AdBlock.empty()
                else if (!block && !request.isForMainFrame && u.startsWith("http") && !STATIC.containsMatchIn(u.substringBefore('#')) && API_PATH.containsMatchIn(u) &&
                    !br.geracaoai.baixartg.web.AdBlock.isAdHost(request.url?.host.orEmpty())) {
                    synchronized(hits) { if (hits.count { it.startsWith("api:") } < 20) hits += "api:$u" }
                }
                return super.shouldInterceptRequest(v, request)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // nunca abre apps externos (intent://, market://); http(s) segue no próprio WebView
                val sch = request.url?.scheme?.lowercase().orEmpty()
                return sch != "http" && sch != "https"
            }
        }
        return wv
    }

    private fun unescapeJs(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try { (org.json.JSONTokener(raw).nextValue() as? String)?.ifBlank { null } } catch (_: Exception) { null }
    }
}
