package br.geracaoai.baixartg.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.tg.Td
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {
    private val shared = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) { Surface { Root(shared) } }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { t -> Regex("""https?://\S+""").find(t)?.value?.let { shared.value = it } }
    }
}

@Composable
fun Root(shared: MutableState<String?>) {
    val auth by Td.auth.collectAsState()
    var hasApi by remember { mutableStateOf(Prefs.apiId != 0) }
    val stage = when { !hasApi -> 0; auth !is TdApi.AuthorizationStateReady -> 1; else -> 2 }
    AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { s ->
        when (s) {
            0 -> SetupScreen { hasApi = true }
            1 -> { LaunchedEffect(auth) { if (Prefs.apiId == 0) hasApi = false }; LoginScreen(auth) }
            else -> HomeScreen(shared)
        }
    }
}

fun fmtBytes(b: Long): String {
    val df = DecimalFormat("#,##0.#")
    return when { b >= 1L shl 30 -> df.format(b / 1073741824.0) + " GB"; b >= 1L shl 20 -> df.format(b / 1048576.0) + " MB"; else -> df.format(b / 1024.0) + " KB" }
}

fun copy(ctx: Context, text: String, toast: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("links", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val phase by Jobs.phase.collectAsState()
    val crawl by Jobs.crawl.collectAsState()
    val items by Jobs.items.collectAsState()
    val status by Jobs.status.collectAsState()
    val stopping by Jobs.stopping.collectAsState()
    var url by remember { mutableStateOf(Prefs.lastUrl) }
    var settings by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { perms.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(shared.value) { shared.value?.let { url = it; shared.value = null } }
    val busy = phase == Phase.CRAWLING || phase == Phase.RUNNING
    val sel = items.count { it.sel }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Baixar pro Telegram", fontWeight = FontWeight.SemiBold) },
            actions = { IconButton({ settings = true }, enabled = !busy) { Icon(Icons.Rounded.Settings, "Ajustes") } })
    }, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 12.dp)) {
                when (phase) {
                    Phase.CRAWLING, Phase.RUNNING -> Button({ Jobs.stop() }, enabled = !stopping, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(54.dp)) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text(if (stopping) "Parando…" else "Parar") }
                    Phase.LIST -> if (items.isNotEmpty()) Button({ Jobs.startTransfer() }, enabled = sel > 0, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(8.dp))
                        val pend = items.count { it.sel && it.st != St.DONE }
                        Text(if (pend == 0) "Tudo enviado ✓" else "Baixar e enviar $pend vídeo(s) pro Telegram") }
                    else -> {}
                }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(url, { url = it }, label = { Text("Link do site") }, placeholder = { Text("https://exemplo.com") }, singleLine = true, enabled = !busy,
                    leadingIcon = { Icon(Icons.Rounded.Link, null) },
                    trailingIcon = { if (url.isNotEmpty() && !busy) IconButton({ url = "" }) { Icon(Icons.Rounded.Close, "Limpar") } else if (!busy) IconButton({
                        ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.let { url = it.trim() } }) { Icon(Icons.Rounded.ContentPaste, "Colar") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (url.isNotBlank()) Jobs.startCrawl(url) }), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                FilledTonalButton({ Jobs.startCrawl(url) }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Procurar vídeos no site inteiro") }
                Text("Até ${Prefs.maxPages} páginas, profundidade ${Prefs.depth} (mude em Ajustes).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            if (phase == Phase.CRAWLING) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("Procurando vídeos…", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val tot = (crawl.pagesDone + crawl.queued).coerceAtMost(Prefs.maxPages).coerceAtLeast(1)
                    LinearProgressIndicator(progress = { crawl.pagesDone.toFloat() / tot }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${crawl.pagesDone} páginas lidas • ${crawl.queued} na fila • ${crawl.found} vídeos", style = MaterialTheme.typography.bodyMedium)
                    Text(crawl.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            if (status.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Text(status, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            if (items.isNotEmpty()) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${items.size} vídeos • $sel marcados", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton({ Jobs.selectAll(sel < items.size) }, enabled = phase != Phase.RUNNING) { Text(if (sel < items.size) "Marcar todos" else "Desmarcar") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ copy(ctx, items.joinToString("\n") { linkOf(it.f) }, "${items.size} links copiados") }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar todos os links") }
                    if (phase == Phase.LIST) OutlinedButton({ Jobs.clear() }) { Text("Limpar") }
                }
            }
            items(items, key = { it.id }) { ItemRow(it, phase != Phase.RUNNING) }
            if (phase == Phase.IDLE && items.isEmpty()) item { HowTo() }
        }
    }
    if (settings) SettingsSheet { settings = false }
}

@Composable
fun ItemRow(it: Item, editable: Boolean) {
    val ctx = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(enabled = editable) { Jobs.toggle(it.id) }.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(it.sel, { _ -> Jobs.toggle(it.id) }, enabled = editable)
            Column(Modifier.weight(1f)) {
                Text(it.f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(it.f.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val (txt, color) = when (it.st) {
                    St.WAIT -> it.f.label to MaterialTheme.colorScheme.onSurfaceVariant
                    St.DOWN -> ("Baixando " + (if (it.total > 0) "${(it.prog * 100).toInt()}% de ${fmtBytes(it.total)}" else fmtBytes(it.bytes))) to MaterialTheme.colorScheme.primary
                    St.UP -> "Enviando pro Telegram ${(it.prog * 100).toInt()}%" to MaterialTheme.colorScheme.primary
                    St.DONE -> "✓ Enviado para Mensagens Salvas" to MaterialTheme.colorScheme.tertiary
                    St.ERR -> "Erro: ${it.msg}" to MaterialTheme.colorScheme.error
                    St.STOPPED -> "Parado" to MaterialTheme.colorScheme.error
                }
                Text(txt, style = MaterialTheme.typography.labelMedium, color = color)
                if (it.st == St.DOWN || it.st == St.UP) LinearProgressIndicator(progress = { it.prog }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            IconButton({ copy(ctx, linkOf(it.f), "Link copiado") }) { Icon(Icons.Rounded.ContentCopy, "Copiar link") }
        }
    }
}

@Composable
fun HowTo() {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Como usar", fontWeight = FontWeight.SemiBold)
        listOf("1. Cole o link do site e toque em “Procurar vídeos”.", "2. O app lê as páginas do site e lista todos os vídeos.",
            "3. Copie os links se quiser, desmarque o que não quer.", "4. Toque em “Baixar e enviar” — cada vídeo vai para as suas Mensagens Salvas do Telegram.",
            "Pode bloquear a tela: continua em segundo plano. “Parar” interrompe na hora.").forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onClose: () -> Unit) {
    var depth by remember { mutableFloatStateOf(Prefs.depth.toFloat()) }
    var pages by remember { mutableStateOf(Prefs.maxPages.toString()) }
    var probe by remember { mutableStateOf(Prefs.probe) }
    var server by remember { mutableStateOf(Prefs.server) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet({ Prefs.depth = depth.toInt(); Prefs.maxPages = pages.toIntOrNull()?.coerceIn(1, 5000) ?: 500; Prefs.probe = probe; Prefs.server = server.trim().ifBlank { Downloader.DEFAULT_SERVER }; onClose() }) {
        Column(Modifier.padding(20.dp, 0.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)
            Text("Profundidade dos links: ${depth.toInt()}")
            Slider(depth, { depth = it }, valueRange = 0f..20f, steps = 19)
            OutlinedTextField(pages, { pages = it.filter(Char::isDigit) }, label = { Text("Máximo de páginas") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Usar servidor (yt-dlp) em todas as páginas"); Text("Acha vídeos de players difíceis. Mais lento.", style = MaterialTheme.typography.bodySmall) }
                Switch(probe, { probe = it })
            }
            OutlinedTextField(server, { server = it }, label = { Text("Servidor yt-dlp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            OutlinedButton({ scope.launch { try { Td.send(TdApi.LogOut()) } catch (_: Exception) {} }; onClose() }, Modifier.fillMaxWidth()) { Text("Sair do Telegram") }
        }
    }
}

/** Bunkr: a URL do CDN só vale com token; copia a página /f/, que sempre abre. */
fun linkOf(f: Found) = if (Bunkr.applies(f) && f.page != f.url) f.page else f.url
