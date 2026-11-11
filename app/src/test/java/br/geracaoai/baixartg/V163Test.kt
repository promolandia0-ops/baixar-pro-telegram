package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class V163Test {
    @Test fun discoverSearchFormAndPatterns() {
        val html = """
            <html><head><link rel="search" type="application/opensearchdescription+xml" href="/opensearch.xml"></head>
            <body>
              <form action="/busca" method="get">
                <input type="search" name="q">
                <button>ok</button>
              </form>
            </body></html>
        """.trimIndent()
        val urls = SiteSearch.discover("https://exemplo.com/home", html, "Aline Faria")
        assertTrue(urls.toString(), urls.any { it.contains("/busca") && it.contains("q=") && it.contains("Aline") })
        assertTrue(urls.all { it.startsWith("https://exemplo.com") })

        val noForm = SiteSearch.discover("https://exemplo.com/", "<html><body>sem form</body></html>", "bunny")
        assertTrue(noForm.any { it.contains("?s=") || it.contains("/search?q=") })
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

    @Test fun youtubeTargets() {
        assertTrue(SiteSearch.isYouTube("https://www.youtube.com/@NASA"))
        assertTrue(SiteSearch.isYouTube("https://youtu.be/jNQXAC9IVRw"))
        assertFalse(SiteSearch.isYouTube("https://test-videos.co.uk/"))
        val ch = SiteSearch.youtubeSearchTarget("https://www.youtube.com/@NASA/videos", "moon landing")
        assertTrue(ch, ch.startsWith("https://www.youtube.com/@NASA/search?query="))
        val all = SiteSearch.youtubeSearchTarget("https://www.youtube.com/", "big buck bunny")
        assertEquals("ytsearch20:big buck bunny", all)
        val thumb = SiteSearch.youtubeThumb("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        assertEquals("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", thumb)
    }

    @Test fun parseKeepsThumbnailAndLinkText() {
        val html = """
            <html><head>
              <meta property="og:image" content="/img/cover.jpg">
              <script type="application/ld+json">{"@type":"VideoObject","contentUrl":"https://cdn.example.com/v.mp4","thumbnailUrl":"https://cdn.example.com/v.jpg"}</script>
            </head><body>
              <a href="/watch/bunny-video">Big Buck Bunny</a>
              <video poster="/p.jpg" src="/media/bunny.mp4"></video>
            </body></html>
        """.trimIndent()
        val r = Extract.parse("https://exemplo.com/page", html)
        val mp4 = r.videos.first { it.url.endsWith("v.mp4") }
        assertEquals("https://cdn.example.com/v.jpg", mp4.thumb)
        val direct = r.videos.first { it.url.endsWith("bunny.mp4") }
        assertEquals("https://exemplo.com/p.jpg", direct.thumb)
        assertTrue(r.linkText.entries.any { it.key.contains("bunny-video") && it.value.contains("Big Buck Bunny") })
    }

    @Test fun nameSearchSkipsUnrelatedLinks() = runBlocking {
        val html = """
            <html><body>
              <a href="/videos/big-buck-bunny">Big Buck Bunny</a>
              <a href="/videos/unrelated-clip">Outra coisa</a>
              <a href="/search?q=bunny">buscar</a>
            </body></html>
        """.trimIndent()
        val r = Extract.parse("https://exemplo.com/", html)
        val words = NameFilter.words("bunny")
        val kept = r.links.filter { l -> SiteSearch.isSearchUrl(l) || SiteSearch.matches((r.linkText[l] ?: "") + " " + l, words) }
        assertTrue(kept.any { it.contains("big-buck-bunny") })
        assertTrue(kept.any { SiteSearch.isSearchUrl(it) })
        assertFalse(kept.any { it.contains("unrelated") })
    }

    @Test fun liveNameSearchFindsBunnyOnly() = runBlocking {
        val out = ArrayList<Found>(); var pages = 0
        val words = NameFilter.words("bunny")
        Crawler(CrawlOpts(2, 20, nameWords = words, nameQuery = "bunny")).run("https://test-videos.co.uk/", { pages = it.pagesDone }) { out += it }
        println("BUSCA NOME bunny: páginas=$pages vídeos=${out.size}")
        out.take(5).forEach { println("  ${it.title} ${it.url}") }
        assertTrue("não achou vídeo do bunny", out.any { it.url.contains("bunny", true) || NameFilter.matches(it, words) })
        assertTrue("varreu páginas demais ($pages)", pages <= 20)
        // sem o nome, o site inteiro (limite 12) já tinha 159 vídeos; com o nome deve ficar só nos relacionados
        assertTrue(out.all { NameFilter.matches(it, words) || it.url.contains("bunny", true) || it.page.contains("bunny", true) })
        Unit
    }
}
