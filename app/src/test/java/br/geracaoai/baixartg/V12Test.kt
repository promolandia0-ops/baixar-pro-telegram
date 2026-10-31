package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File

class V12Test {
    private val dir = File("build/v12").apply { mkdirs() }
    private fun crawl(u: String, d: Int, p: Int): List<Found> = runBlocking {
        val out = ArrayList<Found>(); val t = System.currentTimeMillis(); var pages = 0
        Crawler(CrawlOpts(d, p, parallel = 6)).run(u, { pages = it.pagesDone }, { out += it })
        println("CRAWL $u: $pages páginas, ${out.size} vídeos em ${System.currentTimeMillis() - t} ms"); out.take(6).forEach { println("   [${it.kind}] ${it.url} | ref=${it.ref} | '${it.title}'") }; out
    }
    @Test fun xv() { val l = crawl("https://xvideosputaria.com/elisa-sanches-junto-com-edna-samara-transando-com-dois-negoes-roludos-hd/", 1, 12); check(l.any { it.kind == Kind.HLS }) }
    @Test fun xvHome() { crawl("https://xvideosputaria.com/", 2, 60) }
    @Test fun hlsSpeed() = runBlocking {
        val u = "https://vazounudes.net/hls/6eb9322c-7122-44c1-89c7-daa60fb04b9b/playlist.m3u8"
        for (par in listOf(1, 8)) {
            val t = System.currentTimeMillis()
            val f = Downloader.hls(u, "https://xxx.vazounudes.net/", dir, "xv$par", { _, _ -> }, par)
            val s = (System.currentTimeMillis() - t) / 1000.0
            println("HLS paralelo=$par: ${f.name} ${f.length()} B em ${s}s = ${"%.1f".format(f.length() / s / 1e6)} MB/s")
            if (par == 1) f.delete()
        }
    }
    @Test fun bunkrSpeed() = runBlocking {
        val l = crawl("https://bunkr.cr/a/AnBijFAa", 1, 30).take(6)
        val t1 = System.currentTimeMillis(); var b1 = 0L
        for (f in l.take(3)) { val x = Downloader.download(f, dir, Downloader.DEFAULT_SERVER) { _, _ -> }; b1 += x.length(); x.delete() }
        val s1 = (System.currentTimeMillis() - t1) / 1000.0
        println("BUNKR sequencial 3 arquivos: $b1 B em ${s1}s = ${"%.1f".format(b1 / s1 / 1e6)} MB/s")
        val t2 = System.currentTimeMillis()
        val b2 = l.drop(3).map { f -> async(Dispatchers.IO) { Downloader.download(f, dir, Downloader.DEFAULT_SERVER) { _, _ -> }.let { val n = it.length(); it.delete(); n } } }.awaitAll().sum()
        val s2 = (System.currentTimeMillis() - t2) / 1000.0
        println("BUNKR paralelo 3 arquivos: $b2 B em ${s2}s = ${"%.1f".format(b2 / s2 / 1e6)} MB/s")
    }
    @Test fun faststart() {
        val src = File(System.getProperty("fs.in") ?: "build/v12/moovend.mp4"); if (!src.exists()) return
        val out = File(dir, "faststart.mp4"); println("FASTSTART reescreveu=${Faststart.process(src, out)}")
    }
}
