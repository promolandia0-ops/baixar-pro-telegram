package br.geracaoai.baixartg.core

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** Descobre a busca do próprio site e reconhece links do YouTube para busca via yt-dlp. */
object SiteSearch {
    private val SEARCH_PATH = Regex("""/(search|busca|buscar|find|query|results)(/|$|\?)""", RegexOption.IGNORE_CASE)
    private val SEARCH_QUERY = Regex("""(?:^|[?&])(q|s|query|search|search_query|k|keyword|termo|palavra)=""", RegexOption.IGNORE_CASE)
    private val FOREIGN_SEARCH = Regex("""(?:google|bing|duckduckgo|yahoo|yandex|baidu|startpage)\.""", RegexOption.IGNORE_CASE)
    private val YT_HOST = Regex("""(?:^|\.)(?:youtube\.com|youtu\.be|youtube-nocookie\.com)$""", RegexOption.IGNORE_CASE)

    fun encode(q: String): String = URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")

    fun isYouTube(url: String): Boolean {
        val h = Extract.host(url)
        return YT_HOST.containsMatchIn(h) || h == "youtu.be"
    }

    fun matches(text: String, words: List<String>): Boolean = NameFilter.matchesText(text, words)

    fun isSearchUrl(url: String): Boolean {
        val pathAndQ = try {
            val u = URI(url); (u.path ?: "") + "?" + (u.query ?: "")
        } catch (_: Exception) { url }
        return SEARCH_PATH.containsMatchIn(pathAndQ) || SEARCH_QUERY.containsMatchIn("?" + (try { URI(url).query ?: "" } catch (_: Exception) { "" }))
    }

    /** URL que o yt-dlp / backend deve abrir para buscar no YouTube (canal ou site inteiro). */
    fun youtubeSearchTarget(siteUrl: String, query: String): String {
        val q = encode(query)
        val s = siteUrl.trim()
        Regex("""youtube\.com/(@[\w.-]+)""", RegexOption.IGNORE_CASE).find(s)?.let {
            return "https://www.youtube.com/${it.groupValues[1]}/search?query=$q"
        }
        Regex("""youtube\.com/channel/([\w-]+)""", RegexOption.IGNORE_CASE).find(s)?.let {
            return "https://www.youtube.com/channel/${it.groupValues[1]}/search?query=$q"
        }
        Regex("""youtube\.com/c/([\w.-]+)""", RegexOption.IGNORE_CASE).find(s)?.let {
            return "https://www.youtube.com/c/${it.groupValues[1]}/search?query=$q"
        }
        Regex("""youtube\.com/user/([\w.-]+)""", RegexOption.IGNORE_CASE).find(s)?.let {
            return "https://www.youtube.com/user/${it.groupValues[1]}/search?query=$q"
        }
        return "ytsearch20:$query"
    }

    /** URLs de busca no mesmo domínio, a partir do HTML (form, OpenSearch) e de padrões comuns. */
    fun discover(pageUrl: String, html: String, query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val q = encode(query)
        val rawQ = query.trim()
        val origin = try {
            val u = URI(pageUrl); "${u.scheme}://${u.authority}"
        } catch (_: Exception) { return emptyList() }
        val root = Extract.host(pageUrl)
        val out = LinkedHashSet<String>()

        fun keep(u: String?) {
            val a = Extract.abs(pageUrl, u) ?: return
            if (Extract.host(a) != root) return
            if (FOREIGN_SEARCH.containsMatchIn(a)) return
            out += a
        }

        val doc = Jsoup.parse(html, pageUrl)
        doc.select("form").forEach { form ->
            val action = form.attr("action").ifBlank { pageUrl }
            val method = form.attr("method").ifBlank { "get" }
            if (!method.equals("get", true)) return@forEach
            val inputs = form.select("input[name], textarea[name], select[name]")
            val searchInput = inputs.firstOrNull { e ->
                val n = e.attr("name"); val t = e.attr("type")
                t.equals("search", true) || n.matches(Regex("""^(q|s|query|search|search_query|k|keyword|termo|palavra)$""", RegexOption.IGNORE_CASE))
            } ?: return@forEach
            val params = ArrayList<String>()
            inputs.forEach { e ->
                val n = e.attr("name"); if (n.isBlank()) return@forEach
                val v = if (n == searchInput.attr("name")) rawQ else e.attr("value")
                if (e.attr("type").equals("hidden", true) || n == searchInput.attr("name")) {
                    params += encode(n).replace("%20", "+") + "=" + encode(v).replace("%20", "+")
                }
            }
            val base = Extract.abs(pageUrl, action) ?: return@forEach
            val join = if (base.contains("?")) "&" else "?"
            keep(base + join + params.joinToString("&"))
        }

        doc.select("link[rel=search][type=application/opensearchdescription+xml], link[rel=search][href]").forEach { link ->
            val href = link.attr("abs:href").ifBlank { Extract.abs(pageUrl, link.attr("href")) } ?: return@forEach
            // Se o href já for um template de busca raro, ignora (OSD é XML e é lido à parte).
            if (href.contains("{searchTerms}", true)) keep(href.replace("{searchTerms}", q, true).replace("{searchTerms?}", q, true))
        }

        // Padrões comuns, só se ainda não achamos um form
        if (out.isEmpty()) {
            listOf(
                "$origin/?s=$q",
                "$origin/search?q=$q",
                "$origin/search/$q",
                "$origin/search?query=$q",
                "$origin/busca?q=$q"
            ).forEach(::keep)
        }
        return out.toList()
    }

    /** Lê um OpenSearch Description XML e devolve a URL de busca (GET) com o termo. */
    fun fromOpenSearch(osdXml: String, query: String, pageUrl: String): String? {
        val doc = Jsoup.parse(osdXml, pageUrl)
        val url = doc.selectFirst("Url[type=text/html][template], url[type=text/html][template]")
            ?: doc.selectFirst("Url[template], url[template]")
            ?: return null
        val t = url.attr("template")
        if (t.isBlank()) return null
        val filled = t.replace("{searchTerms}", encode(query), true).replace("{searchTerms?}", encode(query), true)
            .replace(Regex("""\{[^}]+\}"""), "")
        val a = Extract.abs(pageUrl, filled) ?: return null
        if (FOREIGN_SEARCH.containsMatchIn(a)) return null
        if (Extract.host(a) != Extract.host(pageUrl)) return null
        return a
    }

    fun youtubeThumb(url: String): String? {
        val id = Regex("""(?:youtu\.be/|youtube\.com/(?:embed/|shorts/|live/|watch\?(?:.*&)?v=))([\w-]{11})""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
        return id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }
}
