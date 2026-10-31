package br.geracaoai.baixartg.tg

import android.content.Context
import android.os.Build
import br.geracaoai.baixartg.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdException(val code: Int, msg: String) : Exception(msg)

/** Resultado final do envio de uma mensagem (sucesso ou falha). */
class SendResult(val message: TdApi.Message?, val error: TdApi.Error?)

object Td {
    private lateinit var app: Context
    @Volatile private var client: Client? = null
    val auth = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 512)
    val isPremium = MutableStateFlow(false)
    private val sends = ConcurrentHashMap<Long, CompletableDeferred<SendResult>>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (client != null) return
        try { Client.execute(TdApi.SetLogVerbosityLevel(1)) } catch (_: Throwable) {}
        client = Client.create({ onUpdate(it) }, null, null)
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> {
                val s = o.authorizationState
                auth.value = s
                if (s is TdApi.AuthorizationStateWaitTdlibParameters) sendParams()
                if (s is TdApi.AuthorizationStateClosed) { client = null; init(app) }
            }
            is TdApi.UpdateFile -> fileUpdates.tryEmit(o.file)
            is TdApi.UpdateMessageSendSucceeded -> sends.getOrPut(o.oldMessageId) { CompletableDeferred() }.complete(SendResult(o.message, null))
            is TdApi.UpdateMessageSendFailed -> sends.getOrPut(o.oldMessageId) { CompletableDeferred() }.complete(SendResult(null, o.error))
            is TdApi.UpdateOption -> if (o.name == "is_premium") isPremium.value = (o.value as? TdApi.OptionValueBoolean)?.value == true
            else -> {}
        }
    }

    /** Envia os parâmetros se o usuário já configurou api_id/api_hash. */
    fun sendParams() {
        val c = client ?: return
        if (auth.value !is TdApi.AuthorizationStateWaitTdlibParameters) return
        val id = Prefs.apiId; val hash = Prefs.apiHash
        if (id == 0 || hash.isBlank()) return
        val p = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(app.filesDir, "tdlib").absolutePath
            filesDirectory = File(app.filesDir, "tdfiles").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = true; useSecretChats = false
            apiId = id; apiHash = hash
            systemLanguageCode = "pt-BR"; deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: ""; applicationVersion = "BaixarProTelegram 1.0"
        }
        c.send(p) { r -> if (r is TdApi.Error) Prefs.lastError = r.message }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(f: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) { cont.resumeWithException(TdException(500, "TDLib não iniciado")); return@suspendCancellableCoroutine }
        c.send(f) { r ->
            if (!cont.isActive) return@send
            if (r is TdApi.Error) cont.resumeWithException(TdException(r.code, r.message)) else cont.resume(r as T)
        }
    }

    suspend fun awaitSend(tempId: Long): SendResult {
        val d = sends.getOrPut(tempId) { CompletableDeferred() }
        try { return d.await() } finally { sends.remove(tempId) }
    }

    /** Mensagens que ainda estão sendo enviadas (id temporário). */
    fun pendingSends(): List<Long> = sends.keys.toList()

    suspend fun awaitReady(timeoutMs: Long = 30_000): Boolean =
        withTimeoutOrNull(timeoutMs) { auth.first { it is TdApi.AuthorizationStateReady } } != null

    val ready get() = auth.value is TdApi.AuthorizationStateReady
}
