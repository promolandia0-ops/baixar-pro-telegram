package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Test
import java.io.File

class LiveTest {
    private fun crawl(u: String, d: Int, p: Int): List<Found> = runBlocking {
        val out = ArrayList<Found>()
        val n = Crawler(CrawlOpts(d, p)) { pg -> Downloader.probe(Downloader.DEFAULT_SERVER, pg) }.run(u, { }, { out += it })
        println("== $u -> $n vídeos"); out.forEach { println("  [${it.kind}] ${it.url}  (pág: ${it.page})") }; out
    }
    @Test fun crawlSites() {
        val a = crawl(System.getProperty("site1") ?: "https://www.w3schools.com/html/html5_video.asp", 0, 1)
        check(a.any { it.url.endsWith(".mp4") })
        crawl("https://test-videos.co.uk/bigbuckbunny/mp4-h264", 1, 15)
        crawl("https://commons.wikimedia.org/wiki/Category:Videos_of_cats", 1, 5)
    }
    @org.junit.Ignore @Test fun downloads() = runBlocking {
        val dir = File("build/dltest").apply { mkdirs() }
        val f1 = Downloader.direct("https://www.w3schools.com/html/mov_bbb.mp4", "https://www.w3schools.com/html/html5_video.asp", dir, "bbb") { _, _ -> }
        println("direct: ${f1.name} ${f1.length()}"); check(f1.length() > 100_000)
        val f2 = Downloader.hls("https://test-streams.mux.dev/x36xhzz/url_0/193039199_mp4_h264_aac_hd_7.m3u8".let { "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }, null, File(dir, "hls.ts")) { a, b -> }
        println("hls: ${f2.name} ${f2.length()}"); check(f2.length() > 1_000_000)
    }
    @Test fun server() = runBlocking {
        val dir = File("build/dltest").apply { mkdirs() }
        val l = crawl("https://archive.org/details/BigBuckBunny_124", 0, 1)
        check(l.any { it.kind == Kind.SERVER || it.kind == Kind.DIRECT })
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            Downloader.viaServer("https://archive.org/details/BigBuckBunny_124", dir, Downloader.DEFAULT_SERVER) { a, b -> if (a > 3_000_000) { println("server: baixando ok $a/$b"); throw kotlinx.coroutines.CancellationException("teste parar") } }
        }
        job.join(); println("server: parado ok; arquivos restantes=${dir.listFiles()!!.filter{it.name.startsWith("Big")}.size}")
    }
}
