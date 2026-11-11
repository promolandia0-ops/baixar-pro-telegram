package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class V162Test {
    private fun m(q: String, x: Found) = NameFilter.matches(x, NameFilter.words(q))

    @Test fun extractJsonLdOgIframeAndScript() {
        val html = """
            <html><head>
              <meta property="og:title" content="Passeio da Maria Souza">
              <meta property="og:video" content="https://cdn.example.com/files/passeio.mp4">
              <script type="application/ld+json">
                {"@type":"VideoObject","name":"Maria Souza no parque",
                 "contentUrl":"https://cdn.example.com/ld/parque.mp4",
                 "embedUrl":"https://www.youtube.com/embed/jNQXAC9IVRw"}
              </script>
            </head><body>
              <video><source src="/media/local.webm"></video>
              <a href="/clips/ola.m3u8" title="Cena da Maria">ver filme</a>
              <figure>
                <a href="https://files.example.com/x/video-hd.mp4"><img alt="João da Silva na praia"></a>
                <figcaption>João da Silva na praia</figcaption>
              </figure>
              <iframe src="https://www.youtube.com/embed/jNQXAC9IVRw"></iframe>
              <script>var player = { file: "https://cdn.example.com/player/setup.mp4", title: "ok" };</script>
            </body></html>
        """.trimIndent()
        val r = Extract.parse("https://example.com/galeria/maria-souza", html)
        val urls = r.videos.map { it.url }
        assertTrue(urls.any { it.endsWith("passeio.mp4") })
        assertTrue(urls.any { it.endsWith("parque.mp4") })
        assertTrue(urls.any { it.endsWith("local.webm") })
        assertTrue(urls.any { it.endsWith("ola.m3u8") && it.contains("example.com") })
        assertTrue(urls.any { it.endsWith("video-hd.mp4") })
        assertTrue(urls.any { it.endsWith("setup.mp4") })
        assertTrue(r.videos.any { it.kind == Kind.SERVER && it.url.contains("youtube.com/embed/jNQXAC9IVRw") })
        assertTrue(r.videos.any { it.kind == Kind.HLS })
        // iframe YouTube já virou Kind.SERVER; não precisa ir para frames
        assertTrue(r.frames.none { it.contains("youtube.com") })
    }

    @Test fun nameFilterUsesAltLinkAndNearby() {
        val html = """
            <html><body>
              <a href="https://cdn.example.com/a/clip1.mp4"><img alt="Maria Souza no parque"></a>
              <a href="https://cdn.example.com/a/clip2.mp4">outro vídeo</a>
              <figure>
                <video src="https://cdn.example.com/a/clip3.webm"></video>
                <figcaption>João da Silva na praia</figcaption>
              </figure>
            </body></html>
        """.trimIndent()
        val r = Extract.parse("https://example.com/album", html)
        val wordsMaria = NameFilter.words("maria souza")
        val wordsJoao = NameFilter.words("João da Silva")
        val hitMaria = r.videos.filter { NameFilter.matches(it, wordsMaria) }
        val hitJoao = r.videos.filter { NameFilter.matches(it, wordsJoao) }
        assertEquals(1, hitMaria.size)
        assertTrue(hitMaria.first().url.endsWith("clip1.mp4"))
        assertEquals(1, hitJoao.size)
        assertTrue(hitJoao.first().url.endsWith("clip3.webm"))
        assertTrue(m("MARIA SOUZA", hitMaria.first()))
        assertFalse(m("Maria Souza", r.videos.first { it.url.endsWith("clip2.mp4") }))
    }

    @Test fun nameFilterAccentAndPartial() {
        val f = Found("https://s.com/file/aline-faria-hd.mp4", "https://s.com/v/1", "Vídeo", Kind.DIRECT, extra = "Aline Fária no motel")
        assertTrue(m("aline faria", f))
        assertTrue(m("FÁRIA", f))
        assertFalse(m("aline souza", f))
    }

    @Test fun planRangesCoversWholeFile() {
        val p = Downloader.planRanges(1_000_000, 8)
        assertEquals(8, p.size)
        assertEquals(0L, p.first()[0])
        assertEquals(999_999L, p.last()[1])
        for (i in 1 until p.size) assertEquals(p[i-1][1] + 1, p[i][0])
        assertEquals(1_000_000L, p.sumOf { it[1] - it[0] + 1 })
        val small = Downloader.planRanges(100, 8)
        assertTrue(small.isNotEmpty())
        assertEquals(100L, small.sumOf { it[1] - it[0] + 1 })
    }

    @Test fun twitterUrlShapesAreYtDlp() {
        val ok = listOf(
            "https://x.com/NASA/status/1234567890",
            "https://twitter.com/i/status/1234567890",
            "https://x.com/i/web/status/1234567890",
            "https://mobile.twitter.com/foo/status/123?s=20",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        )
        for (u in ok) assertTrue(u, Extract.YTDLP_HOSTS.containsMatchIn(u))
        assertFalse(Extract.YTDLP_HOSTS.containsMatchIn("https://example.com/status/1"))
    }

    @Test fun megaStillNotConfusedWithOtherSites() {
        assertFalse(Mega.looksLikeMega("https://x.com/NASA/status/1"))
        assertFalse(Mega.isMegaLink("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertFalse(Mega.looksLikeMega("https://test-videos.co.uk/bigbuckbunny/mp4-h264"))
        assertTrue(Mega.looksLikeMega("https://mega.nz/file/abc#key"))
    }

    @Test fun liveRangeDownload() = runBlocking {
        val url = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_5MB.mp4"
        val dir = File("build/dltest").apply { mkdirs() }
        val parts = Downloader.planRanges(5_000_000, 8)
        assertEquals(8, parts.size)
        val f = Downloader.direct(url, "https://test-videos.co.uk/bigbuckbunny/mp4-h264", dir, "bbb5") { _, _ -> }
        println("RANGE/DIRECT: ${f.name} ${f.length()} bytes")
        assertTrue("arquivo pequeno demais: ${f.length()}", f.length() > 4_000_000)
        f.delete()
        Unit
    }

    @Test fun liveHtmlPageHasVideos() = runBlocking {
        val out = ArrayList<Found>()
        Crawler(CrawlOpts(0, 1)).run("https://test-videos.co.uk/bigbuckbunny/mp4-h264", {}) { out += it }
        println("LIVE PAGE: ${out.size} vídeos")
        out.take(3).forEach { println("  ${it.kind} ${it.url}") }
        assertTrue(out.any { it.url.contains(".mp4") })
    }
}
