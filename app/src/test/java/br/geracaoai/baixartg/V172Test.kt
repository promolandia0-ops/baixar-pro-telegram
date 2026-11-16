package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class V172Test {
    private fun parse(html: String, page: String = "https://aulas.exemplo.com/indice") = Extract.parse(page, html)

    @Test fun posterIsNotAPhoto() {
        val html = """
            <html><body>
              <video poster="/thumbs/aula-poster.jpg" src="/videos/aula.mp4"></video>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        assertTrue(r.videos.any { it.url.endsWith("aula.mp4") && it.kind != Kind.PHOTO })
        assertTrue(r.videos.none { it.url.contains("poster") || it.url.endsWith("aula-poster.jpg") })
    }

    @Test fun videoObjectThumbnailIsExcluded() {
        val html = """
            <html><body>
              <script type="application/ld+json">
                {"@type":"VideoObject","name":"Aula Gibson",
                 "contentUrl":"https://cdn.exemplo.com/v/gibson.mp4",
                 "thumbnailUrl":"https://cdn.exemplo.com/thumbs/gibson-thumb.jpg",
                 "image":"https://cdn.exemplo.com/thumbs/gibson-cover.jpg"}
              </script>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val urls = r.videos.map { it.url }
        assertTrue(urls.any { it.endsWith("gibson.mp4") })
        assertTrue(urls.none { it.contains("thumb") || it.contains("cover") })
        assertTrue(r.videos.none { it.kind == Kind.PHOTO })
    }

    @Test fun ogImageDroppedWhenOgVideoExists() {
        val html = """
            <html><head>
              <meta property="og:title" content="Aula Gibson">
              <meta property="og:image" content="https://cdn.exemplo.com/covers/gibson.jpg">
              <meta property="og:video" content="https://cdn.exemplo.com/v/gibson.mp4">
            </head><body></body></html>
        """.trimIndent()
        val r = parse(html)
        assertTrue(r.videos.any { it.url.endsWith("gibson.mp4") })
        assertTrue(r.videos.none { it.url.contains("covers/") || it.kind == Kind.PHOTO })
    }

    @Test fun thumbUrlPatternsExcluded() {
        assertTrue(Extract.isVideoThumb("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg"))
        assertTrue(Extract.isVideoThumb("https://cdn.exemplo.com/thumbs/aula.jpg"))
        assertTrue(Extract.isVideoThumb("https://cdn.exemplo.com/media/aula-poster.jpg"))
        assertTrue(Extract.isVideoThumb("https://cdn.exemplo.com/media/gibson-capa.jpg"))
        assertTrue(Extract.isVideoThumb("https://cdn.exemplo.com/preview/x.webp"))
        assertFalse(Extract.isVideoThumb("https://cdn.exemplo.com/fotos/aula-gibson.jpg"))
    }

    @Test fun cardThumbnailLinkYieldsVideoNotImage() = runBlocking {
        val env = LessonSite.start()
        try {
            val found = ArrayList<Found>()
            val n = Crawler(CrawlOpts(1, 80, nameWords = NameFilter.words("Gibson"), nameQuery = "Gibson", parallel = 16))
                .runSiteSearch(env.base, { }) { found += it }
            val urls = found.map { it.url }
            assertTrue("devia achar o mp4, n=$n urls=$urls", urls.any { it.contains("/media/aula-") && it.endsWith(".mp4") })
            assertTrue("não devia listar a capa/thumb: $urls", found.none { it.kind == Kind.PHOTO || it.url.contains("thumb") || it.url.endsWith(".jpg") })
        } finally { env.stop() }
    }

    @Test fun siteSearchTrustsAllCardsWithoutStrictNearbyText() = runBlocking {
        val env = LessonSite.start(pages = 1, perPage = 5, untitled = true)
        try {
            val found = ArrayList<Found>()
            val n = Crawler(CrawlOpts(1, 80, nameWords = NameFilter.words("Gibson"), nameQuery = "Gibson", parallel = 16))
                .runSiteSearch(env.base, { }) { found += it }
            // páginas "Aula 1..5" sem a palavra Gibson no título da mídia — entram porque vieram da busca
            assertTrue("poucos resultados na busca: n=$n ${found.map { it.title }}", found.size >= 5)
            assertTrue(found.none { it.kind == Kind.PHOTO })
        } finally { env.stop() }
    }

    @Test fun siteSearchFollowsPaginationUntilExhausted() = runBlocking {
        val env = LessonSite.start(pages = 5, perPage = 10)
        try {
            val found = ArrayList<Found>()
            var maxPages = 0
            val n = Crawler(CrawlOpts(1, 200, nameWords = NameFilter.words("Gibson"), nameQuery = "Gibson", parallel = 24))
                .runSiteSearch(env.base, { maxPages = maxOf(maxPages, it.pagesDone) }) { found += it }
            val videos = found.filter { it.kind != Kind.PHOTO && it.url.contains("aula-") && it.url.endsWith(".mp4") }
            assertTrue("esperava 50 vídeos das 5 páginas, veio ${videos.size} (n=$n pages=$maxPages) ${videos.map { it.url }.take(8)}", videos.size >= 50)
        } finally { env.stop() }
    }

    @Test fun sniffMagicDetectsImageHtmlVideo() {
        assertEquals("image", Extract.sniffMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image", Extract.sniffMagic(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("html", Extract.sniffMagic("<!DOCTYPE html><html>".toByteArray()))
        assertEquals("html", Extract.sniffMagic("  <html lang=pt>".toByteArray()))
        val mp4 = ByteArray(12)
        "....ftyp".toByteArray().copyInto(mp4) // offset 0-3 dummy, 4-7 ftyp — need size at 0
        // ftyp is at offset 4
        val m = ByteArray(12)
        m[4] = 'f'.code.toByte(); m[5] = 't'.code.toByte(); m[6] = 'y'.code.toByte(); m[7] = 'p'.code.toByte()
        assertEquals("video", Extract.sniffMagic(m))
        assertFalse(Extract.sniffMagic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) == "video")
    }

    @Test fun parseNeverEmitsContentPhotos() {
        val html2 = """
            <html><body>
              <section class="material">
                <h2>Partitura Gibson</h2>
                <img src="/fotos/partitura-gibson.jpg" width="800" height="600" alt="Partitura Gibson">
              </section>
              <section class="player">
                <h2>Aula</h2>
                <video src="/videos/gibson.mp4"></video>
              </section>
            </body></html>
        """.trimIndent()
        val r2 = parse(html2)
        assertTrue(r2.videos.any { it.url.endsWith("gibson.mp4") && it.kind != Kind.PHOTO })
        assertTrue("não coleta partitura: ${r2.videos}", r2.videos.none { it.kind == Kind.PHOTO || it.url.endsWith(".jpg") })
    }
}

private class LessonSite(val base: String, private val http: MiniHttp) {
    fun stop() { http.stop() }

    companion object {
        fun start(pages: Int = 1, perPage: Int = 3, untitled: Boolean = false): LessonSite {
            val http = MiniHttp { _, _, _, _ -> 404 to ("text/plain" to ByteArray(0)) }
            val host = "http://127.0.0.1:${http.port}"
            val total = pages * perPage
            fun html(title: String, body: String) =
                "<html><head><title>$title</title></head><body>$body</body></html>"
            fun cards(page: Int): String {
                val start = (page - 1) * perPage + 1
                val end = minOf(page * perPage, total)
                return (start..end).joinToString("") { i ->
                    val title = if (untitled) "Aula $i" else "Aula Gibson $i"
                    """<article class="card"><a href="/aula/$i"><img src="/thumbs/aula-$i.jpg" alt="$title"><h2>$title</h2></a></article>"""
                }
            }
            fun pagNav(page: Int): String {
                val next = if (page < pages) """<a rel="next" href="/busca?q=Gibson&page=${page + 1}">Próxima</a>""" else ""
                return """<nav class="pagination">$next</nav>"""
            }
            http.handler = { method, path, query, body ->
                fun ok(s: String, ct: String = "text/html; charset=utf-8") = 200 to (ct to s.toByteArray())
                val q = if (method == "POST") body else query
                val term = q.substringAfter("q=", "").substringBefore("&").let {
                    try { URLDecoder.decode(it.replace("+", " "), "UTF-8") } catch (_: Exception) { it }
                }
                val pageNum = Regex("""(?:^|&)page=(\d+)""").find(q)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                when {
                    path == "/" || path.isEmpty() -> ok(html("Escola", """<form action="/busca" method="get"><input type="search" name="q"></form>"""))
                    path == "/busca" -> {
                        val list = if (term.contains("gibson", true) || term.isBlank()) cards(pageNum) + pagNav(pageNum) else "<p>nada</p>"
                        ok(html("Busca", "<main>$list</main>"))
                    }
                    path.startsWith("/aula/") -> {
                        val i = path.substringAfter("/aula/").toIntOrNull() ?: 0
                        val title = if (untitled) "Aula $i" else "Aula Gibson $i"
                        ok(html(title, """<h2>$title</h2><video src="/media/aula-$i.mp4"></video>"""))
                    }
                    else -> 404 to ("text/plain" to ByteArray(0))
                }
            }
            return LessonSite("$host/", http)
        }
    }
}
