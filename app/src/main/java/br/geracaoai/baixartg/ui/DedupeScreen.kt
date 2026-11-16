package br.geracaoai.baixartg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.core.Dedupe
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import java.text.SimpleDateFormat
import java.util.*

private val DF = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
private fun dt(s: Int) = if (s <= 0) "" else DF.format(Date(s * 1000L))
private fun mb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupeScreen(onClose: () -> Unit) {
    val phase by Dedupe.phase.collectAsState()
    val status by Dedupe.status.collectAsState()
    val excluded by Dedupe.excluded.collectAsState()
    val dDone by Dedupe.delDone.collectAsState(); val dTotal by Dedupe.delTotal.collectAsState()
    var photos by remember { mutableStateOf(false) }
    var byImage by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Limpar repetidos", fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (byImage || (phase == Dedupe.Phase.RESULT && Dedupe.visual)) "Por imagem: compara a duração e a miniatura que o Telegram já tem de cada vídeo (baixa só as miniaturas, nunca os vídeos). Bem rigoroso. Nada vem marcado: você confere as miniaturas lado a lado e marca o que é igual. De cada grupo fica sempre a cópia de maior qualidade."
                else "Procura vídeos repetidos nas suas Mensagens Salvas do Telegram. Só lê as informações (arquivo, tamanho, duração) — não baixa nada. De cada vídeo repetido fica a cópia MAIS RECENTE. Nada é apagado sem você confirmar",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (phase) {
                Dedupe.Phase.IDLE, Dedupe.Phase.ERROR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!byImage, { byImage = false }, label = { Text("Exato (rápido)") })
                        FilterChip(byImage, { byImage = true }, label = { Text("Por imagem (mais lento)") })
                    }
                    if (!byImage) Row(verticalAlignment = Alignment.CenterVertically) { Text("Incluir fotos", Modifier.weight(1f)); Switch(photos, { photos = it }) }
                    Button({ Dedupe.analyze(photos, byImage) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Só analisar (não apaga nada)") }
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (Dedupe.hasPendingDelete()) OutlinedButton({ Dedupe.resumeDelete() }, Modifier.fillMaxWidth()) { Text("Continuar apagando (interrompido)") }
                }
                Dedupe.Phase.SCANNING -> {
                    val n by Dedupe.scanned.collectAsState(); val est by Dedupe.estimate.collectAsState()
                    if (est > 0) LinearProgressIndicator({ (n.toFloat() / est).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ Dedupe.cancel() }, Modifier.fillMaxWidth()) { Text("Cancelar") }
                }
                Dedupe.Phase.DELETING -> {
                    LinearProgressIndicator({ if (dTotal > 0) dDone.toFloat() / dTotal else 0f }, Modifier.fillMaxWidth())
                    Text(status.ifBlank { "Apagando…" }, style = MaterialTheme.typography.bodyMedium)
                    Text("Continua em segundo plano, pode sair desta tela.", style = MaterialTheme.typography.bodySmall)
                }
                Dedupe.Phase.DONE -> {
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    OutlinedButton({ Dedupe.reset() }, Modifier.fillMaxWidth()) { Text("Analisar de novo") }
                }
                Dedupe.Phase.RESULT -> {
                    val gs = Dedupe.groups
                    val n = remember(excluded, gs) { Dedupe.toDeleteCount() }
                    val bytes = remember(excluded, gs) { Dedupe.toDeleteBytes() }
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                        Text("${Dedupe.fmtN(gs.size)} vídeos com cópias • ${Dedupe.fmtN(n)} mensagens a apagar • libera ${mb(bytes)}", style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    } }
                    if (gs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (Dedupe.visual) FilledTonalButton({ Dedupe.markSuggested() }, Modifier.weight(1f), enabled = gs.any { it.sure }) { Text("Marcar sugeridos (${gs.count { it.sure }})", maxLines = 1) }
                            else FilledTonalButton({ Dedupe.setAll(true) }, Modifier.weight(1f)) { Text("Marcar todos", maxLines = 1) }
                            OutlinedButton({ Dedupe.setAll(false) }, Modifier.weight(1f)) { Text("Desmarcar todos", maxLines = 1) }
                        }
                        Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = n > 0, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Apagar ${Dedupe.fmtN(n)} repetidos") }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(gs, key = { it.keep }) { g -> if (Dedupe.visual) VisualGroupRow(g, excluded) else GroupRow(g, excluded) }
                        }
                    } else OutlinedButton({ Dedupe.reset() }, Modifier.fillMaxWidth()) { Text("Voltar") }
                }
            }
        }
    }
    if (confirm) {
        val n = Dedupe.toDeleteCount()
        AlertDialog(onDismissRequest = { confirm = false }, icon = { Icon(Icons.Rounded.Warning, null) },
            title = { Text("Apagar ${Dedupe.fmtN(n)} mensagens?") },
            text = { Text(if (Dedupe.visual) "Vão ser apagadas só as cópias que você marcou. De cada grupo fica sempre pelo menos uma cópia. Isso não pode ser desfeito."
                else "Vão ser apagadas das suas Mensagens Salvas só as cópias repetidas. De cada vídeo fica a mensagem mais recente. Isso não pode ser desfeito.") },
            confirmButton = { TextButton({ confirm = false; Dedupe.startDelete() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Apagar") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun GroupRow(g: Dedupe.G, excluded: Set<Long>) {
    var open by remember { mutableStateOf(false) }
    val keep = Dedupe.info[g.keep]
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(keep?.cap?.ifBlank { null } ?: "Vídeo sem legenda", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text("Fica: ${dt(keep?.date ?: 0)} • ${mb(keep?.size ?: 0)} • ${g.dels.size} cópia(s) a apagar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (open) g.dels.forEach { id ->
                val e = Dedupe.info[id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id !in excluded, { v -> Dedupe.toggle(id, v) })
                    Column { Text(e?.cap?.ifBlank { null } ?: "cópia", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text("Apagar: ${dt(e?.date ?: 0)} • ${mb(e?.size ?: 0)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun durTxt(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

@Composable
private fun Thumb(path: String?, modifier: Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = if (path == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, o)
                var ss = 1; while (o.outWidth / (ss * 2) >= 200) ss *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
            } catch (_: Throwable) { null }
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        bmp?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}

/** Modo por imagem: as cópias lado a lado com miniatura, duração, resolução, tamanho e legenda. Nada vem marcado. */
@Composable
private fun VisualGroupRow(g: Dedupe.G, excluded: Set<Long>) {
    val ids = listOf(g.keep) + g.dels.toList()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(if (g.sure) "Muito provável que sejam iguais" else "Parecidos — confira com atenção", style = MaterialTheme.typography.labelMedium,
                color = if (g.sure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ids.forEach { id ->
                    val e = Dedupe.info[id]; val keep = id == g.keep
                    Column(Modifier.width(150.dp)) {
                        Thumb(Dedupe.thumbs[id], Modifier.fillMaxWidth().height(90.dp))
                        Text("${durTxt(e?.dur ?: 0)} • ${e?.w ?: 0}×${e?.h ?: 0}", style = MaterialTheme.typography.labelSmall)
                        Text("${mb(e?.size ?: 0)} • ${dt(e?.date ?: 0)}", style = MaterialTheme.typography.labelSmall)
                        Text(e?.cap?.ifBlank { null } ?: "sem legenda", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        if (keep) Text("FICA (maior qualidade)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id !in excluded, { v -> Dedupe.toggle(id, v) })
                            Text("Apagar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
