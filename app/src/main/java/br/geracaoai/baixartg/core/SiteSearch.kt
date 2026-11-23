package br.geracaoai.baixartg.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/**
 * Detecta a busca nativa do site (form, OpenSearch, padrões de URL, WP REST, APIs JSON)
 * para achar vários vídeos de uma vez só, sem varrer o site inteiro.
 */
object SiteSearch {
    const val METHOD_SITE_SEARCH = "Busca do site"
    const val METHOD_FULL_CRAWL = "Varredura completa"

    private val SEARCH_PATH = Regex("""/(search|busca|buscar|find|query|results)(/|$|\?)""", RegexOption.IGNORE_CASE)
    private val SEARCH_QUERY = Regex("""(?:^|[?&])(q|s|query|search|search_query|k|keyword|termo|palavra|busca)=""", RegexOption.IGNORE_CASE)
    private val FOREIGN_SEARCH = Regex("""(?:google|bing|duckduckgo|yahoo|yandex|baidu|startpage)\.""", RegexOption.IGNORE_CASE)
    private val YT_HOST = Regex("""(?:^|\.)(?:youtube\.com|youtu\.be|youtube-nocookie\.com)$""", RegexOption.IGNORE_CASE)
    private val SEARCH_INPUT = Regex("""^(q|s|query|search|search_query|k|keyword|termo|palavra|busca)$""", RegexOption.IGNORE_CASE)
    private val SCRIPT_API = Regex(
        """(?:["'](https?://[^"']+)|["'](/[^"']+)|["']([A-Za-z][\w./-]*))[^"']*(?:/wp-json/[^"']*search[^"']*|/api/[^"']*search[^"']*|/(?:search|busca)(?:\?[^"']*)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val PAGINATION = Regex("""(?:[?&](?:page|paged|p|pagina|pg)=|/page/|/pagina/|/p/)(\d+)""", RegexOption.IGNORE_CASE)
    private val SKIP_RESULT = Regex("""/(wp-admin|wp-login|login|cart|checkout|account|minha-conta|wp-json)(/|$|\?)""", RegexOption.IGNORE_CASE)

    data class SearchRequest(
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val contentType: String? = null,
        val source: String = "form",
    )

    data class ResultPage(val results: List<String>, val pagination: List<String>)
    data class ResultCard(val url: String, val title: String, val thumb: String? = null)

    fun encode(q: String): String = URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")

    fun isYouTube(url: String): Boolean {
        val h = Extract.host(url)
        return YT_HOST.containsMatchIn(h) || h == "youtu.be"
    }

    fun matches(text: String, words: List<String>): Boolean = NameFilter.matchesText(text, words)

    fun isSearchUrl(url: String): Boolean {
        val pathAndQ = try {
            val u = URI(url)
            (u.path ?: "") + "?" + (u.query ?: "")
        } catch (_: Exception) { url }
        return SEARCH_PATH.containsMatchIn(pathAndQ) || SEARCH_QUERY.containsMatchIn("?" + (try { URI(url).query ?: "" } catch (_: Exception) { "" }))
    }

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

    fun youtubeThumb(url: String): String? {
        val id = Regex("""(?:youtu\.be/|youtube\.com/(?:embed/|shorts/|live/|watch\?(?:.*&)?v=))([\w-]{11})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
        return id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }

    fun originOf(pageUrl: String): String? = try {
        val u = URI(pageUrl)
        if (u.scheme == null || u.authority == null) null else "${u.scheme}://${u.authority}"
    } catch (_: Exception) { null }

    fun openSearchHref(pageUrl: String, html: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val link = doc.selectFirst("link[rel=search][type=application/opensearchdescription+xml], link[rel~=search][type~=opensearch]")
            ?: doc.selectFirst("link[rel=search][href]")
        val href = link?.attr("abs:href")?.ifBlank { Extract.abs(pageUrl, link.attr("href")) }
        return href?.takeIf { !it.contains("{searchTerms}", true) }
    }

    /** Só URLs GET (formas, OpenSearch no href, padrões). Compatível com testes de detecção. */
    fun discover(pageUrl: String, html: String, query: String): List<String> =
        discoverRequests(pageUrl, html, query, includeGuesses = true)
            .filter { it.method.equals("GET", true) }
            .map { it.url }
            .distinct()

    fun discoverRequests(pageUrl: String, html: String, query: String, includeGuesses: Boolean = true): List<SearchRequest> {
        if (query.isBlank()) return emptyList()
        val origin = originOf(pageUrl) ?: return emptyList()
        val root = Extract.host(pageUrl)
        val out = ArrayList<SearchRequest>()
        val seen = HashSet<String>()
        fun keep(req: SearchRequest) {
            val a = Extract.abs(pageUrl, req.url) ?: return
            if (Extract.host(a) != root) return
            if (FOREIGN_SEARCH.containsMatchIn(a)) return
            val key = req.method.uppercase() + " " + a + " " + (req.body ?: "")
            if (!seen.add(key)) return
            out += req.copy(url = a)
        }

        val doc = Jsoup.parse(html, pageUrl)
        doc.select("form").forEach { form ->
            val action = form.attr("action").ifBlank { pageUrl }
            val method = form.attr("method").ifBlank { "get" }
            val inputs = form.select("input[name], textarea[name], select[name]")
            val searchInput = inputs.firstOrNull { e ->
                val n = e.attr("name"); val t = e.attr("type")
                t.equals("search", true) || SEARCH_INPUT.matches(n)
            } ?: return@forEach
            val params = ArrayList<Pair<String, String>>()
            inputs.forEach { e ->
                val n = e.attr("name"); if (n.isBlank()) return@forEach
                val v = if (n == searchInput.attr("name")) query.trim() else e.attr("value")
                if (e.attr("type").equals("hidden", true) || n == searchInput.attr("name") || !e.attr("type").equals("submit", true)) {
                    if (n == searchInput.attr("name") || e.attr("type").equals("hidden", true)) params += n to v
                }
            }
            val encoded = params.joinToString("&") { encode(it.first).replace("%20", "+") + "=" + encode(it.second).replace("%20", "+") }
            val base = Extract.abs(pageUrl, action) ?: return@forEach
            if (method.equals("post", true)) {
                keep(SearchRequest(base, "POST", encoded, "application/x-www-form-urlencoded", "form-post"))
            } else {
                val join = if (base.contains("?")) "&" else "?"
                keep(SearchRequest(base + join + encoded, source = "form"))
            }
        }

        doc.select("link[rel=search][href], link[rel~=search][href]").forEach { link ->
            val href = link.attr("abs:href").ifBlank { Extract.abs(pageUrl, link.attr("href")) } ?: return@forEach
            if (href.contains("{searchTerms}", true)) {
                keep(SearchRequest(href.replace("{searchTerms}", encode(query), true).replace("{searchTerms?}", encode(query), true), source = "opensearch-link"))
            }
        }

        jsonApisFromScripts(pageUrl, html, query).forEach(::keep)

        val hasForm = out.any { it.source.startsWith("form") }
        if (includeGuesses && !hasForm) {
            patternGuesses(pageUrl, query).forEach(::keep)
            wpEndpoints(origin, query).forEach(::keep)
        } else if (includeGuesses) {
            wpEndpoints(origin, query).forEach(::keep)
        }
        return out
    }

    fun patternGuesses(pageUrl: String, query: String): List<SearchRequest> {
        val origin = originOf(pageUrl) ?: return emptyList()
        val q = encode(query)
        return listOf(
            "$origin/?s=$q",
            "$origin/?q=$q",
            "$origin/search?q=$q",
            "$origin/busca?q=$q",
            "$origin/?search=$q",
            "$origin/search/$q",
        ).map { SearchRequest(it, source = "pattern") }
    }

    fun wpEndpoints(origin: String, query: String): List<SearchRequest> {
        val q = encode(query)
        return listOf(
            SearchRequest("$origin/wp-json/wp/v2/search?search=$q&per_page=20", source = "wp-search"),
            SearchRequest("$origin/wp-json/wp/v2/media?search=$q&per_page=20", source = "wp-media"),
        )
    }

    fun jsonApisFromScripts(pageUrl: String, html: String, query: String): List<SearchRequest> {
        val q = encode(query)
        val out = LinkedHashSet<String>()
        val scripts = Jsoup.parse(html, pageUrl).select("script").eachText() + Jsoup.parse(html, pageUrl).select("script").map { it.html() } + Jsoup.parse(html, pageUrl).select("script[src]").map { it.attr("abs:src") }
        val blob = (scripts + html).joinToString("\n")
        Regex("""["'](https?://[^"']*(?:/wp-json/[^"']*search[^"']*|/api/[^"']*search[^"'?]*)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(blob).forEach { out += it.groupValues[1] }
        Regex("""["'](/[^"']*(?:wp-json/[^"']*search[^"']*|/api/[^"']*search)[^"']*)["']""", RegexOption.IGNORE_CASE).findAll(blob).forEach {
            Extract.abs(pageUrl, it.groupValues[1])?.let { u -> out += u }
        }
        return out.map { raw ->
            val u = if (raw.contains("{searchTerms}", true)) raw.replace("{searchTerms}", q, true).replace("{searchTerms?}", q, true)
            else if (raw.contains("search=") || raw.contains("q=")) raw
            else raw + (if (raw.contains("?")) "&" else "?") + "search=$q&q=$q"
            SearchRequest(u, source = "json-api")
        }
    }

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

    fun resultLinks(pageUrl: String, html: String): ResultPage {
        val doc = Jsoup.parse(html, pageUrl)
        val root = Extract.host(pageUrl)
        val results = LinkedHashSet<String>()
        val pagination = LinkedHashSet<String>()
        fun keep(u: String?, into: LinkedHashSet<String>) {
            val a = Extract.abs(pageUrl, u) ?: return
            if (Extract.host(a) != root) return
            if (Extract.skipPage(a)) return
            if (a.substringBefore('#') == pageUrl.substringBefore('#')) return
            if (SKIP_RESULT.containsMatchIn(try { URI(a).path ?: a } catch (_: Exception) { a })) return
            into += a
        }
        doc.select("link[rel=next], a[rel=next]").forEach { keep(it.attr("href"), pagination) }
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            val txt = a.text().lowercase().trim()
            val path = href.lowercase()
            if (txt.matches(Regex("""^(próxima|proxima|próximo|proximo|next|mais|mais resultados|»|›|>>)$""")) ||
                PAGINATION.containsMatchIn(href) || path.contains("/page/") || path.contains("/pagina/")) {
                keep(href, pagination)
            }
        }
        val scoped = doc.select("main a[href], article a[href], [role=main] a[href], .search-results a[href], .search-result a[href], ol a[href], ul a[href], h1 a[href], h2 a[href], h3 a[href], .post a[href], .entry a[href], .result a[href]")
        val pool = if (scoped.isNotEmpty()) scoped else doc.select("a[href]").filter { a ->
            a.parents().none { p -> p.tagName() in setOf("nav", "header", "footer") }
        }
        pool.forEach { a ->
            val href = a.attr("href")
            if (isSearchUrl(Extract.abs(pageUrl, href) ?: href) && PAGINATION.containsMatchIn(href)) return@forEach
            if (isSearchUrl(Extract.abs(pageUrl, href) ?: href) && !PAGINATION.containsMatchIn(href) && a.text().isBlank()) return@forEach
            keep(href, results)
        }
        pagination.forEach { results.remove(it) }
        return ResultPage(results.toList(), pagination.toList())
    }

    fun parseSearchJson(json: String, base: String): List<String> {
        val out = LinkedHashSet<String>()
        fun take(s: String?) { if (!s.isNullOrBlank()) Extract.abs(base, s)?.let { out += it } }
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    for (k in listOf("url", "link", "source_url", "guid", "permalink", "href", "contentUrl", "embeddedUrl")) {
                        if (!v.has(k)) continue
                        when (val x = v.opt(k)) {
                            is String -> take(x)
                            is JSONObject -> take(x.optString("rendered").ifBlank { x.optString("raw") }.ifBlank { x.optString("url") })
                        }
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        try { walk(JSONTokener(json).nextValue()) } catch (_: Exception) {}
        return out.filter { !Extract.skipPage(it) || Extract.isVideoUrl(it) || Extract.isPhotoUrl(it) }.distinct()
    }

    fun looksLikeJson(body: String, contentType: String): Boolean {
        val ct = contentType.lowercase()
        if (ct.contains("json")) return true
        val t = body.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    fun looksLikeSearchResults(html: String, query: String, requestUrl: String, startHtml: String?): Boolean {
        if (looksLikeJson(html, "")) return parseSearchJson(html, requestUrl).isNotEmpty()
        if (isSearchUrl(requestUrl)) {
            val rp = resultLinks(requestUrl, html)
            if (rp.results.isNotEmpty() || rp.pagination.isNotEmpty()) return true
        }
        val q = NameFilter.norm(query)
        if (q.isNotEmpty() && NameFilter.norm(html).contains(q)) return true
        if (html.contains("search-result", true) || html.contains("search-results", true) ||
            html.contains("resultados", true) || html.contains("encontrad", true)) return true
        if (startHtml != null && startHtml == html) return false
        return false
    }


    fun nextSearchUrl(url: String): String? {
        val pageRe = Regex("""([?&](?:page|paged|p|pagina|pg)=)(\d+)""", RegexOption.IGNORE_CASE)
        pageRe.find(url)?.let {
            val n = it.groupValues[2].toIntOrNull() ?: return@let
            return pageRe.replace(url, it.groupValues[1] + (n + 1).toString())
        }
        val offRe = Regex("""([?&](?:offset|start)=)(\d+)""", RegexOption.IGNORE_CASE)
        offRe.find(url)?.let {
            val n = it.groupValues[2].toIntOrNull() ?: return@let
            val step = if (n == 0) 10 else n
            return offRe.replace(url, it.groupValues[1] + (n + step).toString())
        }
        val pathRe = Regex("""(/page/|/pagina/|/p/)(\d+)(/|$)""", RegexOption.IGNORE_CASE)
        pathRe.find(url)?.let {
            val n = it.groupValues[2].toIntOrNull() ?: return@let
            return pathRe.replace(url, it.groupValues[1] + (n + 1) + it.groupValues[3])
        }
        val join = if (url.contains("?")) "&" else "?"
        return url.substringBefore('#') + join + "page=2"
    }

    fun loadMoreUrls(pageUrl: String, html: String): List<String> {
        val root = Extract.host(pageUrl)
        val out = LinkedHashSet<String>()
        fun keep(raw: String?) {
            val a = Extract.abs(pageUrl, raw) ?: return
            if (Extract.host(a) != root) return
            if (Extract.skipPage(a)) return
            out += a
        }
        val doc = org.jsoup.Jsoup.parse(html, pageUrl)
        doc.select("a[rel=next], link[rel=next], a.load-more, .load-more, .loadmore, [class*=load-more], [class*=loadmore], [class*=infinite], [data-load-more], [data-next], [data-next-page], [data-infinite-url], [data-page-url], button[data-url], a[data-url]").forEach { e ->
            val u = e.attr("href").ifBlank { e.attr("data-url") }.ifBlank { e.attr("data-href") }
                .ifBlank { e.attr("data-next") }.ifBlank { e.attr("data-next-page") }
                .ifBlank { e.attr("data-infinite-url") }.ifBlank { e.attr("data-page-url") }
                .ifBlank { e.attr("data-load-more") }
            keep(u)
        }
        Regex("\"(?:next|next_page|nextPage|next_url|nextUrl|load_more|loadMore)\"\\s*:\\s*\"(https?://[^\"]+|/?[^\"]+)\"").findAll(html).forEach { keep(it.groupValues[1]) }
        return out.toList()
    }

    fun resultCards(pageUrl: String, html: String): List<ResultCard> {
        val doc = org.jsoup.Jsoup.parse(html, pageUrl)
        val root = Extract.host(pageUrl)
        val out = LinkedHashMap<String, ResultCard>()
        fun consider(el: org.jsoup.nodes.Element) {
            val links = el.select("a[href]").mapNotNull { Extract.abs(pageUrl, it.attr("href")) }
                .filter { Extract.host(it) == root && !SKIP_RESULT.containsMatchIn(try { java.net.URI(it).path ?: it } catch (_: Exception) { it }) }
            val page = links.firstOrNull { !Extract.skipPage(it) && !isSearchUrl(it) }
                ?: links.firstOrNull { Extract.isVideoUrl(it) }
                ?: return
            if (Extract.isPhotoUrl(page) && !Extract.isVideoUrl(page)) return
            val title = el.select("h1,h2,h3,h4,.title,.name,.entry-title,.titre,span.title,p.title,a[href],span,[itemprop=name],img[alt]").map {
                it.text().trim().ifBlank { it.attr("alt").trim() }.ifBlank { it.attr("title").trim() }
            }.firstOrNull { it.length >= 2 } ?: el.text().replace(Regex("\\s+"), " ").trim().take(150)
            val img = el.select("img[src], img[data-src], img[data-lazy-src]").firstOrNull()
            val thumb = img?.let {
                Extract.abs(pageUrl, it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-lazy-src") })
            }
            val prev = out[page]
            if (prev == null || (title.length > prev.title.length)) out[page] = ResultCard(page, title.take(150), thumb ?: prev?.thumb)
        }
        val explicit = doc.select(
            "article, li, figure, .post, .entry, .card, .item, .result, .video-item, .video, .thumb, .thumbs, .thumb-block, .tmb, .vid-item, .movie-item, .file-item, .grid-item, .list-item, " +
            "[class*=search-result], [class*=video-item], [class*=post-item], [class*=card], [class*=result-item], [class*=thumb], [class*=vid-item], [data-video-id], [data-id], #list_videos .item, .list-videos .item"
        )
        explicit.forEach { consider(it) }
        doc.select("a[href]:has(img)").forEach { consider(it) }
        val parents = LinkedHashSet<org.jsoup.nodes.Element>()
        doc.select("a[href]").forEach { a -> a.parent()?.let { parents += it } }
        for (parent in parents) {
            val grand = parent.parent() ?: continue
            val sibs = grand.children().filter { kid ->
                kid.select("a[href]").isNotEmpty() && (kid.select("img").isNotEmpty() || kid.select("a[href]").size >= 1)
            }
            if (sibs.size >= 2) sibs.forEach { consider(it) }
        }
        resultLinks(pageUrl, html).results.forEach { u ->
            if (u !in out) {
                val a = doc.select("a[href]").firstOrNull { Extract.abs(pageUrl, it.attr("href")) == u }
                val title = a?.text()?.trim().orEmpty().ifBlank { a?.attr("title") ?: "" }
                out[u] = ResultCard(u, title.take(150), null)
            }
        }
        return out.values.toList()
    }

    val TAXONOMY_PATH = Regex("""/(tag|tags|category|categoria|categorias|author|autor|channel|channels|canal|c|a|user|users|usuario|profile|perfil|artist|artista|playlist|series|serie|show|actor|ator|atriz|people|pessoa|elenco|cast)/[^/?#]+""", RegexOption.IGNORE_CASE)
    private val LISTING_PATH = Regex(
        """/(videos|category|categories|categorias?|tag|tags|user|users|usuario|channel|channels|canal|search|busca|profile|perfis?|updates|recent|latest|top|popular|page|pagina|archive|author|elenco|galeria|gallery|playlist|playlists|collection|series|episodes|episodios|filmes|movies|clips|shorts|index|members?)(/|$|\?)""",
        RegexOption.IGNORE_CASE,
    )
    private val LISTING_QUERY = Regex("""[?&](s|q|query|search|busca|k|keyword|author|tag|cat|categoria|user|p)=""", RegexOption.IGNORE_CASE)
    private val LISTING_HTML = Regex("""(?i)(?:list[_-]videos|video-list|videos-list|thumbs-list|grid-videos|video-grid|catalogo|catálogo|listagem)""")
    private val DURATION_BADGE = Regex("""(?:\d{1,2}:)?\d{1,2}:\d{2}""")
    private val PLAY_ICON = Regex("""(?:class|aria-label)\s*=\s*["'][^"']*(?:play-button|play-icon|btn-play|video-play|icon-play|fa-play|playback)""", RegexOption.IGNORE_CASE)

    fun looksLikeListingUrl(u: String): Boolean {
        val path = try { java.net.URI(u).path ?: u } catch (_: Exception) { u }
        if (LISTING_PATH.containsMatchIn(path)) return true
        return u.contains('?') && LISTING_QUERY.containsMatchIn(u)
    }

    fun looksLikeListingHtml(html: String): Boolean {
        if (LISTING_HTML.containsMatchIn(html)) return true
        if (DURATION_BADGE.findAll(html).count() >= 4) return true
        if (PLAY_ICON.findAll(html).count() >= 3) return true
        return false
    }

    /** Grade/listagem (vários cards) vs. página de um vídeo com relacionados. */
    fun isListingPage(pageUrl: String, html: String, cards: List<ResultCard>, videoCount: Int): Boolean {
        if (cards.isEmpty()) return false
        // Página que já tem os arquivos/streams não precisa abrir cards (ex.: página com vários mp4).
        if (videoCount >= 2) return false
        val listingUrl = looksLikeListingUrl(pageUrl)
        val listingHtml = looksLikeListingHtml(html)
        // Um vídeo no player + alguns relacionados = página de vídeo, não listagem.
        if (videoCount == 1 && cards.size <= 12 && !listingUrl && !listingHtml) return false
        if (cards.size >= 3) return true
        if (cards.size >= 2 && videoCount <= 0) return true
        if ((listingUrl || listingHtml) && cards.isNotEmpty() && videoCount <= 1) return true
        return false
    }
}
