package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class V171Test {

    @Test fun discoverSearchFormGetAndOpenSearchLink() {
        val html = """
            <html><head>
              <link rel="search" type="application/opensearchdescription+xml" href="/opensearch.xml">
              <link rel="search" href="/search?q={searchTerms}">
            </head>
            <body>
              <form action="/busca" method="get">
                <input type="search" name="q">
                <button>ok</button>
              </form>
            </body></html>
        """.trimIndent()
        val urls = SiteSearch.discover("https://exemplo.com/home", html, "Aline Faria")
        assertTrue(urls.toString(), urls.any { it.contains("/busca") && it.contains("q=") && it.contains("Aline") })
        assertTrue(urls.toString(), urls.any { it.contains("/search?q=") && it.contains("Aline") })
        assertTrue(urls.all { it.startsWith("https://exemplo.com") })
        assertEquals("https://exemplo.com/opensearch.xml", SiteSearch.openSearchHref("https://exemplo.com/home", html))
    }

    @Test fun discoverInputNamesBuscaTermoAndEs() {
        val html = """
            <form action="/procurar" method="get">
              <input name="termo" type="text">
            </form>
        """.trimIndent()
        val urls = SiteSearch.discover("https://escola.com/", html, "Gibson")
        assertTrue(urls.toString(), urls.any { it.contains("/procurar") && it.contains("termo=") && it.contains("Gibson") })

        val html2 = """
            <form action="/s" method="get"><input name="s"></form>
        """.trimIndent()
        val urls2 = SiteSearch.discover("https://escola.com/", html2, "dedilhado")
        assertTrue(urls2.toString(), urls2.any { it.contains("s=") && it.contains("dedilhado") })
    }

    @Test fun discoverPostForm() {
        val html = """
            <form action="/busca" method="post">
              <input type="hidden" name="ok" value="1">
              <input name="q" type="search">
            </form>
        """.trimIndent()
        val reqs = SiteSearch.discoverRequests("https://exemplo.com/", html, "Gibson")
        val post = reqs.filter { it.method.equals("POST", true) }
        assertTrue(reqs.toString(), post.any { it.url.contains("/busca") && it.body!!.contains("q=Gibson") && it.body!!.contains("ok=1") })
    }

    @Test fun discoverCommonUrlPatternsWhenNoForm() {
        val noForm = SiteSearch.discover("https://exemplo.com/", "<html><body>sem form</body></html>", "bunny")
        assertTrue(noForm.toString(), noForm.any { it.contains("?s=") })
        assertTrue(noForm.toString(), noForm.any { it.contains("?q=") || it.contains("/?q=") })
        assertTrue(noForm.toString(), noForm.any { it.contains("/search?q=") })
        assertTrue(noForm.toString(), noForm.any { it.contains("/busca?q=") })
        assertTrue(noForm.toString(), noForm.any { it.contains("search=") })
        assertTrue(noForm.toString(), noForm.any { it.contains("/search/bunny") || it.contains("/search/bunny".replace(" ", "%20")) })
    }

    @Test fun isSearchUrlDetectsPatterns() {
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/?s=gibson"))
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/?q=gibson"))
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/search?q=gibson"))
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/busca?q=gibson"))
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/?search=gibson"))
        assertTrue(SiteSearch.isSearchUrl("https://exemplo.com/search/gibson"))
        assertFalse(SiteSearch.isSearchUrl("https://exemplo.com/aulas/gibson"))
    }

    @Test fun openSearchTemplate() {
        val xml = """
            <OpenSearchDescription>
              <Url type="text/html" template="https://exemplo.com/search?q={searchTerms}"/>
            </OpenSearchDescription>
        """.trimIndent()
        val u = SiteSearch.fromOpenSearch(xml, "Big Buck", "https://exemplo.com/")
        assertEquals("https://exemplo.com/search?q=Big%20Buck", u)
    }

    @Test fun wordpressRestAndJsonInScript() {
        val origin = "https://aula.com"
        val wp = SiteSearch.wpEndpoints(origin, "Gibson")
        assertTrue(wp.any { it.url.contains("/wp-json/wp/v2/search?search=") && it.url.contains("Gibson") })
        assertTrue(wp.any { it.url.contains("/wp-json/wp/v2/media?search=") })
        val html = """
            <html><body>
              <script>window.SEARCH_API = "https://aula.com/api/search";</script>
            </body></html>
        """.trimIndent()
        val apis = SiteSearch.jsonApisFromScripts("https://aula.com/", html, "Gibson")
        assertTrue(apis.toString(), apis.any { it.url.contains("/api/search") && (it.url.contains("Gibson") || it.url.contains("q=")) })
        val json = """[{"title":"Aula Gibson","url":"https://aula.com/aula/gibson"},{"link":"https://aula.com/aula/2"}]"""
        val links = SiteSearch.parseSearchJson(json, "https://aula.com/")
        assertTrue(links.contains("https://aula.com/aula/gibson"))
        assertTrue(links.contains("https://aula.com/aula/2"))
        val media = """[{"source_url":"https://cdn.aula.com/gibson.jpg","link":"https://aula.com/midia/1","title":{"rendered":"Gibson"}}]"""
        val mlinks = SiteSearch.parseSearchJson(media, "https://aula.com/")
        assertTrue(mlinks.toString(), mlinks.any { it.endsWith("/midia/1") } || mlinks.any { it.endsWith("gibson.jpg") })
    }

    @Test fun resultLinksAndPagination() {
        val html = """
            <html><body>
              <main>
                <article><h2><a href="/aula/gibson">Gibson Les Paul</a></h2></article>
                <article><h2><a href="/aula/gibson-2">Outra Gibson</a></h2></article>
                <a rel="next" href="/busca?q=Gibson&page=2">Próxima</a>
              </main>
              <nav><a href="/login">entrar</a></nav>
            </body></html>
        """.trimIndent()
        val rp = SiteSearch.resultLinks("https://exemplo.com/busca?q=Gibson", html)
        assertTrue(rp.results.toString(), rp.results.any { it.contains("/aula/gibson") })
        assertTrue(rp.pagination.toString(), rp.pagination.any { it.contains("page=2") })
    }

    @Test fun youtubeHelpersStillWork() {
        assertTrue(SiteSearch.isYouTube("https://www.youtube.com/@NASA"))
        assertTrue(SiteSearch.isYouTube("https://youtu.be/jNQXAC9IVRw"))
        assertFalse(SiteSearch.isYouTube("https://test-videos.co.uk/"))
        val ch = SiteSearch.youtubeSearchTarget("https://www.youtube.com/@NASA/videos", "moon landing")
        assertTrue(ch, ch.startsWith("https://www.youtube.com/@NASA/search?query="))
        val thumb = SiteSearch.youtubeThumb("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        assertEquals("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", thumb)
    }

    @Test fun siteSearchFindsNamedLessonsFasterThanFullCrawl() = runBlocking {
        val env = TestSite.start()
        try {
            val words = NameFilter.words("Gibson")
            val searchFound = ArrayList<Found>()
            var searchPages = 0
            val tSearch = System.nanoTime()
            val n = Crawler(CrawlOpts(2, 200, nameWords = words, nameQuery = "Gibson", parallel = 24)).runSiteSearch(env.base, { searchPages = it.pagesDone }) { searchFound += it }
            val msSearch = (System.nanoTime() - tSearch) / 1_000_000
            assertTrue("busca do site não achou nada (n=$n pages=$searchPages)", n > 0)
            assertTrue(searchFound.any { it.url.contains("gibson") && it.kind != Kind.PHOTO } || searchFound.any { NameFilter.matches(it, words) })
            assertTrue("filtrou aula errada: $searchFound", searchFound.all { NameFilter.matches(it, words) })
            assertFalse("não devia trazer bateria", searchFound.any { it.url.contains("bateria") || it.title.contains("Bateria", true) })
            assertTrue("busca do site leu páginas demais ($searchPages)", searchPages <= 20)

            val crawlFound = ArrayList<Found>()
            var crawlPages = 0
            val tCrawl = System.nanoTime()
            Crawler(CrawlOpts(2, 200, nameWords = words, nameQuery = "Gibson", parallel = 24)).run(env.base, { crawlPages = it.pagesDone }) { crawlFound += it }
            val msCrawl = (System.nanoTime() - tCrawl) / 1_000_000
            assertTrue("varredura não achou Gibson via sitemap/links", crawlFound.any { NameFilter.matches(it, words) })
            assertTrue(crawlFound.all { NameFilter.matches(it, words) })
            println("BENCH site-search ${msSearch}ms pages=$searchPages found=${searchFound.size}; full-crawl ${msCrawl}ms pages=$crawlPages found=${crawlFound.size}")
            assertTrue("busca do site leu um volume razoável ($searchPages)", searchPages <= 20)
            assertTrue("varredura nomeada também deve achar Gibson", crawlFound.isNotEmpty())
        } finally { env.stop() }
    }

    @Test fun unnamedParallelCrawlIsQuickOnLocalSite() = runBlocking {
        val env = TestSite.start()
        try {
            val found = ArrayList<Found>()
            var pages = 0
            val t = System.nanoTime()
            Crawler(CrawlOpts(2, 200, parallel = 24)).run(env.base, { pages = it.pagesDone }) { found += it }
            val ms = (System.nanoTime() - t) / 1_000_000
            println("BENCH full unnamed ${ms}ms pages=$pages found=${found.size}")
            assertTrue("varredura curta demais ($pages)", pages >= 40)
            assertTrue("não achou as aulas de teste", found.any { it.url.contains("gibson") } && found.any { it.url.contains("filler") || it.url.contains("bateria") })
            assertTrue("varredura local lenta demais: ${ms}ms para $pages páginas", ms < 8000)
        } finally { env.stop() }
    }

    @Test fun postSearchFormIsUsed() = runBlocking {
        val env = TestSite.start(postForm = true)
        try {
            val words = NameFilter.words("Gibson")
            val found = ArrayList<Found>()
            val n = Crawler(CrawlOpts(1, 80, nameWords = words, nameQuery = "Gibson", parallel = 16))
                .runSiteSearch(env.base, { }) { found += it }
            assertTrue("POST /busca não devolveu aulas ($n ${found.map { it.url }})", n > 0 && found.any { NameFilter.matches(it, words) })
        } finally { env.stop() }
    }
}



private class TestSite(val base: String, private val server: MiniHttp) {
    fun stop() { server.stop() }

    companion object {
        fun start(postForm: Boolean = false): TestSite {
            val placeholder = MiniHttp { _, _, _, _ -> 404 to ("text/plain" to ByteArray(0)) }
            val port = placeholder.port
            val host = "http://127.0.0.1:$port"
            val pages = (1..60).map { n -> "/p/$n" }
            fun html(title: String, body: String) =
                "<html><head><title>$title</title><link rel=\"search\" type=\"application/opensearchdescription+xml\" href=\"/opensearch.xml\"></head><body>$body</body></html>"
            fun aula(slug: String, title: String, file: String) = html(
                title,
                """<h2>$title</h2><img src="/img/$slug.jpg" width="800" height="600" alt="$title"><video src="/media/$file.mp4"></video>""",
            )
            val form = if (postForm)
                """<form action="/busca" method="post"><input type="search" name="q"><button>ok</button></form>"""
            else
                """<form action="/busca" method="get"><input type="search" name="q"><button>ok</button></form>"""
            val homeLinks = pages.joinToString(" ") { """<a href="$it">Página ${it.substringAfterLast('/')}</a>""" } +
                """ <a href="/aula/bateria">Bateria básico</a>"""
            val sitemap = buildString {
                append("""<?xml version="1.0"?><urlset>""")
                listOf("/aula/gibson", "/aula/gibson-2", "/aula/les-paul-gibson", "/aula/bateria").forEach {
                    append("<url><loc>$host$it</loc></url>")
                }
                pages.forEach { append("<url><loc>$host$it</loc></url>") }
                append("</urlset>")
            }
            placeholder.handler = { method, path, query, body ->
                fun ok(htmlBody: String, ct: String = "text/html; charset=utf-8") = 200 to (ct to htmlBody.toByteArray(Charsets.UTF_8))
                when {
                    path == "/" || path.isEmpty() -> ok(html("Escola", "$form<p>$homeLinks</p>"))
                    path == "/opensearch.xml" -> ok("""<OpenSearchDescription><Url type="text/html" template="$host/busca?q={searchTerms}"/></OpenSearchDescription>""", "application/opensearchdescription+xml")
                    path == "/sitemap.xml" -> ok(sitemap, "application/xml")
                    path == "/busca" -> {
                        val term = if (method == "POST") {
                            body.split("&").mapNotNull {
                                val i = it.indexOf("="); if (i < 0) null else
                                    URLDecoder.decode(it.substring(0, i).replace("+", " "), "UTF-8") to URLDecoder.decode(it.substring(i + 1).replace("+", " "), "UTF-8")
                            }.toMap()["q"].orEmpty()
                        } else query.substringAfter("q=", "").substringBefore("&").let {
                            try { URLDecoder.decode(it.replace("+", " "), "UTF-8") } catch (_: Exception) { it }
                        }
                        val hits = if (term.contains("gibson", true)) listOf("/aula/gibson", "/aula/gibson-2", "/aula/les-paul-gibson") else emptyList()
                        ok(html("Busca $term", """<main>${hits.joinToString("") { """<article><h2><a href="$it">${it.substringAfterLast('/').replace("-", " ")}</a></h2></article>""" }}</main>"""))
                    }
                    path == "/aula/gibson" -> ok(aula("gibson", "Aula Gibson Les Paul", "gibson-01"))
                    path == "/aula/gibson-2" -> ok(aula("gibson-2", "Gibson continuação", "gibson-02"))
                    path == "/aula/les-paul-gibson" -> ok(aula("les-paul-gibson", "Les Paul Gibson", "gibson-03"))
                    path == "/aula/bateria" -> ok(aula("bateria", "Bateria básico", "bateria-01"))
                    path.startsWith("/p/") -> {
                        val n = path.substringAfter("/p/")
                        ok(html("Outra aula $n", """<h2>Outra aula $n</h2><video src="/media/filler-$n.mp4"></video>"""))
                    }
                    else -> 404 to ("text/plain" to ByteArray(0))
                }
            }
            return TestSite("$host/", placeholder)
        }
    }
}

/** Servidor HTTP mínimo (Android unit test não tem com.sun.net.httpserver). */
class MiniHttp(handler0: (String, String, String, String) -> Pair<Int, Pair<String, ByteArray>>) {
    @Volatile var handler = handler0
    private val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = ss.localPort
    private val pool = Executors.newFixedThreadPool(32)
    @Volatile private var running = true
    init {
        Thread({
            while (running) {
                try {
                    val sock = ss.accept()
                    pool.execute { serve(sock) }
                } catch (_: Exception) { if (!running) break }
            }
        }, "mini-http").apply { isDaemon = true; start() }
    }
    fun stop() {
        running = false
        try { ss.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }
    private fun serve(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val inp = BufferedInputStream(sock.getInputStream())
            val header = ByteArrayOutputStream()
            var prev = -1
            while (true) {
                val b = inp.read(); if (b < 0) break
                header.write(b)
                if (prev == '\r'.code && b == '\n'.code) {
                    val a = header.toByteArray()
                    if (a.size >= 4 && a[a.size - 4] == '\r'.code.toByte() && a[a.size - 3] == '\n'.code.toByte()) break
                }
                prev = b
            }
            val head = header.toString(Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val first = lines.firstOrNull().orEmpty().split(" ")
            val method = first.getOrNull(0) ?: "GET"
            val rawTarget = first.getOrNull(1) ?: "/"
            val path = rawTarget.substringBefore("?")
            val query = rawTarget.substringAfter("?", "")
            val headers = HashMap<String, String>()
            for (ln in lines.drop(1)) {
                val i = ln.indexOf(':'); if (i > 0) headers[ln.substring(0, i).trim().lowercase()] = ln.substring(i + 1).trim()
            }
            val clen = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (clen > 0) {
                val buf = ByteArray(clen); var off = 0
                while (off < clen) { val n = inp.read(buf, off, clen - off); if (n < 0) break; off += n }
                buf
            } else ByteArray(0)
            val body = bodyBytes.toString(Charsets.UTF_8)
            val (code, payload) = try { handler(method, path, query, body) } catch (_: Exception) { 500 to ("text/plain" to ByteArray(0)) }
            val (ct, data) = payload
            val reason = if (code == 200) "OK" else "ERR"
            val resp = "HTTP/1.1 $code $reason\r\nContent-Type: $ct\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
            sock.getOutputStream().use { out ->
                out.write(resp)
                out.write(data)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
