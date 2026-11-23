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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.web.MegaStore
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
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000); now = System.currentTimeMillis() } }
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

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onClose: () -> Unit, onUse: (url: String, site: Boolean) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(br.geracaoai.baixartg.Prefs.browserUrl.ifBlank { br.geracaoai.baixartg.Prefs.lastUrl }.ifBlank { "https://" }) }
    var current by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }; var canFwd by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    var analyze by remember { mutableStateOf(false) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    fun go(t: String) { val u = t.trim().let { if (it.startsWith("http", true)) it else if (it.contains('.') && !it.contains(' ')) "https://$it" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(it, "UTF-8") }; web?.loadUrl(u) }
    fun savePanel() { val v = BrowserDetect.list.toList(); val p = BrowserDetect.photos.toList(); val h = BrowserDetect.loadedHostSet()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { BrowserStore.savePanel(v, p, h) } }
    DisposableEffect(Unit) {
        JsRender.browserVisible = true
        BrowserDetect.reset(); BrowserDetect.warm()
        BrowserDetect.restore(Extract.host(address))   // lista salva deste site volta como estava
        onDispose { JsRender.browserVisible = false; savePanel(); CookieManager.getInstance().flush(); web?.destroy() }
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
    fun scheduleCollect(delayMs: Long = 1_200) { handler.removeCallbacks(collect); handler.postDelayed(collect, delayMs)
        if (BrowserDetect.photosOn.value) { handler.removeCallbacks(photoRun); handler.postDelayed(photoRun, delayMs + 300) } }
    LaunchedEffect(web) { while (true) { kotlinx.coroutines.delay(2_000); if (web != null) scheduleCollect(0) } }
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("Navegador", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClose) { Icon(Icons.Rounded.Close, "Fechar") } },
                actions = {
                    IconButton({ web?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
                    IconButton({ web?.goForward() }, enabled = canFwd) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Avançar") }
                    IconButton({ web?.reload() }) { Icon(Icons.Rounded.Refresh, "Recarregar") }
                    IconButton({ BrowserStore.toggleFav(current, web?.title ?: "") }, enabled = current.startsWith("http")) {
                        Icon(if (BrowserStore.isFav(current)) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorito") }
                    IconButton({ showSites = true }) { Icon(Icons.Rounded.History, "Recentes") }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(if (current.startsWith("https")) Icons.Rounded.Lock else Icons.Rounded.Public, null) },
                placeholder = { Text("Digite um endereço ou pesquise") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go(address) }))
            if (progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp)) else Spacer(Modifier.height(10.dp))
        }
    }, bottomBar = {
        Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            Button({ ask = true }, Modifier.fillMaxWidth().height(52.dp), enabled = current.startsWith("http"), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(8.dp)); Text("Usar esta página no app") }
            OutlinedButton({ analyze = true }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = current.startsWith("http"), shape = MaterialTheme.shapes.medium) {
                Text("Analisar este site") }
            Text("Entre na sua conta normalmente. O app usa só os cookies desta sessão; sua senha nunca é lida nem guardada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
    }) { pad ->
        AndroidView(factory = { c -> WebView(c).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
            settings.loadWithOverviewMode = true; settings.useWideViewPort = true; settings.setSupportMultipleWindows(true)
            settings.userAgentString = Net.userAgent
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return br.geracaoai.baixartg.web.AdBlock.blockNavigation(request.url.toString(), v.url ?: current, request.hasGesture(), request.isRedirect)
                }
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { if (url != current) BrowserDetect.newPage(); if (br.geracaoai.baixartg.web.AdBlock.enabled) v.evaluateJavascript(br.geracaoai.baixartg.web.AdBlock.COSMETIC_JS) { }; current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward() }
                override fun onPageFinished(v: WebView, url: String) {
                    if (url.startsWith("http")) { br.geracaoai.baixartg.Prefs.browserUrl = url; BrowserStore.visit(url, v.title ?: ""); BrowserDetect.restore(Extract.host(url)) }
                    current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward(); WebCookies.remember(url); if (br.geracaoai.baixartg.web.AdBlock.enabled) v.evaluateJavascript(br.geracaoai.baixartg.web.AdBlock.COSMETIC_JS) { }; v.evaluateJavascript(br.geracaoai.baixartg.core.JsRender.HOOK_JS) { }; scheduleCollect(400) }
                override fun shouldInterceptRequest(v: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url?.toString().orEmpty()
                    if (br.geracaoai.baixartg.web.AdBlock.shouldBlock(u, current, request.isForMainFrame)) return br.geracaoai.baixartg.web.AdBlock.empty()
                    if (u.startsWith("http") && Extract.mediaKindOf(u) != null && !(Extract.isPhotoUrl(u) && !Extract.isVideoUrl(u))) {
                        val pg = current
                        if (!br.geracaoai.baixartg.core.MediaPick.isAdUrl(u)) {
                            BrowserDetect.intercepted(u, pg, "")
                            val ref = request.requestHeaders?.get("Referer") ?: pg
                            br.geracaoai.baixartg.core.PlayCapture.record(u, pg, ref)
                        }
                    }
                    return super.shouldInterceptRequest(v, request)
                }
                override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward() }
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
            setOnScrollChangeListener { _, _, _, _, _ -> scheduleCollect(1_200) }
            web = this
            if (address.length > 8) loadUrl(address)
        } }, modifier = Modifier.fillMaxSize().padding(pad))
        DetectFab(Modifier.fillMaxSize().padding(pad).padding(16.dp), current.startsWith("http")) { sheet = true }
    }
    if (sheet) BrowserVideosSheet({ sheet = false }, { msg -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }) { term ->
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
                items(BrowserStore.favorites.toList(), key = { "f" + it.url }) { s -> SiteRow(s, Icons.Rounded.Star, { showSites = false; address = s.url; web?.loadUrl(s.url) }, { BrowserStore.removeFav(s.url) }) }
                item { Text("Recentes (segure para remover)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
                if (BrowserStore.recents.isEmpty()) item { Text("Nenhum site visitado ainda.", style = MaterialTheme.typography.bodySmall) }
                items(BrowserStore.recents.toList(), key = { "r" + it.url }) { s -> SiteRow(s, Icons.Rounded.History, { showSites = false; address = s.url; web?.loadUrl(s.url) }, { BrowserStore.removeRecent(s.url) }) }
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

// ---------------- MEGA ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MegaScreen(onClose: () -> Unit, goQueue: () -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(Mega.session) }
    var nodes by remember { mutableStateOf<List<Mega.Node>>(emptyList()) }
    var folder by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var link by remember { mutableStateOf("") }
    val stack = remember { mutableStateListOf<String>() }
    fun load() { val s = session ?: return; loading = true; msg = ""
        scope.launch { try { nodes = withContext(Dispatchers.IO) { Mega.accountNodes(s) }; if (folder == null) folder = nodes.firstOrNull { it.type == 2 }?.h }
            catch (e: MegaException) { msg = e.message ?: "Erro"; if (e.code == -15) { MegaStore.clear(); Mega.session = null; session = null } }
            catch (e: Exception) { msg = "Erro: ${e.message}" }
            loading = false } }
    LaunchedEffect(session) { if (session != null && nodes.isEmpty()) load() }
    BackHandler { if (stack.isNotEmpty()) { folder = stack.removeAt(stack.lastIndex); sel = emptySet() } else onClose() }
    Scaffold(topBar = { TopAppBar(title = { Column { Text("MEGA", fontWeight = FontWeight.SemiBold); session?.let { Text(it.email, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
        navigationIcon = { IconButton({ if (stack.isNotEmpty()) { folder = stack.removeAt(stack.lastIndex); sel = emptySet() } else onClose() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        actions = { if (session != null) { IconButton({ load() }) { Icon(Icons.Rounded.Refresh, "Atualizar") }
            IconButton({ val s = session; scope.launch { withContext(Dispatchers.IO) { Mega.logout() }; MegaStore.clear() }; session = null; nodes = emptyList(); folder = null; stack.clear(); sel = emptySet(); if (s == null) Unit }) { Icon(Icons.AutoMirrored.Rounded.Logout, "Sair do MEGA") } } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { if (sel.isNotEmpty()) Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            val chosen = nodes.filter { it.h in sel }
            Button({ val s = session ?: return@Button
                if (Jobs.addExternal(chosen.map { Mega.toFound(it, 'a', page = "https://mega.nz/fm/${it.h}") to it.size }, start = true)) { sel = emptySet(); goQueue() } else msg = "Há uma tarefa em andamento. Espere terminar." },
                Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(8.dp)); Text("Baixar e enviar ${chosen.size} (${fmtBytes(chosen.sumOf { it.size })})") }
        } } }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Link público do MEGA", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium, label = { Text("https://mega.nz/file/…#chave ou /folder/…#chave") })
                    FilledTonalButton({ loading = true; msg = ""
                        scope.launch { try { val l = withContext(Dispatchers.IO) { Mega.resolvePublic(link) }
                            if (l.isEmpty()) msg = "Nenhum vídeo nesse link." else if (Jobs.addExternal(l, start = false)) goQueue() else msg = "Há uma tarefa em andamento." }
                            catch (e: Exception) { msg = e.message ?: "Erro" }; loading = false } },
                        Modifier.fillMaxWidth(), enabled = !loading && (Mega.isMegaLink(link) || Mega.looksLikeMega(link)), shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Listar vídeos do link") }
                } }
            }
            if (msg.isNotBlank()) item { InfoCard(msg, Icons.Rounded.ErrorOutline, 1) }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (session == null) item { MegaLogin { s -> session = s; nodes = emptyList(); folder = null } }
            else {
                val here = nodes.filter { it.parent == folder }
                val subfolders = here.filter { it.isFolder }.sortedBy { it.name.lowercase() }
                val vids = here.filter { it.isVideo }.sortedBy { it.name.lowercase() }
                val roots = nodes.filter { it.type >= 2 }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { roots.forEach { r -> FilterChip(folder == r.h && stack.isEmpty(), { stack.clear(); folder = r.h; sel = emptySet() }, { Text(r.name) }) } } }
                if (stack.isNotEmpty()) item { Text(nodes.firstOrNull { it.h == folder }?.name ?: "", style = MaterialTheme.typography.titleMedium) }
                items(subfolders, key = { "d" + it.h }) { d -> ListItem(headlineContent = { Text(d.name) }, leadingContent = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    supportingContent = { val c = nodes.count { it.parent == d.h && it.isVideo }; Text(if (c > 0) "$c vídeo(s)" else "Pasta") },
                    modifier = Modifier.clickable { folder?.let { stack.add(it) }; folder = d.h; sel = emptySet() }) }
                if (vids.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${vids.size} vídeo(s) • ${fmtBytes(vids.sumOf { it.size })}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    val all = vids.all { it.h in sel }
                    TextButton({ sel = if (all) sel - vids.map { it.h }.toSet() else sel + vids.map { it.h } }) { Text(if (all) "Desmarcar todos" else "Marcar todos") } } }
                items(vids, key = { "v" + it.h }) { v -> ListItem(headlineContent = { Text(v.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(fmtBytes(v.size) + if (Sent.isSent(Mega.toFound(v, 'a', page = "https://mega.nz/fm/${v.h}"))) " • Já enviado" else "") },
                    leadingContent = { Checkbox(v.h in sel, { c -> sel = if (c) sel + v.h else sel - v.h }) },
                    modifier = Modifier.clickable { sel = if (v.h in sel) sel - v.h else sel + v.h }) }
                if (!loading && subfolders.isEmpty() && vids.isEmpty()) item { EmptyState(Icons.Rounded.VideoLibrary, "Nenhum vídeo aqui", "Abra outra pasta.") }
            }
        }
    }
}

@Composable
fun MegaLogin(onDone: (Mega.Session) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var mfa by remember { mutableStateOf("") }
    var needMfa by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Entrar na sua conta MEGA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), label = { Text("E-mail") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Senha") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (needMfa) OutlinedTextField(mfa, { mfa = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(), label = { Text("Código de verificação (2FA)") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button({ busy = true; err = ""
            scope.launch { try { val s = withContext(Dispatchers.Default) { Mega.login(email, pass, mfa.ifBlank { null }) }; MegaStore.save(s); pass = ""; onDone(s) }
                catch (e: MegaException) { if (e.code == -26) { needMfa = true; err = "Digite o código do app autenticador." } else err = e.message ?: "Erro" }
                catch (e: Exception) { err = "Erro: ${e.message}" }; busy = false } },
            Modifier.fillMaxWidth().height(50.dp), enabled = !busy && email.contains('@') && pass.isNotEmpty() && (!needMfa || mfa.length == 6), shape = MaterialTheme.shapes.medium) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(8.dp)); Text("Entrar") }
        Text("Login direto com a API oficial do MEGA. A senha só é usada para gerar a chave na hora e não é guardada; o app guarda só a sessão, criptografada no aparelho. Os arquivos são descriptografados no celular, em qualidade original.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
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
