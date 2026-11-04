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
        handle(intent)
        setContent {
            val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
            AppTheme(dark) { Surface(color = MaterialTheme.colorScheme.background) { Root(shared) } }
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
            else -> MainShell(shared)
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

/** Bunkr: a URL do CDN só vale com token; copia a página /f/, que sempre abre. */
fun linkOf(f: Found) = if (Bunkr.applies(f) && f.page != f.url) f.page else f.url
