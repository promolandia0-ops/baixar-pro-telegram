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

/** Leva um link do Navegador para Filtrar ou Varrer tudo (a busca só começa quando o usuário tocar no botão). */
object Handoff { val page = mutableStateOf<String?>(null); val site = mutableStateOf<String?>(null) }

// ---------------- Navegador ----------------

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onClose: () -> Unit, onUse: (url: String, site: Boolean) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(br.geracaoai.baixartg.Prefs.lastUrl.ifBlank { "https://" }) }
    var current by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }; var canFwd by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf(false) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else onClose() }
    fun go(t: String) { val u = t.trim().let { if (it.startsWith("http", true)) it else if (it.contains('.') && !it.contains(' ')) "https://$it" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(it, "UTF-8") }; web?.loadUrl(u) }
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush(); web?.destroy() } }
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("Navegador", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClose) { Icon(Icons.Rounded.Close, "Fechar") } },
                actions = {
                    IconButton({ web?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
                    IconButton({ web?.goForward() }, enabled = canFwd) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Avançar") }
                    IconButton({ web?.reload() }) { Icon(Icons.Rounded.Refresh, "Recarregar") }
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
            Text("Entre na sua conta normalmente. O app usa só os cookies desta sessão; sua senha nunca é lida nem guardada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
    }) { pad ->
        AndroidView(factory = { c -> WebView(c).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.databaseEnabled = true
            settings.loadWithOverviewMode = true; settings.useWideViewPort = true; settings.setSupportMultipleWindows(false)
            settings.userAgentString = Net.userAgent
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward() }
                override fun onPageFinished(v: WebView, url: String) { current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward(); WebCookies.remember(url) }
                override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) { current = url; address = url; canBack = v.canGoBack(); canFwd = v.canGoForward() }
            }
            webChromeClient = object : WebChromeClient() { override fun onProgressChanged(v: WebView, p: Int) { progress = p } }
            web = this
            if (address.length > 8) loadUrl(address)
        } }, modifier = Modifier.fillMaxSize().padding(pad))
    }
    if (ask) AlertDialog(onDismissRequest = { ask = false }, icon = { Icon(Icons.Rounded.Download, null) },
        title = { Text("Usar esta página") }, text = { Text(current, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton({ ask = false; CookieManager.getInstance().flush(); onUse(current, false) }) { Text("Filtrar") } },
        dismissButton = { TextButton({ ask = false; CookieManager.getInstance().flush(); onUse(current, true) }) { Text("Varrer tudo") } })
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
                        Modifier.fillMaxWidth(), enabled = !loading && Mega.isMegaLink(link), shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Listar vídeos do link") }
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
