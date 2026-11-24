package br.geracaoai.baixartg.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.web.WebCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

/**
 * Botão de vídeos/fotos detectados, isolado: o relógio de 1 s e as contagens da lista só redesenham ESTE botão
 * (na 1.8.9 o relógio redesenhava a tela inteira do navegador a cada segundo, contando a lista toda duas vezes).
 */
@Composable
private fun DetectFab(modifier: Modifier, show: Boolean, onOpen: () -> Unit) {
    val mode by BrowserDetect.mode
    val detected by remember { derivedStateOf { if (BrowserDetect.mode.intValue == 1) 0 else BrowserDetect.list.count { !it.sent } } }
    val marked by remember { derivedStateOf { BrowserDetect.list.count { it.sel && !it.sent } } }
    val phN by remember { derivedStateOf { BrowserDetect.photos.size } }
    val pendingN by BrowserDetect.pending
    val photoBusy by BrowserDetect.photoBusy
    val already by BrowserDetect.alreadyHere
    val pageAt by BrowserDetect.pageAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis(); BrowserDetect.watchLoading(now) } }
    val working = (if (mode == 1) 0 else pendingN) + (if (mode != 0) photoBusy else 0)
    // "Procurando" tem prazo: sem nada novo em 25 s, diz que não há nada novo
    val searching = detected == 0 && phN == 0 && working > 0 && (pageAt == 0L || now - pageAt < 25_000)
    val none = when (mode) { 1 -> "Nenhuma foto nova"; 2 -> "Nenhum vídeo ou foto novo"; else -> "Nenhum vídeo novo" }
    // sempre visível numa página aberta e o toque SEMPRE abre o painel (modo Vídeos/Fotos, marcar, baixar).
    // Antes: com nada achado o toque copiava o diagnóstico e não dava pra trocar de modo.
    if (show || detected > 0 || working > 0 || phN > 0 || already > 0) Box(modifier, contentAlignment = Alignment.BottomEnd) {
        ExtendedFloatingActionButton(onClick = onOpen,
            icon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.VideoLibrary, null) },
            text = { Text(if (detected > 0) (if (BrowserDetect.term.value.isNotBlank()) "$marked de $detected marcados" else "$detected vídeos") + (if (pendingN > 0) " (+$pendingN…)" else "") + (if (mode == 2 && phN > 0) " • $phN fotos" else "")
                else if (phN > 0) "$phN fotos" + (if (photoBusy > 0) " (+…)" else "") else if (searching) (if (mode == 1) "Procurando fotos…" else "Procurando vídeos…")
                else "$none nesta página" + (if (already > 0 && mode != 1) " • $already já na fila/enviados" else "")) })
    }
}

/** Leva um link do Navegador para Filtrar ou Varrer tudo (a busca só começa quando o usuário tocar no botão). */
object Handoff { val page = mutableStateOf<String?>(null); val site = mutableStateOf<String?>(null) }

// ---------------- Navegador ----------------

/**
 * WebView único do Navegador, vivo no nível do app (fora da recomposição).
 * Trocar de aba só esconde; não destroi, não recarrega, preserva página, scroll, login e histórico de voltar.
 */
object BrowserRuntime {
    @Volatile var web: WebView? = null
        private set
    @Volatile private var started = false
    fun adopt(w: WebView) { if (web !== w) web = w }
    fun detach() { web?.let { v -> (v.parent as? ViewGroup)?.removeView(v) } }
    fun goBack(): Boolean {
        val w = web ?: return false
        if (!w.canGoBack()) return false
        w.goBack(); return true
    }
    fun onEnter() {
        if (started) return
        started = true
        BrowserDetect.warm()
        val u = br.geracaoai.baixartg.Prefs.browserUrl.ifBlank { br.geracaoai.baixartg.Prefs.lastUrl }
        if (u.startsWith("http")) BrowserDetect.restore(Extract.host(u))
    }
    fun release() {
        try { web?.destroy() } catch (_: Exception) {}
        web = null; started = false
    }
    /** Página aberta agora no navegador visível (lida por threads de rede). */
    @Volatile var pageUrl: String = ""
    @Volatile private var swInstalled = false
    /**
     * Pedidos feitos pelo service worker do site não passam pelo WebViewClient: sites que buscam a lista .m3u8
     * por ele não mostravam vídeo nenhum até o play. Aqui eles entram na mesma detecção do navegador.
     */
    fun installServiceWorkerHook() {
        if (swInstalled) return
        swInstalled = true
        try {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
                !androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
            androidx.webkit.ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    try {
                        val pg = pageUrl
                        // só atribui à página visível quando não há páginas ocultas abertas (elas também usam service worker)
                        if (!pg.startsWith("http") || !br.geracaoai.baixartg.core.JsRender.browserVisible || br.geracaoai.baixartg.core.JsRender.alive.get() > 0) return null
                        val u = request.url?.toString().orEmpty()
                        if (!u.startsWith("http")) return null
                        val hdrs = request.requestHeaders ?: emptyMap()
                        BrowserDiag.media("sw", u)
                        if (MseCapture.hasSpecialHeaders(hdrs) || Extract.isDash(u) || Extract.isHls(u)) MseCapture.recordRequest(pg, u, request.method ?: "GET", hdrs, source = "browser-sw")
                        if (Extract.mediaKindOf(u) != null && !(Extract.isPhotoUrl(u) && !Extract.isVideoUrl(u)) && !br.geracaoai.baixartg.core.MediaPick.isAdUrl(u)) {
                            BrowserDetect.intercepted(u, pg, "")
                            br.geracaoai.baixartg.core.PlayCapture.record(u, pg, hdrs["Referer"] ?: pg)
                        }
                    } catch (_: Throwable) {}
                    return null
                }
            })
        } catch (e: Throwable) { br.geracaoai.baixartg.core.CrashLog.write("service worker: gancho não instalado", e) }
    }
    /** Muda quando o WebView precisa ser recriado (o renderizador caiu). */
    val gen = androidx.compose.runtime.mutableIntStateOf(0)
    /** Página para abrir no WebView recriado. */
    @Volatile var reloadUrl: String? = null
    private val falls = ArrayDeque<Long>()
    /** O renderizador do navegador caiu: descarta o WebView morto e pede um novo. Retorna o aviso para a tela. */
    fun onRendererGone(dead: WebView?, url: String): String {
        if (web === dead || dead == null) web = null
        val now = System.currentTimeMillis()
        falls.addLast(now); while (falls.isNotEmpty() && now - falls.first() > 90_000) falls.removeFirst()
        val again = falls.size < 3 && url.startsWith("http")
        reloadUrl = if (again) url else null
        br.geracaoai.baixartg.core.CrashLog.mark("navegador recriado (${if (again) "recarregando" else "sem recarregar: caiu ${falls.size}x"})")
        gen.intValue++
        return if (again) "A página travou o navegador. Recarreguei sem fechar o app."
        else "Esta página derrubou o navegador ${falls.size} vezes seguidas. Não recarreguei para não travar de novo."
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(visible: Boolean = true, onLeaveTab: () -> Unit = {}, onUse: (url: String, site: Boolean) -> Unit) {
    var web by remember { mutableStateOf(BrowserRuntime.web) }
    val initialAddr = remember { BrowserRuntime.web?.url?.takeIf { it.isNotBlank() } ?: br.geracaoai.baixartg.Prefs.browserUrl.ifBlank { br.geracaoai.baixartg.Prefs.lastUrl }.ifBlank { "https://" } }
    var addrField by remember { mutableStateOf(TextFieldValue(initialAddr, TextRange(initialAddr.length))) }
    var addrFocused by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(BrowserRuntime.web?.url.orEmpty()) }
    val focusManager = LocalFocusManager.current
    fun syncAddress(u: String) {
        if (u.isBlank() || addrFocused) return
        if (addrField.text != u) addrField = TextFieldValue(u, TextRange(u.length))
    }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(BrowserRuntime.web?.canGoBack() == true) }; var canFwd by remember { mutableStateOf(BrowserRuntime.web?.canGoForward() == true) }
    var ask by remember { mutableStateOf(false) }
    var analyze by remember { mutableStateOf(false) }
    BackHandler(enabled = visible) {
        when (BrowserNav.onBack(web?.canGoBack() == true)) {
            BrowserNav.BackAction.WEBVIEW -> web?.goBack()
            BrowserNav.BackAction.PREVIOUS_TAB -> onLeaveTab()
        }
    }
    fun go(t: String) {
        val u = BrowserAddress.toLoadUrl(t)
        focusManager.clearFocus()
        addrFocused = false
        if (u.isNotEmpty()) {
            addrField = TextFieldValue(u, TextRange(u.length))
            web?.loadUrl(u)
        } else if (current.startsWith("http")) {
            addrField = TextFieldValue(current, TextRange(current.length))
        }
    }
    fun savePanel() { val v = BrowserDetect.list.toList(); val p = BrowserDetect.photos.toList(); val h = BrowserDetect.loadedHostSet()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { BrowserStore.savePanel(v, p, h) } }
    DisposableEffect(visible) {
        JsRender.browserVisible = visible
        if (visible) BrowserRuntime.onEnter()
        onDispose {
            JsRender.browserVisible = false
            savePanel()
            try { CookieManager.getInstance().flush() } catch (_: Exception) {}
            // não destroi o WebView: ele vive em BrowserRuntime e é só reanexado na próxima vez
        }
    }
    // grava a lista do painel no disco quando muda (sobrevive a fechar/atualizar o app)
    LaunchedEffect(Unit) {
        var sig = 0
        while (true) {
            kotlinx.coroutines.delay(4_000)
            val n = BrowserDetect.list.fold(BrowserDetect.list.size) { a, d -> 31 * a + (d.key.hashCode() xor (if (d.sel) 1 else 0) xor (d.state shl 2) xor d.f.url.hashCode()) } + BrowserDetect.photos.size * 7
            if (n != sig) { sig = n; savePanel() }
        }
    }
    // trilha: teclado abrindo/fechando no navegador (ajuda a achar quedas ao digitar em formulários)
    val imeIns = WindowInsets.ime; val dens = androidx.compose.ui.platform.LocalDensity.current
    val imeOpen by remember(imeIns, dens) { derivedStateOf { imeIns.getBottom(dens) > 0 } }
    LaunchedEffect(imeOpen) { if (visible) br.geracaoai.baixartg.core.CrashLog.mark(if (imeOpen) "navegador: teclado aberto" else "navegador: teclado fechado") }
    var showSites by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sheet by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // Coleta: no máximo 1 por vez e no máximo a cada ~1,2 s (antes: a cada rolagem + a cada 2,5 s, sem esperar a anterior
    // terminar, rodando o script de vídeo e o de foto juntos). JSON lido fora da thread da tela.
    val collectState = remember { object { var busy = false; var lastAt = 0L; var photoTurn = 0; var photoAt = 0L; var epoch = -1 } }
    val collect = remember { Runnable {
        val v = web ?: return@Runnable
        val page = v.url ?: return@Runnable
        if (!page.startsWith("http")) return@Runnable
        val now = System.currentTimeMillis()
        if (collectState.busy && now - collectState.lastAt < 10_000) return@Runnable
        collectState.busy = true; collectState.lastAt = now
        // só o que mudou desde a última vez (nada, se a página não mudou)
        val full = BrowserDetect.fullCollect.also { if (it) BrowserDetect.fullCollect = false }
        v.evaluateJavascript("(function(){try{var r=window.__bptReqLog||[];window.__bptReqLog=[];var m=window.__bptMseActive?1:0;var t=window.__bptMseTracks||[];return JSON.stringify({m:m,r:r,t:t})}catch(e){return '{}'}})()") { mraw ->
            collectScope.launch {
                try {
                    val ms = try { (org.json.JSONTokener(mraw ?: "").nextValue() as? String) } catch (_: Exception) { null } ?: mraw ?: "{}"
                    val mo = org.json.JSONObject(ms)
                    if (mo.optInt("m") == 1) MseCapture.recordMse(page, "sb_vis", "video/mp4")
                    mo.optJSONArray("t")?.let { ta -> for (i in 0 until ta.length()) { val to = ta.optJSONObject(i) ?: continue; MseCapture.recordMse(page, to.optString("id"), to.optString("mime")) } }
                    mo.optJSONArray("r")?.let { MseCapture.ingestJsReqLog(page, it.toString()) }
                } catch (_: Exception) {}
            }
        }
        v.evaluateJavascript(if (full) JsScripts.COLLECT else JsScripts.COLLECT_INC) { raw ->
            BrowserDiag.timed(System.currentTimeMillis() - now)
            collectScope.launch {
                val c = JsScripts.parseCollected(raw)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try { c?.takeIf { !it.same }?.let { BrowserDetect.onCollected(page, it) } } catch (_: Exception) {}
                    collectState.busy = false
                }
            }
        }
    } }
    // Fotos: corrida PRÓPRIA, independente da coleta de vídeos (antes só rodava no fim da coleta de vídeos e, se aquela
    // resposta atrasasse ou se perdesse, o script de fotos nunca rodava: "<img> na página=-1"). Prazo de 8 s por resposta.
    val photoState = remember { object { var busy = false; var at = 0L; var epoch = -1 } }
    val photoRun = remember { Runnable {
        val v = web ?: return@Runnable
        val page = v.url ?: return@Runnable
        if (!page.startsWith("http") || !BrowserDetect.photosOn.value) return@Runnable
        val now = System.currentTimeMillis()
        if (photoState.busy && now - photoState.at < 8_000) return@Runnable
        photoState.busy = true; photoState.at = now
        BrowserDiag.photoCalls++
        // v1.9.5: coletor simples (PhotoGrab): devolve as fotos visíveis; o app só tira lixo e repetidos
        v.evaluateJavascript(PhotoGrab.JS) { praw ->
            collectScope.launch {
                try {
                    BrowserDiag.photoReplies++
                    val r = PhotoGrab.parse(praw)
                    if (r.err.isNotBlank()) { BrowserDiag.photoErrs++; BrowserDiag.lastPhotoErr = r.err }
                    if (r.ok && !r.same) {
                        BrowserDiag.lastImgs = r.imgs; BrowserDiag.lastRaw = r.raw; BrowserDiag.lastCands = r.cands.size
                        r.rejects.forEach { (k, x) -> BrowserDiag.rej(k, x) }
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            try { BrowserDetect.onGrab(page, r.cands) } catch (e: Throwable) { BrowserDiag.lastPhotoErr = "app: " + e.javaClass.simpleName + " " + (e.message ?: "").take(120) }
                        }
                    }
                } catch (e: Throwable) { BrowserDiag.lastPhotoErr = "app: " + e.javaClass.simpleName + " " + (e.message ?: "").take(120) }
                finally { withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main) { photoState.busy = false } }
            }
        }
    } }
    // Lista de vídeos que o player pede por fetch/XHR (anotada pelo gancho na página, inclusive quando quem responde é o
    // service worker). Antes só era lida no "Procurar": a página mostrava 0 vídeos até o play. Agora é lida sozinha,
    // a cada 2,5 s, por até 5 min depois de abrir a página. Barato: só devolve o que é novo.
    val netPoll = remember { object : Runnable {
        var left = 0
        override fun run() {
            val v = web ?: return
            val page = v.url ?: return
            if (!page.startsWith("http") || left <= 0) return
            left--
            v.evaluateJavascript("(function(){try{var n=window.__bptNet||[];var i=window.__bptNetRd||0;window.__bptNetRd=n.length;var c=window.__bptNetCt||[];var j=window.__bptNetCtRd||0;window.__bptNetCtRd=c.length;return JSON.stringify({n:n.slice(i),c:c.slice(j),h:window.__bptMseHook?1:0,m:window.__bptMseActive?1:0})}catch(e){return '{}'}})()") { raw ->
                try {
                    val js = org.json.JSONTokener(raw ?: "").nextValue() as? String ?: "{}"
                    val o = org.json.JSONObject(js)
                    if (o.optInt("h") == 0) BrowserDiag.inc("gancho da página ausente")
                    if (o.optInt("m") == 1) BrowserDiag.inc("player usa MediaSource")
                    val arr = o.optJSONArray("n") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val u = arr.optString(i)
                        if (!u.startsWith("http")) continue
                        BrowserDiag.media("página", u)
                        if (!br.geracaoai.baixartg.core.MediaPick.isAdUrl(u)) BrowserDetect.intercepted(u, page, v.title ?: "")
                    }
                    // listas sem ".m3u8"/".mpd" no endereço, reconhecidas pelo Content-Type da resposta
                    val ct = o.optJSONArray("c") ?: org.json.JSONArray()
                    for (i in 0 until ct.length()) {
                        val e = ct.optJSONObject(i) ?: continue
                        val u = e.optString("u"); val t = e.optString("t")
                        if (!u.startsWith("http")) continue
                        BrowserDiag.media("página", u, t)
                        val k = if (t.contains("mpegurl", true)) Kind.HLS else if (t.contains("dash", true)) Kind.SERVER else null
                        if (k != null && !br.geracaoai.baixartg.core.MediaPick.isAdUrl(u)) BrowserDetect.intercepted(u, page, v.title ?: "", k)
                    }
                } catch (_: Exception) {}
            }
            if (left > 0) handler.postDelayed(this, 2_500)
        }
    } }
    // a cada 2,5 s por até 5 min na mesma página (aulas e players que só carregam depois de um clique)
    fun startNetPoll() { handler.removeCallbacks(netPoll); netPoll.left = 120; handler.postDelayed(netPoll, 1_500) }
    fun scheduleCollect(delayMs: Long = 1_200) { handler.removeCallbacks(collect); handler.postDelayed(collect, delayMs)
        if (BrowserDetect.photosOn.value) { handler.removeCallbacks(photoRun); handler.postDelayed(photoRun, delayMs + 300) } }
    val mode by BrowserDetect.mode
    val detected by remember { derivedStateOf { if (BrowserDetect.mode.intValue == 1) 0 else BrowserDetect.list.count { !it.sent } } }
    val marked by remember { derivedStateOf { BrowserDetect.list.count { it.sel && !it.sent } } }
    val phN by remember { derivedStateOf { BrowserDetect.photos.size } }
    val pendingN by BrowserDetect.pending
    val photoBusy by BrowserDetect.photoBusy
    val pageAt by BrowserDetect.pageAt
    val loadAllOn by BrowserLoadMore.running
    val loadAllPg by BrowserLoadMore.pageNum
    val working = (if (mode == 1) 0 else pendingN) + (if (mode != 0) photoBusy else 0)
    val searching = detected == 0 && phN == 0 && working > 0 && (pageAt == 0L || System.currentTimeMillis() - pageAt < 25_000)
    var moreMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        Column(Modifier.statusBarsPadding().padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ web?.goBack() }, Modifier.size(36.dp), enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", Modifier.size(20.dp)) }
                IconButton({ web?.goForward() }, Modifier.size(36.dp), enabled = canFwd) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Avançar", Modifier.size(20.dp)) }
                IconButton({ web?.reload() }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Refresh, "Recarregar", Modifier.size(20.dp)) }
                OutlinedTextField(
                    value = addrField,
                    onValueChange = { addrField = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).onFocusChanged { fs ->
                        val nowF = fs.isFocused
                        if (nowF && !addrFocused) {
                            addrFocused = true
                            addrField = addrField.copy(selection = TextRange(0, addrField.text.length))
                        } else if (!nowF && addrFocused) {
                            addrFocused = false
                            if (current.startsWith("http")) addrField = TextFieldValue(current, TextRange(current.length))
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    leadingIcon = { Icon(if (current.startsWith("https")) Icons.Rounded.Lock else Icons.Rounded.Public, null, Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (addrField.text.isNotEmpty()) {
                            IconButton({ addrField = TextFieldValue("", TextRange(0)) }, Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, "Limpar", Modifier.size(16.dp))
                            }
                        }
                    },
                    placeholder = { Text("Endereço ou pesquisa", maxLines = 1) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(addrField.text) }),
                )
                IconButton({ BrowserStore.toggleFav(current, web?.title ?: "") }, Modifier.size(36.dp), enabled = current.startsWith("http")) {
                    Icon(if (BrowserStore.isFav(current)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorito", Modifier.size(20.dp))
                }
                IconButton({ showSites = true }, Modifier.size(36.dp)) { Icon(Icons.Rounded.History, "Recentes", Modifier.size(20.dp)) }
            }
            if (progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp)) else Spacer(Modifier.height(4.dp))
        }
    }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val countLabel = when {
                    loadAllOn -> if (loadAllPg > 1) "pág $loadAllPg • $detected" else "$detected vídeos…"
                    mode == 1 -> "$phN fotos"
                    BrowserDetect.term.value.isNotBlank() && detected > 0 -> "$marked/$detected vídeos"
                    detected > 0 -> "$detected vídeo${if (detected == 1) "" else "s"}" + (if (pendingN > 0) " (+$pendingN)" else "")
                    mode == 2 && phN > 0 -> "$phN fotos"
                    searching -> "Procurando…"
                    else -> "0 vídeos"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(
                        onClick = { sheet = true },
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    ) {
                        if (searching || loadAllOn || pendingN > 0) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(countLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                    Box {
                        IconButton({ moreMenu = true }, Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.MoreVert, "Mais opções", Modifier.size(20.dp))
                        }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Analisar este site") },
                                leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                                enabled = current.startsWith("http"),
                                onClick = { moreMenu = false; analyze = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Usar esta página no app") },
                                leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                enabled = current.startsWith("http"),
                                onClick = { moreMenu = false; ask = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (loadAllOn) "Parar carregamento" else "Carregar tudo (ver mais / páginas)") },
                                leadingIcon = { Icon(if (loadAllOn) Icons.Rounded.Stop else Icons.Rounded.UnfoldMore, null) },
                                enabled = current.startsWith("http") || loadAllOn,
                                onClick = { moreMenu = false; if (loadAllOn) BrowserLoadMore.stop() else BrowserLoadMore.start(web) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sobre cookies (sessão)") },
                                leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                                onClick = {
                                    moreMenu = false
                                    android.widget.Toast.makeText(ctx, "O app usa só os cookies desta sessão; sua senha nunca é lida nem guardada.", android.widget.Toast.LENGTH_LONG).show()
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = {
                            // procura de novo na página inteira (e não só no que mudou desde a última vez)
                            BrowserDetect.fullCollect = true
                            scheduleCollect(0)
                        },
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = current.startsWith("http"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Procurar", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { if (loadAllOn) BrowserLoadMore.stop() else BrowserLoadMore.start(web) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = current.startsWith("http") || loadAllOn,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Icon(if (loadAllOn) Icons.Rounded.Stop else Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (loadAllOn) "Parar" else "Carregar tudo", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = { ask = true },
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = current.startsWith("http"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Usar", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }) { pad ->
        val gen by BrowserRuntime.gen
        key(gen) { AndroidView(factory = { c ->
            val existing = BrowserRuntime.web
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                web = existing
                existing
            } else WebView(c).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
            settings.loadWithOverviewMode = true; settings.useWideViewPort = true; settings.setSupportMultipleWindows(true)
            settings.userAgentString = Net.userAgent
            // sem offscreenPreRaster: rasterizar a página inteira fora da tela gastava muita memória e derrubava o renderizador
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            try { settings.setNeedInitialFocus(false) } catch (_: Exception) {}
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try { androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, MseHookScript.SCRIPT, setOf("*")) } catch (_: Exception) {}
            }
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    if (web === view) web = null
                    val pg = current
                    return br.geracaoai.baixartg.core.CrashLog.rendererGone("navegador", view, detail) {
                        val msg = BrowserRuntime.onRendererGone(view, pg)
                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return br.geracaoai.baixartg.web.AdBlock.blockNavigation(request.url.toString(), v.url ?: current, request.hasGesture(), request.isRedirect)
                }
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    BrowserRuntime.pageUrl = url
                    startNetPoll()
                    br.geracaoai.baixartg.core.CrashLog.mark("navegador: abrindo ${br.geracaoai.baixartg.core.CrashLog.siteTag(url)}")
                    if (url != current) BrowserDetect.newPage()
                    v.evaluateJavascript(MseHookScript.SCRIPT) { }
                    if (br.geracaoai.baixartg.web.AdBlock.enabled) v.evaluateJavascript(br.geracaoai.baixartg.web.AdBlock.COSMETIC_JS) { }
                    current = url; syncAddress(url); canBack = v.canGoBack(); canFwd = v.canGoForward()
                }
                override fun onPageFinished(v: WebView, url: String) {
                    br.geracaoai.baixartg.core.CrashLog.mark("navegador: carregou ${br.geracaoai.baixartg.core.CrashLog.siteTag(url)}")
                    if (url.startsWith("http")) { br.geracaoai.baixartg.Prefs.browserUrl = url; BrowserStore.visit(url, v.title ?: ""); BrowserDetect.restore(Extract.host(url)) }
                    current = url; syncAddress(url); canBack = v.canGoBack(); canFwd = v.canGoForward(); WebCookies.remember(url)
                    if (br.geracaoai.baixartg.web.AdBlock.enabled) v.evaluateJavascript(br.geracaoai.baixartg.web.AdBlock.COSMETIC_JS) { }
                    v.evaluateJavascript(MseHookScript.SCRIPT) { }
                }
                override fun shouldInterceptRequest(v: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url?.toString().orEmpty()
                    if (br.geracaoai.baixartg.web.AdBlock.shouldBlock(u, current, request.isForMainFrame)) return br.geracaoai.baixartg.web.AdBlock.empty()
                    val hdrs = request.requestHeaders ?: emptyMap()
                    val pg = current
                    if (u.startsWith("http")) BrowserDiag.media("webview", u)
                    if (u.startsWith("http") && pg.startsWith("http") && (MseCapture.hasSpecialHeaders(hdrs) || Extract.isDash(u) || Extract.isHls(u) || Extract.isSegmentUrl(u) || hdrs.keys.any { it.equals("Range", true) })) {
                        MseCapture.recordRequest(pg, u, request.method ?: "GET", hdrs, source = "browser-tab")
                    }
                    if (u.startsWith("http") && Extract.mediaKindOf(u) != null && !(Extract.isPhotoUrl(u) && !Extract.isVideoUrl(u))) {
                        if (!br.geracaoai.baixartg.core.MediaPick.isAdUrl(u)) {
                            BrowserDetect.intercepted(u, pg, "")
                            val ref = hdrs["Referer"] ?: pg
                            br.geracaoai.baixartg.core.PlayCapture.record(u, pg, ref)
                        }
                    }
                    return super.shouldInterceptRequest(v, request)
                }
                override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { if (url != BrowserRuntime.pageUrl) { BrowserRuntime.pageUrl = url; startNetPoll() }; current = url; syncAddress(url); canBack = v.canGoBack(); canFwd = v.canGoForward() }
            }
            settings.setSupportMultipleWindows(true); settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { progress = p; if (p in 30..90 && br.geracaoai.baixartg.web.AdBlock.enabled) v.evaluateJavascript(br.geracaoai.baixartg.web.AdBlock.COSMETIC_JS) { } }
                /** target=_blank / window.open após toque: abre na MESMA aba. Sem toque do usuário: pop-up bloqueado. */
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    if (!isUserGesture && br.geracaoai.baixartg.web.AdBlock.enabled) return false
                    val href = view.hitTestResult?.extra?.takeIf { it.startsWith("http", true) && view.hitTestResult?.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) }
                    if (href != null) { view.loadUrl(href); return false }
                    val tr = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tmp = WebView(view.context)
                    tmp.webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean =
                            br.geracaoai.baixartg.core.CrashLog.rendererGone("auxiliar", view, detail)
                        private var done = false
                        fun take(u: String?) {
                            if (done || u == null) return
                            if (!u.startsWith("http", true)) return
                            done = true; view.loadUrl(u); tmp.post { try { tmp.stopLoading(); tmp.destroy() } catch (_: Exception) {} }
                        }
                        override fun shouldOverrideUrlLoading(w: WebView, r: android.webkit.WebResourceRequest): Boolean { take(r.url?.toString()); return true }
                        override fun onPageStarted(w: WebView, url: String?, favicon: Bitmap?) { take(url) }
                    }
                    tr.webView = tmp; resultMsg.sendToTarget()
                    return true
                }
            }
            web = this
            BrowserRuntime.adopt(this)
            BrowserRuntime.installServiceWorkerHook()
            val start = BrowserRuntime.reloadUrl.also { BrowserRuntime.reloadUrl = null } ?: initialAddr.takeIf { gen == 0 }
            if (start != null && start.length > 8) loadUrl(start)
        }
        }, update = { w ->
            web = w
            w.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            val u = w.url.orEmpty()
            if (u.isNotBlank()) { current = u; syncAddress(u) }
            canBack = w.canGoBack(); canFwd = w.canGoForward()
        }, onRelease = { BrowserRuntime.detach() }, modifier = Modifier.fillMaxSize().padding(pad)) }
    }
    if (sheet) BrowserVideosSheet({ sheet = false }, { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }, onLoadAll = { BrowserLoadMore.start(web) }) { term ->
        val v = web; val page = v?.url
        if (v != null && page != null && page.startsWith("http")) {
            v.evaluateJavascript("(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()") { raw ->
                val html = try { org.json.JSONTokener(raw ?: "").nextValue() as? String } catch (_: Exception) { null }
                siteSearchUrl(page, html, term)?.let { u -> sheet = false; v.loadUrl(u) }
            }
        }
    }
    if (showSites) AlertDialog(onDismissRequest = { showSites = false }, icon = { Icon(Icons.Rounded.History, null) },
        title = { Text("Recentes e favoritos") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (BrowserStore.favorites.isNotEmpty()) item { Text("Favoritos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
                items(BrowserStore.favorites.toList(), key = { "f" + it.url }) { s -> SiteRow(s, Icons.Rounded.Star, { showSites = false; addrFocused = false; syncAddress(s.url); web?.loadUrl(s.url) }, { BrowserStore.removeFav(s.url) }) }
                item { Text("Recentes (segure para remover)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
                if (BrowserStore.recents.isEmpty()) item { Text("Nenhum site visitado ainda.", style = MaterialTheme.typography.bodySmall) }
                items(BrowserStore.recents.toList(), key = { "r" + it.url }) { s -> SiteRow(s, Icons.Rounded.History, { showSites = false; addrFocused = false; syncAddress(s.url); web?.loadUrl(s.url) }, { BrowserStore.removeRecent(s.url) }) }
            }
        },
        confirmButton = { TextButton({ showSites = false }) { Text("Fechar") } })
    if (ask) AlertDialog(onDismissRequest = { ask = false }, icon = { Icon(Icons.Rounded.Download, null) },
        title = { Text("Usar esta página") }, text = { Text(current, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton({ ask = false; CookieManager.getInstance().flush(); onUse(current, false) }) { Text("Filtrar") } },
        dismissButton = { TextButton({ ask = false; CookieManager.getInstance().flush(); onUse(current, true) }) { Text("Varrer tudo") } })
    if (analyze) SiteAnalyzeDialog(current, web) { analyze = false }
}

// ---------------- Contas logadas (Configurações) ----------------

@Composable
fun LoggedAccountsGroup() {
    var list by remember { mutableStateOf(WebCookies.domains()) }
    SettingsGroup("Contas logadas", Icons.Rounded.ManageAccounts) {
        Text("Sites em que você entrou pelo Navegador do app. Os downloads usam essa sessão (cookies).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text("Nenhuma conta logada.", style = MaterialTheme.typography.bodyMedium)
        list.forEach { d -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Public, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Text(d, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ WebCookies.logout(d); list = WebCookies.domains() }) { Text("Sair") }
        } }
        OutlinedButton({ WebCookies.clearAll { list = emptyList() }; list = emptyList() }, Modifier.fillMaxWidth(), enabled = list.isNotEmpty(), shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Sair de todas") }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SiteRow(s: BrowserStore.Site, icon: androidx.compose.ui.graphics.vector.ImageVector, onOpen: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { confirm = true }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { Extract.host(s.url) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(s.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (confirm) TextButton({ confirm = false; onRemove() }) { Text("Remover") }
    }
}
