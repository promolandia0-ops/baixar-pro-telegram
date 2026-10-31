package br.geracaoai.baixartg.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 96; maxRequestsPerHost = 24 })
        .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
        .addInterceptor { ch ->
            val r = ch.request()
            val b = r.newBuilder()
            if (r.header("User-Agent") == null) b.header("User-Agent", UA)
            if (r.header("Accept-Language") == null) b.header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            ch.proceed(b.build())
        }.build()
    /** Cliente com timeout longo para o servidor (Render pode demorar para acordar / yt-dlp). */
    val slow: OkHttpClient = http.newBuilder().connectTimeout(90, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
}

/** Executa a chamada de forma cancelável (o botão Parar cancela a conexão na hora). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
    })
}
