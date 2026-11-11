package br.geracaoai.baixartg.web

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import br.geracaoai.baixartg.core.CookieSource

/**
 * Sessão do navegador do app: os cookies ficam só no CookieManager do WebView (como num navegador comum).
 * Nenhuma senha é lida ou guardada. Aqui só lembramos os NOMES dos domínios visitados para listar em "Contas logadas".
 */
object WebCookies : CookieSource {
    private lateinit var p: SharedPreferences
    fun init(c: Context) {
        p = c.getSharedPreferences("web_domains", Context.MODE_PRIVATE)
        val cm0 = CookieManager.getInstance()
        cm0.setAcceptCookie(true)
        cm0.flush()
    }
    private val cm get() = CookieManager.getInstance()

    override fun cookieFor(url: String): String? {
        val direct = try { cm.getCookie(url) } catch (_: Exception) { null }
        if (!direct.isNullOrBlank()) return direct
        val host = hostOf(url) ?: return null
        for (u in listOf("https://$host/", "https://www.$host/", "http://$host/", "https://m.$host/")) {
            val c = try { cm.getCookie(u) } catch (_: Exception) { null }
            if (!c.isNullOrBlank()) return c
        }
        return null
    }
    override fun store(url: String, setCookie: String) { try { cm.setCookie(url, setCookie) } catch (_: Exception) {} }

    override fun knownHosts(): List<String> {
        val extra = listOf("youtube.com", "www.youtube.com", "m.youtube.com", "google.com", "www.google.com")
        return (domains() + extra).distinct().filter { !cookieFor("https://$it/").isNullOrBlank() }
    }

    /** Grava os cookies no perfil persistente do WebView (disco). */
    fun persist() { try { cm.flush() } catch (_: Exception) {} }

    /** Chamado quando uma página termina de carregar no Navegador. */
    fun remember(url: String) {
        val host = hostOf(url) ?: return
        if (!cm.getCookie("https://$host").isNullOrBlank()) p.edit().putBoolean(baseDomain(host), true).apply()
        cm.flush()
    }

    /** Domínios com cookies guardados agora. */
    fun domains(): List<String> = p.all.keys.filter { !cm.getCookie("https://$it").isNullOrBlank() || !cm.getCookie("https://www.$it").isNullOrBlank() }.sorted()

    /** Sai de um site: expira todos os cookies visíveis desse domínio e subdomínios conhecidos. */
    fun logout(domain: String) {
        val hosts = listOf(domain, "www.$domain")
        for (h in hosts) for (scheme in listOf("https", "http")) {
            val url = "$scheme://$h/"
            val names = cm.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() } ?: continue
            for (n in names) for (d in listOf(null, h, ".$h", ".$domain", domain)) {
                cm.setCookie(url, "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/" + (d?.let { "; Domain=$it" } ?: ""))
            }
        }
        cm.flush(); p.edit().remove(domain).apply()
    }

    fun clearAll(done: () -> Unit = {}) {
        cm.removeAllCookies { cm.flush(); done() }
        WebStorage.getInstance().deleteAllData()
        p.edit().clear().apply()
    }

    fun hostOf(url: String) = try { java.net.URI(url).host?.lowercase() } catch (_: Exception) { null }
    /** example.com.br / sub.example.com -> domínio base (aproximado). */
    fun baseDomain(host: String): String {
        val parts = host.removePrefix("www.").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val n = if (sld.length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }
}
