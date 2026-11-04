package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class V15Test {
    @Test fun pickVariantQuality() {
        val pl = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhi.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nmid.m3u8\n"
        check(Downloader.pickVariant("https://x.com/a/m.m3u8", pl)!!.url.endsWith("hi.m3u8"))
        check(Downloader.pickVariant("https://x.com/a/m.m3u8", pl, 720)!!.url.endsWith("mid.m3u8"))
        check(Downloader.pickVariant("https://x.com/a/m.m3u8", pl, 360)!!.url.endsWith("hi.m3u8")) // nada abaixo: usa a melhor
    }
    /** Filtrar: profundidade 0 / 1 página = só os vídeos daquela página. */
    @Test fun filterSinglePage() = runBlocking {
        val out = ArrayList<Found>(); var pages = 0
        Crawler(CrawlOpts(0, 1)).run("https://test-videos.co.uk/bigbuckbunny/mp4-h264", { pages = it.pagesDone }) { out += it }
        println("FILTRAR: páginas=$pages vídeos=${out.size}"); out.forEach { println("  ${it.url}") }
        check(pages == 1); check(out.isNotEmpty()); check(out.all { it.page.contains("bigbuckbunny/mp4-h264") })
        val sz = Downloader.sizeOf(out.first()); println("TAMANHO ${out.first().url} = $sz"); check(sz > 0)
    }
    /** Varrer tudo: mesmo domínio, respeita o limite de páginas. */
    @Test fun crawlSiteCapped() = runBlocking {
        val out = ArrayList<Found>(); var pages = 0
        Crawler(CrawlOpts(2, 12)).run("https://test-videos.co.uk/", { pages = it.pagesDone }) { out += it }
        println("VARRER: páginas=$pages vídeos=${out.size} páginas-com-vídeo=${out.map { it.page }.distinct().size}")
        check(pages in 2..12); check(out.size > 0)
    }
    @Test fun parseLinks() { check(Jobs.parseLinks("a.com/x\n https://b.com/y  \n\nhttps://b.com/y").size == 2) }
}
