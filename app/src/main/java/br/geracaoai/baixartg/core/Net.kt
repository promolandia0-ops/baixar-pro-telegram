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

/** Fonte dos cookies (no app = CookieManager do WebView; nos testes = memória). */
interface CookieSource {
    fun cookieFor(url: String): String?
    fun store(url: String, setCookie: String) {}
}

object Net {
    /** Mesmo User-Agent do WebView (definido no App ao iniciar). */
    @Volatile var userAgent: String = UA
    @Volatile var cookies: CookieSource? = null
    /** Hosts que nunca recebem cookies do navegador (API do Telegram, MEGA). */
    private val noCookieHosts = listOf("api.telegram.org", "mega.co.nz", "mega.nz", "mega.io")

    fun originOf(url: String): String? = try { java.net.URI(url).let { if (it.scheme == null || it.authority == null) null else "${it.scheme}://${it.authority}/" } } catch (_: Exception) { null }

    /** Acrescenta cookies da sessão do navegador, User-Agent do WebView e Referer. Público para teste. */
    fun decorate(r: okhttp3.Request): okhttp3.Request {
        val b = r.newBuilder()
        val url = r.url.toString()
        if (r.header("User-Agent") == null) b.header("User-Agent", userAgent)
        if (r.header("Accept-Language") == null) b.header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
        if (r.header("Referer") == null && r.method == "GET") originOf(url)?.let { b.header("Referer", it) }
        return b.build()
    }

    /** Cookie de cada salto (inclusive redirecionamentos): só os cookies do domínio daquele salto. */
    fun withCookies(r: okhttp3.Request): okhttp3.Request {
        val host = r.url.host
        val b = r.newBuilder().removeHeader("Cookie")
        if (noCookieHosts.none { host == it || host.endsWith(".$it") }) {
            val c = try { cookies?.cookieFor(r.url.toString()) } catch (_: Exception) { null }
            if (!c.isNullOrBlank()) b.header("Cookie", c)
        }
        return b.build()
    }
    private fun newClient(connectS: Long, readS: Long, writeS: Long, callS: Long, maxReq: Int, maxHost: Int, pool: Int): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(connectS, TimeUnit.SECONDS)
            .readTimeout(readS, TimeUnit.SECONDS)
            .writeTimeout(writeS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true).followSslRedirects(true)
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = maxReq; maxRequestsPerHost = maxHost })
            .connectionPool(okhttp3.ConnectionPool(pool, 5, TimeUnit.MINUTES))
            .addInterceptor { ch -> ch.proceed(decorate(ch.request())) }
            .addNetworkInterceptor { ch ->
                val req = withCookies(ch.request())
                val resp = ch.proceed(req)
                val sc = resp.headers("Set-Cookie")
                if (sc.isNotEmpty()) cookies?.let { cs -> sc.forEach { try { cs.store(resp.request.url.toString(), it) } catch (_: Exception) {} } }
                resp
            }
        if (callS > 0) b.callTimeout(callS, TimeUnit.SECONDS)
        return b.build()
    }

    /** Downloads, tamanhos, Telegram — timeouts folgados. */
    val http: OkHttpClient = newClient(30, 90, 90, 0, 96, 24, 32)
    /** Varredura e busca do site: timeouts curtos, muita paralelismo, HTTP/2 quando o site oferece. */
    val fast: OkHttpClient = newClient(5, 8, 8, 12, 128, 32, 64)
    /** Cliente com timeout longo para o servidor (Render pode demorar para acordar / yt-dlp). */
    val slow: OkHttpClient = http.newBuilder().connectTimeout(90, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()
    /** Só do navegador (detecção): conexões e fila de pedidos próprias — nunca espera atrás dos downloads/envios da fila. */
    val browser: OkHttpClient = newClient(8, 20, 20, 40, 64, 16, 16)
    val browserSlow: OkHttpClient = newClient(30, 45, 45, 50, 16, 8, 8)
}

/** Executa a chamada de forma cancelável (o botão Parar cancela a conexão na hora). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { try { cancel() } catch (_: Throwable) {} }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
    })
}
