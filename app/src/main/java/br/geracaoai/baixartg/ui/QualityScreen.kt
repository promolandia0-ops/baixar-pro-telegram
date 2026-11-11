package br.geracaoai.baixartg.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Jobs
import br.geracaoai.baixartg.core.Phase
import br.geracaoai.baixartg.core.QualitySearch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun QualityTab(goQueue: () -> Unit) {
    val q by QualitySearch.query.collectAsState()
    val link by QualitySearch.link.collectAsState()
    val filter by QualitySearch.filter.collectAsState()
    val raw by QualitySearch.items.collectAsState()
    val loading by QualitySearch.loading.collectAsState()
    val loadingMore by QualitySearch.loadingMore.collectAsState()
    val hasMore by QualitySearch.hasMore.collectAsState()
    val pending by QualitySearch.pending.collectAsState()
    val err by QualitySearch.error.collectAsState()
    val status by QualitySearch.status.collectAsState()
    val phase by Jobs.phase.collectAsState()
    val busy = loading || phase == Phase.CRAWLING || phase == Phase.RUNNING
    var showLink by rememberSaveable { mutableStateOf(false) }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val visible = remember(raw, filter) { QualitySearch.visible() }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    LaunchedEffect(listState, visible.size, hasMore, loadingMore, loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { idx -> if (idx >= visible.size - 3 && hasMore && !loading && !loadingMore) QualitySearch.loadMore() }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Section("Buscar em 4K", "Digite um tema, um nome ou o título de um vídeo. O app consulta várias fontes ao mesmo tempo e mostra os primeiros resultados na hora; a resolução entra pelo título e é completada depois quando der. Sem filtro de idade; sites de vazamento e pirataria continuam bloqueados.", Icons.Rounded.HighQuality)
        }
        item {
            OutlinedTextField(q, { QualitySearch.query.value = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium,
                label = { Text("O que você quer buscar") }, placeholder = { Text("ex.: Big Buck Bunny") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
        }
        item {
            TextButton({ showLink = !showLink }) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(8.dp)); Text(if (showLink) "Ocultar o link" else "Já tenho um link (achar o mesmo vídeo em melhor qualidade)") }
            if (showLink) OutlinedTextField(link, { QualitySearch.link.value = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium, label = { Text("Link de um vídeo (opcional)") })
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == QualitySearch.Filter.UHD, { QualitySearch.filter.value = QualitySearch.Filter.UHD }, { Text("4K") })
                FilterChip(filter == QualitySearch.Filter.HD, { QualitySearch.filter.value = QualitySearch.Filter.HD }, { Text("1080p+") })
                FilterChip(filter == QualitySearch.Filter.ANY, { QualitySearch.filter.value = QualitySearch.Filter.ANY }, { Text("Qualquer") })
            }
        }
        item { BigButton("Buscar", Icons.Rounded.Search, !busy && (q.isNotBlank() || link.isNotBlank())) { QualitySearch.search() } }
        if (loading && raw.isEmpty()) item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text("Buscando… o servidor pode demorar um pouco para acordar. Os primeiros resultados aparecem assim que uma fonte responder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (err.isNotBlank() && raw.isEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error) }
        if (status.isNotBlank() && !loading) item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pending && raw.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Text("Buscando mais resultados…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (QualitySearch.filterFallback() && visible.isNotEmpty()) item { Text("Nenhum vídeo com essa qualidade confirmada. Mostrando o melhor que achei (a qualidade no título é uma estimativa).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (visible.isNotEmpty()) item {
            val n = visible.size
            Button({
                if (QualitySearch.enqueue(visible, start = true)) goQueue()
                else android.widget.Toast.makeText(ctx, "Há uma tarefa em andamento. Espere terminar.", android.widget.Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth().height(48.dp), enabled = !busy, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(8.dp)); Text("Baixar todos ($n) e enviar")
            }
        }
        itemsIndexed(visible, key = { _, f -> f.url }) { i, f ->
            QualityCard(f, playing = previewUrl != null && previewUrl == f.preview, onPreview = {
                previewUrl = if (previewUrl == f.preview) null else f.preview
            }, onDownload = {
                if (QualitySearch.enqueue(listOf(f), start = true)) goQueue()
                else android.widget.Toast.makeText(ctx, "Há uma tarefa em andamento. Espere terminar.", android.widget.Toast.LENGTH_SHORT).show()
            }, enabled = !busy)
        }
        if (loadingMore) item { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
        if (visible.isEmpty() && !loading && raw.isNotEmpty()) item {
            Text("Nenhum resultado neste filtro. Toque em “Qualquer” para ver os outros.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QualityCard(f: Found, playing: Boolean, onPreview: () -> Unit, onDownload: () -> Unit, enabled: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PreviewThumb(f.thumb, f.preview, playing, onPreview, Modifier.size(120.dp, 74.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(qualityLabel(f.height, f.title).takeIf { it != "—" }, fmtDur(f.duration).takeIf { f.duration > 0 }, f.site.ifBlank { null }, if (f.size > 0) fmtBytes(f.size) else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!f.preview.isNullOrBlank()) Text("Toque na miniatura para a prévia (sem som)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onDownload, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Baixar melhor qualidade")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewThumb(thumb: String?, preview: String?, playing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.combinedClickable(onClick = { if (!preview.isNullOrBlank()) onToggle() }, onLongClick = { if (!preview.isNullOrBlank()) onToggle() }).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (playing && !preview.isNullOrBlank()) {
            key(preview) {
                AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(Uri.parse(preview))
                        setOnPreparedListener { mp -> mp.isLooping = true; mp.setVolume(0f, 0f); start() }
                        setOnErrorListener { _, _, _ -> false }
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        } else {
            Thumb(thumb, Modifier.fillMaxSize())
        }
        if (!preview.isNullOrBlank() && !playing) {
            Text(qualityLabelOrPlay(), Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun qualityLabelOrPlay() = "prévia"

fun qualityLabel(h: Int, title: String = ""): String {
    val likely = if (h <= 0) QualitySearch.heightFromTitle(title) else 0
    val used = if (h > 0) h else likely
    val tag = when {
        used >= 2160 -> "4K"
        used >= 1440 -> "1440p"
        used >= 1080 -> "1080p"
        used >= 720 -> "720p"
        used >= 480 -> "480p"
        used > 0 -> "${used}p"
        else -> ""
    }
    return when {
        h > 0 -> tag
        likely > 0 -> "provável $tag"
        else -> "—"
    }
}

fun fmtDur(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
