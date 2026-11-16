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
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Limpar repetidos", fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Procura vídeos repetidos nas suas Mensagens Salvas do Telegram. Só lê as informações (arquivo, tamanho, duração) — não baixa nada. De cada vídeo repetido fica a cópia MAIS RECENTE. Nada é apagado sem você confirmar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (phase) {
                Dedupe.Phase.IDLE, Dedupe.Phase.ERROR -> {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Incluir fotos", Modifier.weight(1f)); Switch(photos, { photos = it }) }
                    Button({ Dedupe.analyze(photos) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Só analisar (não apaga nada)") }
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
                            FilledTonalButton({ Dedupe.setAll(true) }, Modifier.weight(1f)) { Text("Marcar todos", maxLines = 1) }
                            OutlinedButton({ Dedupe.setAll(false) }, Modifier.weight(1f)) { Text("Desmarcar todos", maxLines = 1) }
                        }
                        Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = n > 0, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.width(8.dp)); Text("Apagar ${Dedupe.fmtN(n)} repetidos") }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(gs, key = { it.keep }) { g -> GroupRow(g, excluded) }
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
            text = { Text("Vão ser apagadas das suas Mensagens Salvas só as cópias repetidas. De cada vídeo fica a mensagem mais recente. Isso não pode ser desfeito.") },
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
