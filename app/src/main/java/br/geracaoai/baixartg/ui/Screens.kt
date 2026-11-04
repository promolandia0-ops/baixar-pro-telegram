package br.geracaoai.baixartg.ui

import android.Manifest
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.BuildConfig
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.tg.Td
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Rounded.Home), SITE("Varrer tudo", Icons.Rounded.TravelExplore), PAGE("Filtrar", Icons.Rounded.FilterAlt),
    QUEUE("Fila", Icons.Rounded.Downloading), HIST("Histórico", Icons.Rounded.History)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(shared: MutableState<String?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val homeText = rememberSaveable { mutableStateOf("") }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { perms.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(Unit) { Jobs.syncTelegram(auto = true) }
    LaunchedEffect(shared.value) { shared.value?.let { homeText.value = it; tab = Tab.HOME; shared.value = null } }
    val phase by Jobs.phase.collectAsState()
    val items by Jobs.items.collectAsState()
    val active = items.count { it.st == St.DOWN || it.st == St.UP }

    if (settings) { BackHandler { settings = false }; SettingsScreen { settings = false }; return }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column { Text(tab.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Baixar pro Telegram", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { IconButton({ settings = true }) { Icon(Icons.Rounded.Settings, "Configurações") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            Column {
                ActivityBar(phase) { tab = Tab.QUEUE }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label, maxLines = 1) },
                            icon = { if (t == Tab.QUEUE && (items.isNotEmpty())) BadgedBox(badge = { Badge { Text(if (active > 0) "$active" else "${items.size}") } }) { Icon(t.icon, null) } else Icon(t.icon, null) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.HOME -> HomeTab(homeText) { tab = Tab.QUEUE }
                    Tab.SITE -> SiteTab { tab = Tab.QUEUE }
                    Tab.PAGE -> PageTab()
                    Tab.QUEUE -> QueueTab()
                    Tab.HIST -> HistoryTab { tab = Tab.QUEUE }
                }
            }
        }
    }
}

/** Barra fina acima do menu enquanto algo roda: status + Parar, visível em qualquer aba. */
@Composable
fun ActivityBar(phase: Phase, onOpen: () -> Unit) {
    val status by Jobs.status.collectAsState(); val crawl by Jobs.crawl.collectAsState(); val stopping by Jobs.stopping.collectAsState()
    AnimatedVisibility(phase == Phase.CRAWLING || phase == Phase.RUNNING) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(if (phase == Phase.CRAWLING) "Procurando: ${crawl.pagesDone} páginas • ${crawl.found} vídeos" else status.ifBlank { "Baixando e enviando…" },
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton({ Jobs.stop() }, enabled = !stopping) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (stopping) "Parando…" else "Parar") }
            }
        }
    }
}

// ---------------- componentes ----------------

@Composable
fun Section(title: String, sub: String? = null, icon: ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        if (icon != null) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }; Spacer(Modifier.width(14.dp)) }
        Column { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun LinkField(value: String, onChange: (String) -> Unit, enabled: Boolean, multi: Boolean = false, label: String = "Link") {
    val ctx = LocalContext.current
    OutlinedTextField(value, onChange, label = { Text(label) }, placeholder = { Text(if (multi) "https://site.com/video1\nhttps://site.com/video2" else "https://exemplo.com") },
        singleLine = !multi, minLines = if (multi) 3 else 1, maxLines = if (multi) 8 else 1, enabled = enabled,
        leadingIcon = { Icon(Icons.Rounded.Link, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton({ onChange("") }, enabled = enabled) { Icon(Icons.Rounded.Close, "Limpar") }
            else IconButton({ ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.let { onChange(it.trim()) } }, enabled = enabled) { Icon(Icons.Rounded.ContentPaste, "Colar") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
}

@Composable
fun BigButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(54.dp)) {
        Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
fun InfoCard(text: String, icon: ImageVector = Icons.Rounded.Info, tone: Int = 0) {
    val (bg, fg) = when (tone) { 1 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer }
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(text, color = fg, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun ScanProgress() {
    val crawl by Jobs.crawl.collectAsState()
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(16.dp)) {
        Text("Procurando vídeos…", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        val tot = (crawl.pagesDone + crawl.queued).coerceAtLeast(1)
        LinearProgressIndicator(progress = { crawl.pagesDone.toFloat() / tot }, Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        Spacer(Modifier.height(10.dp))
        Row { Stat("${crawl.pagesDone}", "páginas lidas", Modifier.weight(1f)); Stat("${crawl.queued}", "na fila", Modifier.weight(1f)); Stat("${crawl.found}", "vídeos", Modifier.weight(1f)) }
        Spacer(Modifier.height(6.dp))
        Text(crawl.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
fun Stat(v: String, l: String, m: Modifier) = Column(m) { Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(l, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

private val listPad = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)

// ---------------- Início ----------------

@Composable
fun HomeTab(text: MutableState<String>, goQueue: () -> Unit) {
    val phase by Jobs.phase.collectAsState(); val busy = phase == Phase.CRAWLING || phase == Phase.RUNNING
    val links = remember(text.value) { Jobs.parseLinks(text.value) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section("Baixar vídeos", "Cole um ou vários links (um por linha). Qualidade original, sem compressão.", Icons.Rounded.Download) }
        item { LinkField(text.value, { text.value = it }, !busy, multi = true, label = "Links dos vídeos ou páginas") }
        item {
            BigButton(if (links.size > 1) "Baixar ${links.size} links" else "Baixar", Icons.Rounded.Download, !busy && links.isNotEmpty()) {
                Jobs.startScan(links, Jobs.Mode.LINKS, Prefs.autoSend); goQueue() }
            Spacer(Modifier.height(6.dp))
            Text(if (Prefs.autoSend) "Os vídeos encontrados vão direto para as suas Mensagens Salvas." else "Envio automático desligado: confira a lista na Fila e toque em “Baixar e enviar”.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) item { InfoCard("Há uma tarefa em andamento. Acompanhe na aba Fila.", Icons.Rounded.HourglassTop) }
        item { Spacer(Modifier.height(4.dp)); HowTo() }
    }
}

@Composable
fun HowTo() {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Como funciona", style = MaterialTheme.typography.titleSmall)
        Tip(Icons.Rounded.Download, "Início", "baixa os vídeos dos links colados.")
        Tip(Icons.Rounded.TravelExplore, "Varrer tudo", "percorre o site inteiro e baixa todos os vídeos.")
        Tip(Icons.Rounded.FilterAlt, "Filtrar", "pega só os vídeos de uma página, sem abrir outras.")
        Tip(Icons.Rounded.Lock, "Segundo plano", "pode bloquear a tela; “Parar” interrompe na hora.")
    } }
}
@Composable fun Tip(i: ImageVector, t: String, d: String) = Row(verticalAlignment = Alignment.Top) {
    Icon(i, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
    Text(buildString { append(t); append(": "); append(d) }, style = MaterialTheme.typography.bodyMedium) }

// ---------------- Varrer tudo ----------------

@Composable
fun SiteTab(goQueue: () -> Unit) {
    val phase by Jobs.phase.collectAsState(); val mode by Jobs.mode.collectAsState(); val status by Jobs.status.collectAsState(); val items by Jobs.items.collectAsState()
    val busy = phase == Phase.CRAWLING || phase == Phase.RUNNING
    var url by rememberSaveable { mutableStateOf(Prefs.lastUrl) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section("Varrer o site inteiro", "Abre todas as páginas do mesmo site ligadas a este link, encontra todos os vídeos e baixa tudo automaticamente.", Icons.Rounded.TravelExplore) }
        item { LinkField(url, { url = it }, !busy, label = "Link do site") }
        item {
            BigButton("Varrer tudo", Icons.Rounded.TravelExplore, !busy && url.isNotBlank()) { Jobs.startScan(listOf(url), Jobs.Mode.SITE, true) }
            Spacer(Modifier.height(6.dp))
            Text("Limite: até ${Prefs.maxPages} páginas, profundidade ${Prefs.depth}, só o mesmo domínio (mude em Configurações).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mode == Jobs.Mode.SITE) {
            if (phase == Phase.CRAWLING) item { ScanProgress() }
            if (status.isNotBlank() && phase != Phase.CRAWLING) item { InfoCard(status) }
            if (items.isNotEmpty() && phase != Phase.CRAWLING) item {
                FilledTonalButton(goQueue, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Icon(Icons.Rounded.Downloading, null); Spacer(Modifier.width(8.dp)); Text("Ver ${items.size} vídeos na Fila") }
            }
        }
    }
}

// ---------------- Filtrar ----------------

@Composable
fun PageTab() {
    val phase by Jobs.phase.collectAsState(); val mode by Jobs.mode.collectAsState(); val status by Jobs.status.collectAsState()
    val busy = phase == Phase.CRAWLING || phase == Phase.RUNNING
    var url by rememberSaveable { mutableStateOf("") }
    val query = rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Section("Só os vídeos desta página", "Cole o link de uma página. O app lista apenas os vídeos dela (não abre outras páginas), todos já marcados.", Icons.Rounded.FilterAlt) }
        item { LinkField(url, { url = it }, !busy, label = "Link da página") }
        item { BigButton("Buscar vídeos desta página", Icons.Rounded.Search, !busy && url.isNotBlank()) { Jobs.startScan(listOf(url), Jobs.Mode.PAGE, false) } }
        if (mode == Jobs.Mode.PAGE) {
            if (phase == Phase.CRAWLING) item { ScanProgress() }
            if (status.isNotBlank() && phase != Phase.CRAWLING) item { InfoCard(status) }
            itemsPanel(query, showSend = true)
        }
    }
}

// ---------------- Fila ----------------

@Composable
fun QueueTab() {
    val items by Jobs.items.collectAsState(); val phase by Jobs.phase.collectAsState(); val status by Jobs.status.collectAsState()
    val query = rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (items.isEmpty()) {
            item { if (phase == Phase.CRAWLING) ScanProgress() else EmptyState(Icons.Rounded.Downloading, "Fila vazia", "Cole um link em Início, Varrer tudo ou Filtrar.") }
        } else {
            item { QueueSummary(items, phase) }
            if (phase == Phase.CRAWLING) item { ScanProgress() }
            if (status.isNotBlank() && phase == Phase.LIST) item { InfoCard(status) }
            itemsPanel(query, showSend = true)
        }
    }
}

@Composable
fun QueueSummary(items: List<Item>, phase: Phase) {
    val sel = items.filter { it.sel || it.st != St.WAIT }
    val done = sel.count { it.st == St.DONE }; val err = sel.count { it.st == St.ERR }
    val prog = if (sel.isEmpty()) 0f else sel.sumOf { (when (it.st) { St.DONE, St.ERR -> 1f; St.DOWN -> it.prog * 0.5f; St.READY -> 0.5f; St.UP -> 0.5f + it.prog * 0.5f; else -> 0f }).toDouble() }.toFloat() / sel.size
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(16.dp)) {
        Row { Stat("${items.size}", "na lista", Modifier.weight(1f)); Stat("$done", "enviados", Modifier.weight(1f)); Stat("$err", "com erro", Modifier.weight(1f)) }
        if (phase == Phase.RUNNING) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round) }
    } }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Icon(icon, null, Modifier.padding(20.dp).size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp)); Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Lista de vídeos com seleção em massa, filtro por nome e envio. Usada na Fila e no Filtrar. */
fun LazyListScope.itemsPanel(query: MutableState<String>, showSend: Boolean) {
    item(key = "tools") { SelectionTools(query, showSend) }
    item(key = "list") { ItemsList(query.value) }
}

@Composable
fun SelectionTools(query: MutableState<String>, showSend: Boolean) {
    val ctx = LocalContext.current
    val items by Jobs.items.collectAsState(); val phase by Jobs.phase.collectAsState()
    if (items.isEmpty()) return
    val editable = phase == Phase.LIST || phase == Phase.IDLE
    val sel = items.count { it.sel }; val sentN = items.count { it.sent }
    val selBytes = items.filter { it.sel && it.total > 0 }.sumOf { it.total }; val unknown = items.count { it.sel && it.total <= 0 }
    val qWords = remember(query.value) { NameFilter.words(query.value) }
    val shown = remember(items, qWords) { if (qWords.isEmpty()) items else items.filter { NameFilter.matches(it.f, qWords) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$sel de ${items.size} marcados" + (if (sentN > 0) " • $sentN já enviados" else "") + (if (selBytes > 0) " • ${fmtBytes(selBytes)}" + (if (unknown > 0) " + $unknown sem tamanho" else "") else ""),
            style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val p = PaddingValues(horizontal = 8.dp)
            FilledTonalButton({ Jobs.markNext(100) }, enabled = editable && sel < items.size, contentPadding = p, modifier = Modifier.weight(1f)) { Text("Marcar 100", maxLines = 1) }
            FilledTonalButton({ Jobs.selectAll(true) }, enabled = editable && sel < items.size, contentPadding = p, modifier = Modifier.weight(1f)) { Text("Marcar todos", maxLines = 1) }
            OutlinedButton({ Jobs.selectAll(false) }, enabled = editable && sel > 0, contentPadding = p, modifier = Modifier.weight(1f)) { Text("Desmarcar", maxLines = 1) }
        }
        OutlinedTextField(query.value, { query.value = it }, label = { Text("Filtrar por nome") }, placeholder = { Text("ex.: Aline Faria") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = MaterialTheme.shapes.medium,
            trailingIcon = { if (query.value.isNotEmpty()) IconButton({ query.value = "" }) { Icon(Icons.Rounded.Close, "Limpar filtro") } }, modifier = Modifier.fillMaxWidth())
        if (qWords.isNotEmpty()) {
            val ids = shown.map { it.id }.toSet()
            Text("${shown.size} encontrados • ${shown.count { it.sel }} marcados", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = PaddingValues(horizontal = 8.dp)
                Button({ Jobs.onlySel(ids) }, enabled = editable && shown.isNotEmpty(), contentPadding = p, modifier = Modifier.weight(1f)) { Text("Marcar só esses", maxLines = 1) }
                FilledTonalButton({ Jobs.setSel(ids, true) }, enabled = editable && shown.any { !it.sel }, contentPadding = p, modifier = Modifier.weight(1f)) { Text("Marcar +", maxLines = 1) }
                OutlinedButton({ Jobs.setSel(ids, false) }, enabled = editable && shown.any { it.sel }, contentPadding = p, modifier = Modifier.weight(1f)) { Text("Desmarcar esses", maxLines = 1) }
            }
        }
        if (showSend && editable) {
            val pend = items.count { it.sel && it.st != St.DONE }
            BigButton(if (pend == 0) "Tudo enviado ✓" else "Baixar e enviar $pend vídeo(s)", Icons.AutoMirrored.Rounded.Send, pend > 0) { Jobs.startTransfer() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ copy(ctx, items.joinToString("\n") { linkOf(it.f) }, "${items.size} links copiados") }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar links") }
            Spacer(Modifier.weight(1f))
            if (editable) TextButton({ Jobs.clear() }) { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Limpar lista") }
        }
    }
}

/** Lista renderizada dentro de um item (até 400 de cada vez para manter fluido; filtro por nome restringe). */
@Composable
fun ItemsList(q: String) {
    val items by Jobs.items.collectAsState(); val phase by Jobs.phase.collectAsState()
    val qWords = remember(q) { NameFilter.words(q) }
    val shown = remember(items, qWords) { if (qWords.isEmpty()) items else items.filter { NameFilter.matches(it.f, qWords) } }
    var limit by remember { mutableIntStateOf(200) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.take(limit).forEach { key(it.id) { ItemRow(it, phase == Phase.LIST || phase == Phase.IDLE) } }
        if (shown.size > limit) OutlinedButton({ limit += 200 }, Modifier.fillMaxWidth()) { Text("Mostrar mais (${shown.size - limit} restantes)") }
    }
}

@Composable
fun ItemRow(it: Item, editable: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(enabled = editable) { Jobs.toggle(it.id) }.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(it.sel, { _ -> Jobs.toggle(it.id) }, enabled = editable)
            Column(Modifier.weight(1f)) {
                Text(it.f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(it.f.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (it.sent) Chip("Já enviado", cs.tertiaryContainer, cs.onTertiaryContainer)
                    if (it.st == St.WAIT) Chip(if (it.total > 0) fmtBytes(it.total) else "Tamanho ?", cs.secondaryContainer, cs.onSecondaryContainer)
                    val (txt, color) = when (it.st) {
                        St.WAIT -> it.f.label to cs.onSurfaceVariant
                        St.DOWN -> (it.msg.ifBlank { "Baixando " + (if (it.total > 0) "${(it.prog * 100).toInt()}% de ${fmtBytes(it.total)}" else fmtBytes(it.bytes)) + (if (it.speed > 0) " • ${fmtBytes(it.speed)}/s" else "") }) to cs.primary
                        St.READY -> "Baixado (${fmtBytes(it.total)}) • aguardando envio" to cs.primary
                        St.UP -> "Enviando pro Telegram ${(it.prog * 100).toInt()}%" to cs.primary
                        St.DONE -> "Enviado para Mensagens Salvas" to cs.tertiary
                        St.ERR -> "Erro: ${it.msg}" to cs.error
                        St.STOPPED -> "Parado" to cs.error
                    }
                    Text(txt, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (it.st == St.DOWN || it.st == St.UP) LinearProgressIndicator(progress = { it.prog }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            when (it.st) {
                St.DONE -> Icon(Icons.Rounded.CheckCircle, "Enviado", Modifier.padding(12.dp), tint = cs.tertiary)
                St.ERR -> Icon(Icons.Rounded.ErrorOutline, "Erro", Modifier.padding(12.dp), tint = cs.error)
                else -> IconButton({ copy(ctx, linkOf(it.f), "Link copiado") }) { Icon(Icons.Rounded.ContentCopy, "Copiar link", tint = cs.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun Chip(t: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) =
    Surface(color = bg, shape = MaterialTheme.shapes.small) { Text(t, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }

// ---------------- Histórico ----------------

@Composable
fun HistoryTab(goQueue: () -> Unit) {
    val ctx = LocalContext.current
    val hist by History.list.collectAsState(); val phase by Jobs.phase.collectAsState(); val sync by Jobs.syncStatus.collectAsState()
    val busy = phase == Phase.CRAWLING || phase == Phase.RUNNING
    val df = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")) }
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hist.size} vídeos enviados", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton({ Jobs.syncTelegram() }) { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sincronizar") }
            }
            if (sync.isNotBlank()) Text(sync, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hist.isEmpty()) item { EmptyState(Icons.Rounded.History, "Nenhum envio ainda", "Os vídeos enviados ao Telegram aparecem aqui.") }
        items(hist.take(500), key = { it.time.toString() + it.f.url }) { h ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(df.format(Date(h.time)) + (if (h.size > 0) " • ${fmtBytes(h.size)}" else ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ copy(ctx, linkOf(h.f), "Link copiado") }) { Icon(Icons.Rounded.ContentCopy, "Copiar link", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FilledTonalIconButton({ if (Jobs.resend(h.f)) { Toast.makeText(ctx, "Reenviando…", Toast.LENGTH_SHORT).show(); goQueue() } else Toast.makeText(ctx, "Aguarde a tarefa atual terminar", Toast.LENGTH_SHORT).show() }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Reenviar pro Telegram") }
                }
            }
        }
        if (hist.isNotEmpty()) item { TextButton({ confirm = true }, Modifier.fillMaxWidth()) { Text("Limpar histórico", color = MaterialTheme.colorScheme.error) } }
    }
    if (confirm) AlertDialog({ confirm = false }, confirmButton = { TextButton({ History.clear(); Jobs.clearHistory(); confirm = false }) { Text("Limpar") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } }, title = { Text("Limpar histórico?") },
        text = { Text("Os vídeos já enviados deixarão de ser desmarcados automaticamente (até sincronizar de novo).") })
}

// ---------------- Configurações ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var depth by remember { mutableFloatStateOf(Prefs.depth.toFloat()) }
    var pages by remember { mutableStateOf(Prefs.maxPages.toString()) }
    var probe by remember { mutableStateOf(Prefs.probe) }
    var server by remember { mutableStateOf(Prefs.server) }
    var pd by remember { mutableFloatStateOf(Prefs.parDown.toFloat()) }
    var pu by remember { mutableFloatStateOf(Prefs.parUp.toFloat()) }
    var token by remember { mutableStateOf(Prefs.botToken) }
    var showTok by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(Prefs.quality) }
    var auto by remember { mutableStateOf(Prefs.autoSend) }
    var test by remember { mutableStateOf("") }; var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() { Prefs.depth = depth.toInt(); Prefs.maxPages = pages.toIntOrNull()?.coerceIn(1, 5000) ?: 500; Prefs.probe = probe; Prefs.parDown = pd.toInt(); Prefs.parUp = pu.toInt()
        Prefs.server = server.trim().ifBlank { Downloader.DEFAULT_SERVER }; Prefs.botToken = token.trim(); Prefs.quality = quality; Prefs.autoSend = auto }
    DisposableEffect(Unit) { onDispose { save() } }
    Scaffold(topBar = { TopAppBar(title = { Text("Configurações", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsGroup("Telegram", Icons.AutoMirrored.Rounded.Send) {
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Token do bot (opcional)") }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    visualTransformation = if (showTok) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton({ showTok = !showTok }) { Icon(if (showTok) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Mostrar") } }, modifier = Modifier.fillMaxWidth())
                Text("Os vídeos são enviados pela sua conta para as Mensagens Salvas (sem limite de 50 MB do bot). O token só é verificado no teste.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Envio automático", "Depois de encontrar, já baixa e envia (Início e Varrer tudo).", auto) { auto = it }
                FilledTonalButton({ save(); testing = true; test = ""; scope.launch { test = Jobs.testConnection(); testing = false } }, enabled = !testing, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                    if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.NetworkCheck, null); Spacer(Modifier.width(8.dp)); Text("Testar conexão") }
                if (test.isNotBlank()) InfoCard(test, if (test.contains("✗")) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, if (test.contains("✗")) 1 else 2)
            }
            SettingsGroup("Qualidade", Icons.Rounded.HighQuality) {
                val opts = listOf(0 to "Original (máxima)", 1080 to "Até 1080p", 720 to "Até 720p", 480 to "Até 480p")
                opts.forEach { (v, l) -> Row(Modifier.fillMaxWidth().clickable { quality = v }, verticalAlignment = Alignment.CenterVertically) { RadioButton(quality == v, { quality = v }); Text(l) } }
                Text("Nunca há compressão: o arquivo é enviado exatamente como baixado. O limite só escolhe a versão em sites que oferecem várias (HLS).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsGroup("Varredura", Icons.Rounded.TravelExplore) {
                Text("Profundidade dos links: ${depth.toInt()}")
                Slider(depth, { depth = it }, valueRange = 0f..20f, steps = 19)
                OutlinedTextField(pages, { pages = it.filter(Char::isDigit) }, label = { Text("Máximo de páginas") }, singleLine = true, shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                SwitchRow("Usar servidor (yt-dlp) em todas as páginas", "Acha vídeos de players difíceis. Mais lento.", probe) { probe = it }
            }
            SettingsGroup("Desempenho", Icons.Rounded.Speed) {
                Text("Downloads ao mesmo tempo: ${pd.toInt()}")
                Slider(pd, { pd = it }, valueRange = 1f..6f, steps = 4)
                Text("Envios ao Telegram ao mesmo tempo: ${pu.toInt()}")
                Slider(pu, { pu = it }, valueRange = 1f..3f, steps = 1)
                Text("Conta restrita? Deixe envios em 1.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(server, { server = it }, label = { Text("Servidor yt-dlp") }, singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            }
            SettingsGroup("Histórico de enviados", Icons.Rounded.History) {
                val sync by Jobs.syncStatus.collectAsState()
                var hist by remember { mutableIntStateOf(Sent.size) }
                Text("$hist chaves • vídeos já enviados aparecem desmarcados com “Já enviado”.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ Jobs.syncTelegram() }, Modifier.weight(1f)) { Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text("Sincronizar", maxLines = 1) }
                    OutlinedButton({ Jobs.clearHistory(); hist = 0 }, Modifier.weight(1f)) { Text("Limpar chaves", maxLines = 1) }
                }
                if (sync.isNotBlank()) { Text(sync, style = MaterialTheme.typography.bodySmall); LaunchedEffect(sync) { hist = Sent.size } }
            }
            OutlinedButton({ scope.launch { try { Td.send(TdApi.LogOut()) } catch (_: Exception) {} }; onClose() }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.AutoMirrored.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sair do Telegram") }
            Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun SettingsGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

@Composable
fun SwitchRow(t: String, d: String, v: Boolean, on: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.bodyLarge); Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.width(12.dp)); Switch(v, on)
}
