package br.geracaoai.baixartg.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.geracaoai.baixartg.Prefs
import br.geracaoai.baixartg.tg.Td
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

@Composable
fun AuthFrame(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var id by remember { mutableStateOf(Prefs.apiId.takeIf { it != 0 }?.toString() ?: "") }
    var hash by remember { mutableStateOf(Prefs.apiHash) }
    AuthFrame(Icons.Rounded.Key, "Configurar acesso", "O Telegram exige uma chave própria (gratuita) para apps como este. Leva 2 minutos:") {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1. Toque em “Abrir my.telegram.org” abaixo.",
                    "2. Digite seu número com +55 e toque em Next.",
                    "3. O código chega no próprio app do Telegram (não por SMS). Cole e entre.",
                    "4. Toque em “API development tools”.",
                    "5. Preencha App title: Baixar e Short name: baixar123 (se já tem um app criado, use o mesmo api_id) (o resto pode ficar vazio; Platform: Android).",
                    "6. Toque em Create application.",
                    "7. Copie o “App api_id” (número) e o “App api_hash” (texto longo) para os campos abaixo.").forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))) }) {
            Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Abrir my.telegram.org") }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text("api_id") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Seus dados ficam só neste celular. A chave não dá acesso à sua conta sem o código de login.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button({ Prefs.apiId = id.toIntOrNull() ?: 0; Prefs.apiHash = hash; Td.sendParams(); onDone() },
            enabled = id.isNotBlank() && hash.length >= 20, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Continuar") }
    }
}

@Composable
fun LoginScreen(state: TdApi.AuthorizationState?) {
    val scope = rememberCoroutineScope()
    var input by remember(state) { mutableStateOf(if (state is TdApi.AuthorizationStateWaitPhoneNumber) "+55" else "") }
    var err by remember(state) { mutableStateOf<String?>(null) }
    var busy by remember(state) { mutableStateOf(false) }
    fun go(f: TdApi.Function<*>) { busy = true; err = null; scope.launch { try { Td.send(f) } catch (e: Exception) { err = traduz(e.message) }; busy = false } }
    AnimatedContent(state?.javaClass, transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut()) }, label = "auth") { _ ->
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> AuthFrame(Icons.Rounded.PhoneAndroid, "Entrar no Telegram", "Use o número da sua conta. Os vídeos vão para as suas Mensagens Salvas.") {
                OutlinedTextField(input, { input = it }, label = { Text("Número com DDD") }, singleLine = true, isError = err != null, supportingText = { err?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button({ go(TdApi.SetAuthenticationPhoneNumber(input.filter { it.isDigit() || it == '+' }, null)) }, enabled = !busy && input.length > 8, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Enviar código") }
                TextButton({ Prefs.apiId = 0; Prefs.apiHash = ""; go(TdApi.Close()) }) { Text("Trocar api_id/api_hash") }
            }
            is TdApi.AuthorizationStateWaitCode -> AuthFrame(Icons.Rounded.Sms, "Digite o código", "Enviamos um código para o app do Telegram em outro aparelho (ou por SMS).") {
                OutlinedTextField(input, { input = it.filter(Char::isDigit) }, label = { Text("Código") }, singleLine = true, isError = err != null, supportingText = { err?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button({ go(TdApi.CheckAuthenticationCode(input)) }, enabled = !busy && input.length >= 5, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Entrar") }
            }
            is TdApi.AuthorizationStateWaitPassword -> AuthFrame(Icons.Rounded.Lock, "Verificação em duas etapas", "Digite sua senha do Telegram." + (state.passwordHint.takeIf { it.isNotBlank() }?.let { "\nDica: $it" } ?: "")) {
                OutlinedTextField(input, { input = it }, label = { Text("Senha") }, singleLine = true, isError = err != null, supportingText = { err?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button({ go(TdApi.CheckAuthenticationPassword(input)) }, enabled = !busy && input.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Entrar") }
            }
            is TdApi.AuthorizationStateWaitRegistration -> AuthFrame(Icons.Rounded.Info, "Conta não encontrada", "Crie sua conta no app oficial do Telegram primeiro e depois volte aqui.") {}
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp))
                    Text(Prefs.lastError.takeIf { it.isNotBlank() }?.let { "Erro: ${traduz(it)}" } ?: "Conectando ao Telegram…")
                    if (Prefs.lastError.contains("API_ID", true)) TextButton({ Prefs.apiId = 0; Prefs.apiHash = ""; Prefs.lastError = ""; go(TdApi.Close()) }) { Text("Corrigir api_id/api_hash") }
                }
            }
        }
    }
}

fun traduz(m: String?): String = when {
    m == null -> "Erro desconhecido"
    "PHONE_NUMBER_INVALID" in m -> "Número inválido. Use o formato +55 11 99999-9999."
    "PHONE_CODE_INVALID" in m -> "Código incorreto."
    "PHONE_CODE_EXPIRED" in m -> "Código expirou. Volte e peça outro."
    "PASSWORD_HASH_INVALID" in m -> "Senha incorreta."
    "API_ID" in m -> "api_id ou api_hash inválido."
    "FLOOD" in m || "Too Many" in m -> "Muitas tentativas. Aguarde alguns minutos."
    else -> m
}

