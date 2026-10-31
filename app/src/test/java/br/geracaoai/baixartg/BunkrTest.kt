package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class BunkrTest {
    @Test fun albumEndToEnd() = runBlocking {
        val out = ArrayList<Found>()
        Crawler(CrawlOpts(1, 20)).run("https://bunkr.cr/a/AnBijFAa", {}, { out += it })
        println("bunkr crawl: ${out.size}"); out.forEach { println("  [${it.kind}] ${it.url} (pág ${it.page}) '${it.title}'") }
        check(out.isNotEmpty())
        val dir = File("build/bunkr").apply { deleteRecursively(); mkdirs() }
        for (f in out.takeLast(2)) {
            val file = Downloader.download(f, dir, Downloader.DEFAULT_SERVER) { _, _ -> }
            val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
            println("bunkr download: ${file.name} ${file.length()} bytes, ftyp=${String(head, 4, 4)}")
            check(file.length() > 100_000)
        }
        val f2 = Downloader.download(Found("https://bunkr.cr/f/Ik6Multt8LnnA", "https://bunkr.cr/f/Ik6Multt8LnnA", "x", Kind.SERVER), dir, Downloader.DEFAULT_SERVER) { _, _ -> }
        println("bunkr /f/ direto: ${f2.name} ${f2.length()}")
    }
}
