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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        br.geracaoai.baixartg.core.JsRender.bind(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { try { br.geracaoai.baixartg.core.Downloader.warmUp(br.geracaoai.baixartg.Prefs.server) } catch (_: Exception) {} }
        handle(intent)
        askBackgroundPermissions()
        br.geracaoai.baixartg.core.Pipeline.start()
        br.geracaoai.baixartg.core.Dedupe.resumeDelete()   // retoma a fila salva (app aberto = pode iniciar o serviço)
        setContent {
            val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
            AppTheme(dark) { Surface(color = MaterialTheme.colorScheme.background) { Root(shared) } }
        }
    }
    override fun onPause() {
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}
        super.onPause()
    }
    override fun onStop() {
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}
        super.onStop()
    }
    override fun onDestroy() {
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) {}
        br.geracaoai.baixartg.core.JsRender.unbind(this)
        BrowserRuntime.release()
        super.onDestroy()
    }
    /** Uma vez: notificações (Android 13+) e "não otimizar bateria" — para a fila não ser parada em segundo plano. */
    private fun askBackgroundPermissions() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (!Prefs.askedBattery && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Prefs.askedBattery = true
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
                startActivity(i)
            }
        } catch (_: Exception) {}
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
    CrashNotice()
    AnimatedContent(stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { s ->
        when (s) {
            0 -> SetupScreen { hasApi = true }
            1 -> { LaunchedEffect(auth) { if (Prefs.apiId == 0) hasApi = false }; LoginScreen(auth) }
            else -> MainShell(shared)
        }
    }
}

/** Se o app fechou sozinho da última vez, avisa e deixa copiar o motivo (gerado mesmo com o app fechado). */
@Composable
fun CrashNotice() {
    val rep by CrashLog.pending.collectAsState()
    val ctx = LocalContext.current
    val r = rep ?: return
    AlertDialog(onDismissRequest = { CrashLog.pending.value = null },
        icon = { Icon(Icons.Rounded.BugReport, null) },
        title = { Text("O app fechou da última vez") },
        text = { Text("O motivo foi registrado. Toque em Copiar e cole no chat para a gente corrigir.\n\n" + r.lines().filter { it.contains("FECHOU") || it.contains("QUEDA") || it.contains("WEBVIEW") }.takeLast(3).joinToString("\n").take(400),
            style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton({ copy(ctx, r, "Motivo da queda copiado. Cole no chat."); CrashLog.pending.value = null }) { Text("Copiar") } },
        dismissButton = { TextButton({ CrashLog.pending.value = null }) { Text("Fechar") } })
}

fun fmtBytes(b: Long): String {
    val df = DecimalFormat("#,##0.#")
    return when { b >= 1L shl 30 -> df.format(b / 1073741824.0) + " GB"; b >= 1L shl 20 -> df.format(b / 1048576.0) + " MB"; else -> df.format(b / 1024.0) + " KB" }
}

fun copy(ctx: Context, text: String, toast: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("links", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

/** Bunkr: a URL do CDN só vale com token; copia a página /f/, que sempre abre. */
fun linkOf(f: Found) = if (Bunkr.applies(f) && f.page != f.url) f.page else f.url
