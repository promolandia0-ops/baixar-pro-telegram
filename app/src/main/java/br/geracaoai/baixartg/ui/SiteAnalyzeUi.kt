package br.geracaoai.baixartg.ui

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.geracaoai.baixartg.BuildConfig
import br.geracaoai.baixartg.core.PlayCapture
import br.geracaoai.baixartg.core.SiteAnalyzer
import br.geracaoai.baixartg.core.SiteHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
fun SiteAnalyzeDialog(pageUrl: String, web: WebView?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("Preparando…") }
    var report by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(true) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun cancel() {
        job?.cancel()
        running = false
        if (report == null) onClose()
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    LaunchedEffect(pageUrl) {
        SiteHash.init(ctx)
        job = scope.launch {
            try {
                // 1) Raio-X: a investigação completa (player, rede no play, teste peça por peça, validade, app, fotos)
                val raio = try {
                    br.geracaoai.baixartg.core.RaioX.analyze(
                        page = pageUrl,
                        eval = { js -> withContext(Dispatchers.Main) { evalJs(web, js) }?.let { raw ->
                            if (raw == "null") null else try { (org.json.JSONTokener(raw).nextValue() as? String) ?: raw } catch (_: Exception) { raw } } },
                        tapPlay = { withContext(Dispatchers.Main) { try { web?.evaluateJavascript(TapPlay.JS) { } } catch (_: Exception) {} } },
                        progress = br.geracaoai.baixartg.core.RaioX.Progress { s -> main.post { step = s } },
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "=== RAIO-X === não terminou (${e.javaClass.simpleName})"
                }
                // 2) análise anterior (continua valendo) — instala o coletor de erros JS no WebView visível (só leitura)
                step = "Completando com a análise do site…"
                withContext(Dispatchers.Main) { web?.evaluateJavascript(SiteAnalyzer.HOOK_ERR_JS) { } }
                step = "Lendo o player…"
                val snapRaw = withContext(Dispatchers.Main) { evalJs(web, SiteAnalyzer.ANALYZE_JS) }
                val snap = SiteAnalyzer.parseJsSnap(snapRaw)
                val captured = try { PlayCapture.hits(pageUrl).map { it.url } } catch (_: Exception) { emptyList() }
                val webVer = try {
                    android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "desconhecido"
                } catch (_: Exception) { "desconhecido" }
                val photosOn = try { br.geracaoai.baixartg.Prefs.mediaMode != 0 } catch (_: Exception) { false }
                val out = withContext(Dispatchers.IO) {
                    SiteAnalyzer.run(
                        pageUrl = pageUrl,
                        appVersion = BuildConfig.VERSION_NAME,
                        webViewVersion = webVer,
                        photosOn = photosOn,
                        snap = snap,
                        captured = captured,
                        progress = SiteAnalyzer.Progress { s -> main.post { step = s } },
                    )
                }
                report = raio + "\n\n" + out.report
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                // falha do analisador: relatório mínimo, sem mensagem crua
                report = br.geracaoai.baixartg.core.AnonReport.sanitize(
                    "Baixar pro Telegram v${BuildConfig.VERSION_NAME}\nWebView: desconhecido\n\nPágina: carregou=não  HTTP=desconhecido  tempo=0ms  redirecionamentos=0\n\nAnti-robô: não\n\nSUGESTÃO: A página não carregou.\n"
                )
            } finally {
                running = false
            }
        }
    }

    Dialog(onDismissRequest = { if (running) cancel() else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Raio-X do site", style = MaterialTheme.typography.titleLarge)
                Text("Relatório anônimo: confira tudo antes de copiar. Não leva endereço, nome do site nem título.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                if (running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Dá play sozinho, grava o que o player pede e testa peça por peça. Leva uns 2 minutos — deixe a página aberta.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    OutlinedButton({ cancel() }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Cancelar") }
                } else {
                    val text = report.orEmpty()
                    Text(text, Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClose, Modifier.weight(1f)) { Text("Fechar") }
                        Button({
                            ctx.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("relatório anônimo", text))
                            Toast.makeText(ctx, "Relatório copiado. Confira de novo ao colar.", Toast.LENGTH_SHORT).show()
                        }, Modifier.weight(1f), enabled = text.isNotBlank()) { Text("Copiar") }
                    }
                }
            }
        }
    }
}

private suspend fun evalJs(web: WebView?, script: String): String? {
    if (web == null) return null
    return suspendCancellableCoroutine { cont ->
        try {
            web.evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw) }
        } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
    }
}
