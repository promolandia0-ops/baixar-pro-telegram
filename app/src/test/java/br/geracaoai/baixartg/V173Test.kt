package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class V173Test {
    private fun parse(html: String, page: String = "https://aulas.exemplo.com/aula/gibson") = Extract.parse(page, html)

    @Test fun threeQualitiesCollapseToOne1080() {
        val html = """
            <html><body>
              <h2>Aula Gibson</h2>
              <video>
                <source src="https://cdn.exemplo.com/v/gibson-360.mp4" />
                <source src="https://cdn.exemplo.com/v/gibson-720.mp4" />
                <source src="https://cdn.exemplo.com/v/gibson-1080.mp4" />
              </video>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val vids = r.videos.filter { it.kind != Kind.PHOTO }
        assertEquals("devia ficar 1 item, veio ${vids.map { it.url }}", 1, vids.size)
        assertTrue(vids[0].url.contains("1080"))
        assertEquals(1080, vids[0].quality)
        assertEquals("Vídeo 1080p", vids[0].typeLabel)
    }

    @Test fun playerSourcesArrayPicksMaxHeight() {
        val html = """
            <html><body>
              <script>
                var player = { sources: [
                  { file: "https://cdn.exemplo.com/p/aula_360.mp4", label: "360p" },
                  { file: "https://cdn.exemplo.com/p/aula_1080.mp4", label: "1080p" },
                  { file: "https://cdn.exemplo.com/p/aula_720.mp4", label: "720p" }
                ]};
              </script>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val vids = r.videos.filter { it.kind != Kind.PHOTO && it.url.contains("aula_") }
        assertEquals("sources viram 1 vídeo: ${vids.map { it.url }}", 1, vids.size)
        assertTrue(vids[0].url.contains("1080"))
        assertEquals(1080, vids[0].quality)
    }

    @Test fun hlsMasterSelectsTopVariant() {
        val pl = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhi.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nmid.m3u8\n"
        val v = Downloader.pickVariant("https://cdn.exemplo.com/hls/master.m3u8", pl)!!
        assertTrue(v.url, v.url.endsWith("hi.m3u8"))
        assertEquals(1080, v.height)

        val html = """
            <html><body>
              <h2>Aula</h2>
              <script>
                var src = "https://cdn.exemplo.com/hls/master.m3u8";
                var a = "https://cdn.exemplo.com/hls/360p.m3u8";
                var b = "https://cdn.exemplo.com/hls/1080p.m3u8";
              </script>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val hls = r.videos.filter { Extract.isHls(it.url) }
        assertEquals("master + variantes = 1 item: ${hls.map { it.url }}", 1, hls.size)
        assertTrue("deveria ficar o master: ${hls[0].url}", hls[0].url.contains("master.m3u8"))
    }

    @Test fun sameVideoOnTwoResultPagesIsOneItem() = runBlocking {
        val htmlA = """
            <html><body>
              <h2>Aula Gibson</h2>
              <video src="https://cdn.exemplo.com/v/gibson-720.mp4"></video>
            </body></html>
        """.trimIndent()
        val htmlB = """
            <html><body>
              <h2>Aula Gibson</h2>
              <video src="https://cdn.exemplo.com/v/gibson-1080.mp4?token=abc&expires=9"></video>
            </body></html>
        """.trimIndent()
        val a = Extract.parse("https://aulas.exemplo.com/aula/gibson?from=1", htmlA).videos
        val b = Extract.parse("https://aulas.exemplo.com/aula/gibson?from=2", htmlB).videos
        val merged = MediaIdentity.collapse(a + b)
        assertEquals("duas páginas, um vídeo: ${merged.map { it.url }}", 1, merged.size)
        assertTrue(merged[0].url.contains("1080"))
        assertTrue(MediaIdentity.same(a.first(), b.first()))
    }

    @Test fun distinctVideosOnSamePageStaySeparate() {
        val html = """
            <html><body>
              <section class="lesson-card">
                <h2>Gibson</h2>
                <video src="https://cdn.exemplo.com/v/gibson-frente.mp4"></video>
                <video src="https://cdn.exemplo.com/v/gibson-palco.mp4"></video>
              </section>
            </body></html>
        """.trimIndent()
        val vids = parse(html).videos.filter { it.kind != Kind.PHOTO }
        assertEquals("frente e palco são aulas/ângulos diferentes: ${vids.map { it.url }}", 2, vids.size)
    }

    @Test fun thumbBlockCardsAreDetected() {
        val html = """
            <html><body>
              <div class="thumbs">
                <div class="thumb-block"><a href="/video/1"><img src="/t/1.jpg"><span>Aula 1</span></a></div>
                <div class="thumb-block"><a href="/video/2"><img src="/t/2.jpg"><span>Aula 2</span></a></div>
                <div class="item"><a href="/video/3"><img src="/t/3.jpg"><p class="title">Aula 3</p></a></div>
              </div>
            </body></html>
        """.trimIndent()
        val cards = SiteSearch.resultCards("https://site.com/busca?q=gibson", html)
        val urls = cards.map { it.url }
        assertTrue(urls.toString(), urls.any { it.endsWith("/video/1") })
        assertTrue(urls.toString(), urls.any { it.endsWith("/video/2") })
        assertTrue(urls.toString(), urls.any { it.endsWith("/video/3") })
        assertTrue(cards.any { it.title.contains("Aula 1") })
    }
}
