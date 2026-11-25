package br.geracaoai.baixartg.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import br.geracaoai.baixartg.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Baixa vídeos protegidos por desafio JS / MSE / cabeçalhos especiais usando o próprio WebView
 * (mesmos cookies no CookieManager e mesmo User-Agent do Navegador).
 *
 * Ordem de preferência dentro da página:
 *  1) Manifest (DASH .mpd / HLS .m3u8) capturado com todos os cabeçalhos → baixa maior qualidade e remuxa para MP4.
 *  2) Stream/segmentos por faixa (Content-Range) com os mesmos cabeçalhos → baixa sequencialmente até o fim.
 *  3) Último recurso: captura bytes do appendBuffer por trilha via @JavascriptInterface (playbackRate alto + seek) → monta fMP4/MP4.
 */
object BrowserMseDownloader {
    @Volatile private var appCtx: Context? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val activeViews = AtomicInteger(0)
    @Volatile var lastMemoryWarning: String? = null

    fun init(c: Context) { appCtx = c.applicationContext }

    fun effectiveConcurrency(desired: Int, availBytes: Long, totalBytes: Long, lowMemory: Boolean): Pair<Int, String?> {
        val d = desired.coerceIn(1, 10)
        return when {
            lowMemory || (availBytes in 1 until (250L shl 20)) -> {
                val cap = minOf(d, 2)
                cap to if (cap < d) "Memória baixa: limitando a $cap navegadores simultâneos" else null
            }
            (availBytes in 1 until (450L shl 20)) || (totalBytes > 0 && availBytes * 100 / totalBytes < 12) -> {
                val cap = minOf(d, 4)
                cap to if (cap < d) "Memória reduzida: limitando a $cap navegadores simultâneos" else null
            }
            else -> d to null
        }
    }

    private fun currentCap(): Pair<Int, String?> {
        val desired = try { Prefs.parDown.coerceIn(1, 10) } catch (_: Exception) { 10 }
        val ctx = appCtx ?: return desired to null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return desired to null
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            effectiveConcurrency(desired, mi.availMem, mi.totalMem, mi.lowMemory)
        } catch (_: Exception) {
            desired to null
        }
    }

    private suspend fun acquireSlot(onStatus: (String) -> Unit): String? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (cap, warn) = currentCap()
            lastMemoryWarning = warn
            if (warn != null) onStatus(warn)
            val cur = activeViews.get()
            if (cur < cap && activeViews.compareAndSet(cur, cur + 1)) return warn
            delay(250)
        }
    }

    private fun releaseSlot() {
        activeViews.decrementAndGet()
    }

    private class TrackSink(
        val id: String,
        val mime: String,
        val kind: TrackKind,
        val file: File,
    ) {
        val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
        var hasInit = false
        var bytes = 0L
        @Volatile var closed = false
        @Synchronized fun write(data: ByteArray) {
            if (closed || data.isEmpty()) return
            hasInit = Mp4Boxes.appendFmp4Clean(hasInit, data, out)
            bytes += data.size
        }
        @Synchronized fun close() {
            if (closed) return
            closed = true
            try { out.flush() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
        }
    }

    private class Bridge(
        private val pageUrl: String,
        private val trackDir: File,
        private val onActivity: () -> Unit,
    ) {
        val tracks = ConcurrentHashMap<String, TrackSink>()
        val totalBytes = AtomicLong(0)
        val mseActive = AtomicBoolean(false)

        @JavascriptInterface
        fun onTrack(id: String, mime: String) {
            mseActive.set(true)
            onActivity()
            MseCapture.recordMse(pageUrl, id, mime)
            ensureTrack(id, mime)
        }

        @JavascriptInterface
        fun onReq(json: String) {
            onActivity()
            MseCapture.ingestJsReqJson(pageUrl, json)
        }

        @JavascriptInterface
        fun onChunk(trackId: String, mime: String, @Suppress("UNUSED_PARAMETER") seq: Int, b64: String, @Suppress("UNUSED_PARAMETER") isLast: Boolean) {
            if (b64.isBlank()) return
            mseActive.set(true)
            onActivity()
            val sink = ensureTrack(trackId, mime)
            try {
                val raw = Base64.decode(b64, Base64.DEFAULT)
                sink.write(raw)
                totalBytes.addAndGet(raw.size.toLong())
            } catch (_: Exception) {}
        }

        private fun ensureTrack(id: String, mime: String): TrackSink {
            val tid = id.ifBlank { "sb_1" }
            return tracks.getOrPut(tid) {
                val kind = MseCapture.trackKindOf(mime)
                val f = File(trackDir, "mse_${tid}_${System.nanoTime()}.mp4")
                TrackSink(tid, mime, kind, f)
            }
        }

        fun closeAll() { tracks.values.forEach { it.close() } }
        fun deleteAll() { closeAll(); tracks.values.forEach { it.file.delete() } }
    }

    private class PassThrough(c: Context) : FrameLayout(c) {
        override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
    }

    private fun tapCenter(wv: WebView) {
        try {
            val w = wv.width.takeIf { it > 0 } ?: 1080
            val h = wv.height.takeIf { it > 0 } ?: 720
            val x = w / 2f
            val y = h / 2f
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(down)
            wv.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        } catch (_: Exception) {}
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun download(
        f: Found,
        dir: File,
        maxHeight: Int = 0,
        progress: (Long, Long) -> Unit,
        onStatus: (String) -> Unit = {},
    ): File {
        val pageUrl = f.page.ifBlank { f.url }
        if (!pageUrl.startsWith("http", true)) throw DlException("Endereço inválido para o navegador")

        // Se já temos manifest capturado desta página (do Navegador visível), tenta direto antes de abrir outro WebView
        val preHint = MseCapture.snapshot(pageUrl)
        val preDash = preHint.manifests.lastOrNull { MseCapture.isDash(it) }
        if (preDash != null) {
            try {
                return MseDownloader.downloadDash(preDash, pageUrl, dir, f.title, maxHeight, progress = progress)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        val preHls = preHint.manifests.lastOrNull { MseCapture.isHls(it) }
        if (preHls != null) {
            try {
                return Downloader.hls(preHls.url, pageUrl, dir, f.title, progress, maxHeight = maxHeight, headers = preHls.headers)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        val ctx = JsRender.hostActivity() ?: appCtx ?: throw DlException("Navegador indisponível no momento")
        val warn = acquireSlot(onStatus)
        if (warn != null) progress(0L, -1L)

        val workDir = File(dir, "mse_${System.nanoTime()}").apply { mkdirs() }
        val lastBeat = AtomicLong(System.currentTimeMillis())
        val finalBridge = Bridge(pageUrl, workDir) { lastBeat.set(System.currentTimeMillis()) }
        var holder: PassThrough? = null
        var webView: WebView? = null
        val closed = AtomicBoolean(false)

        fun destroyWeb() {
            if (!closed.compareAndSet(false, true)) return
            main.post {
                try {
                    webView?.stopLoading()
                    webView?.removeJavascriptInterface("__bptMse")
                    (holder?.parent as? ViewGroup)?.removeView(holder)
                    webView?.destroy()
                } catch (_: Exception) {}
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            }
        }

        try {
            val started = AtomicBoolean(false)
            withContext(Dispatchers.Main) {
                val act = JsRender.hostActivity()
                val wv = WebView(act ?: ctx)
                Quiet.hidden(wv)
                webView = wv
                val s = wv.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = true
                s.databaseEnabled = true
                s.mediaPlaybackRequiresUserGesture = false
                s.loadsImagesAutomatically = true
                s.blockNetworkImage = false
                try { s.userAgentString = Net.userAgent } catch (_: Exception) {}
                try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}

                wv.addJavascriptInterface(finalBridge, "__bptMse")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    try { WebViewCompat.addDocumentStartJavaScript(wv, MseHookScript.SCRIPT, setOf("*")) } catch (_: Exception) {}
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: android.webkit.WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean =
                        CrashLog.rendererGone("download", view, detail)
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        lastBeat.set(System.currentTimeMillis())
                        try { view.evaluateJavascript(MseHookScript.SCRIPT) {} } catch (_: Exception) {}
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        lastBeat.set(System.currentTimeMillis())
                        try { view.evaluateJavascript(MseHookScript.SCRIPT) {} } catch (_: Exception) {}
                        try { view.evaluateJavascript(JsRender.PLAY_JS_SCRIPT) {} } catch (_: Exception) {}
                        tapCenter(view)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url?.toString().orEmpty()
                        if (br.geracaoai.baixartg.web.AdBlock.shouldBlock(u, pageUrl, request.isForMainFrame)) {
                            return br.geracaoai.baixartg.web.AdBlock.empty()
                        }
                        if (u.startsWith("http", true)) {
                            val hdrs = request.requestHeaders ?: emptyMap()
                            val isInteresting = MseCapture.hasSpecialHeaders(hdrs) ||
                                Extract.isHls(u) || Extract.isDash(u) ||
                                Extract.mediaKindOf(u) != null || Extract.isSegmentUrl(u) ||
                                hdrs.keys.any { it.equals("Range", true) }
                            if (isInteresting) {
                                lastBeat.set(System.currentTimeMillis())
                                MseCapture.recordRequest(pageUrl, u, request.method ?: "GET", hdrs, source = "wv-intercept")
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url?.toString().orEmpty()
                        val sch = request.url?.scheme?.lowercase().orEmpty()
                        if (sch != "http" && sch != "https") return true
                        return br.geracaoai.baixartg.web.AdBlock.blockNavigation(target, pageUrl, request.hasGesture(), request.isRedirect)
                    }
                }

                if (act != null) {
                    val box = PassThrough(act).apply { alpha = 0f }
                    holder = box
                    val dm = act.resources.displayMetrics
                    box.addView(wv, FrameLayout.LayoutParams(dm.widthPixels.coerceAtLeast(1280), dm.heightPixels.coerceAtLeast(720)))
                    val root = act.window?.decorView as? ViewGroup
                    if (root != null) root.addView(box, 0, ViewGroup.LayoutParams(dm.widthPixels.coerceAtLeast(1280), dm.heightPixels.coerceAtLeast(720)))
                    else { wv.measure(1080, 2200); wv.layout(0, 0, 1080, 2200) }
                } else {
                    wv.measure(1280, 720); wv.layout(0, 0, 1280, 720)
                }
                wv.loadUrl(pageUrl, mapOf("Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"))
                started.set(true)
            }

            // Fase 1 (até 25s): espera desafio JS passar, dá toque/play, e observa manifests / ranges / MSE
            val discoverStart = System.currentTimeMillis()
            var playedAt = 0L
            var manifestWaitUntil = 0L
            while (System.currentTimeMillis() - discoverStart < 26_000L) {
                currentCoroutineContext().ensureActive()
                delay(600)

                val wv = webView ?: break
                val chal = withContext(Dispatchers.Main) {
                    evalString(wv, "(function(){var t=document.title||'';return (/just a moment|attention required|checking your browser|verify you are human|um momento/i.test(t)||!!document.querySelector('#challenge-form,#challenge-stage,[name=cf-turnstile-response]'))?'CH':'OK'})()")
                }
                if (chal == "CH") {
                    MseCapture.recordChallenge(pageUrl)
                    lastBeat.set(System.currentTimeMillis())
                    continue
                }

                val now = System.currentTimeMillis()
                if (playedAt == 0L || now - playedAt >= 3_000L) {
                    playedAt = now
                    withContext(Dispatchers.Main) {
                        try {
                            wv.evaluateJavascript(MseHookScript.SCRIPT) {}
                            wv.evaluateJavascript(AgeGate.BYPASS_JS) { agRaw ->
                                if (agRaw != null && agRaw.contains("\"clicked\":1")) {
                                    lastBeat.set(System.currentTimeMillis())
                                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                                }
                            }
                            wv.evaluateJavascript(JsRender.PLAY_JS_SCRIPT) {}
                            tapCenter(wv)
                        } catch (_: Exception) {}
                    }
                }

                // Puxa reqLog do JS como garantia além do @JavascriptInterface
                val reqLogRaw = withContext(Dispatchers.Main) {
                    evalString(wv, "(function(){try{var a=window.__bptReqLog||[];if(!a.length)return '';window.__bptReqLog=[];return JSON.stringify(a)}catch(e){return ''}})()")
                }
                if (!reqLogRaw.isNullOrBlank()) MseCapture.ingestJsReqLog(pageUrl, reqLogRaw)

                val hint = MseCapture.snapshot(pageUrl)
                if (hint.manifests.isNotEmpty()) {
                    if (manifestWaitUntil == 0L) manifestWaitUntil = now + 1_400L
                    if (now >= manifestWaitUntil) break
                } else if (hint.rangesOrDirect.isNotEmpty() && !finalBridge.mseActive.get() && now - discoverStart > 4_000L) {
                    break
                } else if (finalBridge.mseActive.get() && now - discoverStart > 5_000L && hint.segments.isEmpty() && hint.rangesOrDirect.isEmpty()) {
                    // MSE puro sem manifest nem range URL único: entra direto na captura de appendBuffer
                    break
                }
            }

            // Drena reqLog final antes da decisão
            webView?.let { wv ->
                val tail = withContext(Dispatchers.Main) {
                    evalString(wv, "(function(){try{var a=window.__bptReqLog||[];window.__bptReqLog=[];return JSON.stringify(a)}catch(e){return ''}})()")
                }
                if (!tail.isNullOrBlank()) MseCapture.ingestJsReqLog(pageUrl, tail)
            }

            val hint = MseCapture.snapshot(pageUrl)

            // Prioridade 1: Manifest DASH (.mpd) ou HLS (.m3u8) com TODOS os cabeçalhos capturados
            val dash = hint.manifests.lastOrNull { MseCapture.isDash(it) }
            if (dash != null) {
                destroyWeb()
                try {
                    return MseDownloader.downloadDash(dash, pageUrl, dir, f.title, maxHeight, progress = progress)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            val hls = hint.manifests.lastOrNull { MseCapture.isHls(it) }
            if (hls != null) {
                destroyWeb()
                try {
                    return Downloader.hls(hls.url, pageUrl, dir, f.title, progress, maxHeight = maxHeight, headers = hls.headers)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            // Prioridade 2: Stream por faixas (Content-Range / vídeo direto com cabeçalhos especiais)
            val ranged = hint.rangesOrDirect.filter {
                !MediaPick.isAdUrl(it.url) &&
                    (!finalBridge.mseActive.get() || it.contentRange.contains("bytes", true) || Extract.mediaKindOf(it.url) == Kind.DIRECT)
            }
            if (ranged.isNotEmpty() && !(finalBridge.mseActive.get() && hint.segments.size >= 2)) {
                val picked = MediaPick.pick(ranged.map { MediaPick.Cand(it.url, pageUrl = pageUrl) })
                val bestReq = ranged.firstOrNull { it.url == picked?.url } ?: ranged.last()
                val audioReq = ranged.firstOrNull {
                    it.url != bestReq.url && (it.contentType.startsWith("audio/", true) || it.url.contains("audio", true))
                }
                try {
                    destroyWeb()
                    if (audioReq != null) {
                        val vTmp = MseDownloader.downloadRanges(bestReq, pageUrl, workDir, f.title + "_v") { g, t -> progress(g, t) }
                        val aTmp = MseDownloader.downloadRanges(audioReq, pageUrl, workDir, f.title + "_a") { _, _ -> }
                        val out = File(dir, Downloader.safe(f.title) + "_" + System.nanoTime() + ".mp4")
                        return Remux.muxTracks(vTmp, aTmp, out)
                    }
                    val out = MseDownloader.downloadRanges(bestReq, pageUrl, dir, f.title, progress = progress)
                    if (out.length() >= 4096) return out
                    out.delete()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }

            // Prioridade 3 (último recurso): captura bytes do appendBuffer por trilha via @JavascriptInterface
            val wv = webView ?: throw DlException("Não foi possível capturar o vídeo pelo navegador")
            withContext(Dispatchers.Main) {
                try {
                    wv.evaluateJavascript("window.__bptCaptureBytes=true;window.__bptFlushInit&&window.__bptFlushInit();") {}
                } catch (_: Exception) {}
            }

            val capStart = System.currentTimeMillis()
            var lastBytes = finalBridge.totalBytes.get()
            var lastGrowthAt = capStart
            var endedCount = 0
            while (System.currentTimeMillis() - capStart < 180_000L) {
                currentCoroutineContext().ensureActive()
                delay(400)
                val stJson = withContext(Dispatchers.Main) { evalString(wv, MseHookScript.ADVANCE_PLAY_JS) }
                val now = System.currentTimeMillis()
                val curBytes = finalBridge.totalBytes.get()
                if (curBytes > lastBytes) {
                    lastBytes = curBytes
                    lastGrowthAt = now
                    progress(curBytes, -1L)
                }
                val ended = stJson?.contains("\"ended\":true") == true
                if (ended) {
                    endedCount++
                    if (endedCount >= 3 && now - lastGrowthAt >= 1_500L) break
                } else {
                    endedCount = 0
                }
                // sem novos bytes por 18s (se já capturou algo) ou 25s sem nada -> encerra
                val stallLimit = if (curBytes > 0) 18_000L else 25_000L
                if (now - lastGrowthAt > stallLimit) break
            }

            destroyWeb()
            finalBridge.closeAll()

            val nonEmpty = finalBridge.tracks.values.filter { it.file.exists() && it.bytes > 64 }
            if (nonEmpty.isEmpty()) {
                // última tentativa com lista de segmentos se houver URLs de segmentos capturadas
                if (hint.segments.size >= 2) {
                    return MseDownloader.downloadSegmentList(hint.segments, pageUrl, dir, f.title, progress = progress)
                }
                throw DlException("Navegador não capturou dados do player MSE")
            }

            val videoTrack = nonEmpty.firstOrNull { it.kind == TrackKind.VIDEO || it.kind == TrackKind.MUXED }
                ?: nonEmpty.firstOrNull { Mp4Boxes.detectTrackKind(it.file.readBytes(64 * 1024)) == TrackKind.VIDEO }
                ?: nonEmpty.maxByOrNull { it.bytes }!!
            val audioTrack = nonEmpty.firstOrNull { it.id != videoTrack.id && it.kind == TrackKind.AUDIO }
                ?: nonEmpty.firstOrNull { it.id != videoTrack.id && Mp4Boxes.detectTrackKind(it.file.readBytes(64 * 1024)) == TrackKind.AUDIO }

            val out = File(dir, Downloader.safe(f.title) + "_" + System.nanoTime() + ".mp4")
            return withContext(Dispatchers.IO) {
                Remux.muxTracks(videoTrack.file, audioTrack?.file, out)
            }
        } catch (e: Throwable) {
            destroyWeb()
            finalBridge.deleteAll()
            throw e
        } finally {
            destroyWeb()
            finalBridge.closeAll()
            workDir.deleteRecursively()
            releaseSlot()
        }
    }

    private fun File.readBytes(max: Int): ByteArray {
        val len = minOf(max.toLong(), length()).toInt().coerceAtLeast(0)
        val b = ByteArray(len)
        inputStream().use { it.read(b) }
        return b
    }

    private suspend fun evalString(wv: WebView, js: String): String? =
        withTimeoutOrNull(3_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(js) { raw ->
                    val s = if (raw.isNullOrBlank() || raw == "null") null
                    else try { (org.json.JSONTokener(raw).nextValue() as? String) ?: raw } catch (_: Exception) { raw }
                    if (cont.isActive) cont.resume(s) {}
                }
            }
        }
}
