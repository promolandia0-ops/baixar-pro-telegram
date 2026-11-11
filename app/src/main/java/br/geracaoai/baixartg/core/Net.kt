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
    fun knownHosts(): List<String> = emptyList()
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
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 96; maxRequestsPerHost = 24 })
        .connectionPool(okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES))
        .addInterceptor { ch ->
            ch.proceed(decorate(ch.request()))
        }.addNetworkInterceptor { ch ->
            val req = withCookies(ch.request())
            val resp = ch.proceed(req)
            // Cookies novos que o site manda voltam para a sessão do navegador
            val sc = resp.headers("Set-Cookie")
            if (sc.isNotEmpty()) {
                val rh = req.url.host
                if (noCookieHosts.none { rh == it || rh.endsWith(".$it") }) {
                    cookies?.let { cs -> sc.forEach { try { cs.store(resp.request.url.toString(), it) } catch (_: Exception) {} } }
                }
            }
            resp
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
