package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class V174Test {

    @Test fun listingThumbWithoutThumbInNameIsCover() {
        val page = "https://escola.exemplo.com/professor/joao"
        val html = """
            <html><body>
              <div class="item">
                <a href="/p/1"><img src="/uploads/joao-aula-1.jpg" alt="Aula 1"><h2>Aula 1</h2><span class="duration">12:34</span></a>
              </div>
            </body></html>
        """.trimIndent()
        val el = Jsoup.parse(html, page).selectFirst("img")!!
        assertTrue(Extract.isCoverImage("https://escola.exemplo.com/uploads/joao-aula-1.jpg", el, page))
        val r = Extract.parse(page, html)
        assertTrue("capa da listagem não pode virar foto: ${r.videos}", r.videos.none { it.kind == Kind.PHOTO || it.url.endsWith(".jpg") })
    }

    @Test fun contentPhotosAreNeverCollected() {
        val html = """
            <html><body>
              <h1>Aula Gibson</h1>
              <video src="/media/aula.mp4"></video>
              <figure><img src="/fotos/partitura-frente.jpg" alt="partitura"></figure>
            </body></html>
        """.trimIndent()
        val r = Extract.parse("https://escola.exemplo.com/aula/gibson", html)
        assertTrue(r.videos.any { it.url.endsWith("aula.mp4") && it.kind != Kind.PHOTO })
        assertTrue("fotos de conteúdo também ficam de fora: ${r.videos}", r.videos.none { it.kind == Kind.PHOTO || it.url.endsWith(".jpg") })
    }

    @Test fun directListingPageResolvesVideosAndDropsCovers() = runBlocking {
        val env = TeacherListing.start()
        try {
            val found = ArrayList<Found>()
            // mesmo caminho do Filtrar / Início: profundidade 0, 1 página, fotos desligadas
            val n = Crawler(CrawlOpts(0, 1, followCards = true, maxCardPages = 50, parallel = 8))
                .run(env.listing, { }) { found += it }
            val urls = found.map { it.url }
            assertEquals("devia achar 3 vídeos, n=$n urls=$urls", 3, found.size)
            assertTrue("todos devem ser o mp4 resolvido: $urls", found.all { it.url.contains("/media/") && it.url.endsWith(".mp4") && it.kind != Kind.PHOTO })
            assertTrue("nenhuma capa/upload: $urls", found.none {
                it.kind == Kind.PHOTO || it.url.endsWith(".jpg") || it.url.contains("/uploads/")
            })
            assertTrue(urls.any { it.endsWith("joao-1.mp4") })
            assertTrue(urls.any { it.endsWith("joao-2.mp4") })
            assertTrue(urls.any { it.endsWith("joao-3.mp4") })
        } finally { env.stop() }
    }

    @Test fun listingPicksMaxQualityAndDedupes() = runBlocking {
        val env = TeacherListing.start(qualities = true)
        try {
            val found = ArrayList<Found>()
            Crawler(CrawlOpts(0, 1, followCards = true, maxCardPages = 50, parallel = 8))
                .run(env.listing, { }) { found += it }
            val aula1 = found.filter { it.title.contains("Aula 1") || it.url.contains("joao-1") }
            assertEquals("variantes de qualidade viram 1 item: $found", 1, aula1.size)
            assertTrue("maior qualidade: ${aula1.firstOrNull()}", aula1.any { it.url.contains("1080") || it.quality >= 1080 })
            assertTrue(found.none { it.kind == Kind.PHOTO })
        } finally { env.stop() }
    }

    @Test fun watchPageDoesNotFollowRelatedCards() = runBlocking {
        val env = TeacherListing.start()
        try {
            val found = ArrayList<Found>()
            Crawler(CrawlOpts(0, 1, followCards = true, maxCardPages = 50, parallel = 8))
                .run(env.watch, { }) { found += it }
            val urls = found.map { it.url }
            assertTrue("página da aula deve ter o vídeo dela: $urls", urls.any { it.endsWith("principal.mp4") })
            assertTrue("não deve abrir os relacionados: $urls", found.none { it.url.contains("rel-") })
            assertTrue(found.none { it.kind == Kind.PHOTO })
        } finally { env.stop() }
    }

    @Test fun photosStayOffByDefaultEvenOnListing() = runBlocking {
        val env = TeacherListing.start()
        try {
            val found = ArrayList<Found>()
            Crawler(CrawlOpts(0, 1, followCards = true, maxCardPages = 50)).run(env.listing, { }) { found += it }
            assertTrue(found.isNotEmpty())
            assertTrue("padrão é só vídeo: $found", found.none { it.kind == Kind.PHOTO })
        } finally { env.stop() }
    }

    @Test fun sniffRejectsJpegAsVideo() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image", Extract.sniffMagic(jpeg))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals("image", Extract.sniffMagic(png))
        assertNull(Extract.kindFromContentType("image/jpeg"))
        assertNull(Extract.mediaKindOf("https://cdn.exemplo.com/uploads/capa.jpg"))
    }

    @Test fun singleVideoPageWithJsInjectedPlayer() = runBlocking {
        val env = JsPlayerPage.start()
        try {
            val found = ArrayList<Found>()
            val htmlAfter = """<html><body><video src="${env.mp4}"></video></body></html>"""
            val n = Crawler(
                CrawlOpts(0, 1, followCards = true, maxCardPages = 10, resolvePlayer = true, parallel = 4),
                jsFallback = { u ->
                    JsPage(htmlAfter, listOf(Found(env.mp4, u, "Aula JS", Kind.DIRECT, extra = "Aula JS")))
                },
                probe = { "Aula JS (yt-dlp)" },
            ).run(env.page, { }) { found += it }
            val urls = found.map { it.url }
            assertEquals("um vídeo só (js ganha do yt-dlp): n=$n $found", 1, found.size)
            assertTrue("devia resolver o mp4 injetado: $urls", found.any { it.url == env.mp4 && it.kind != Kind.PHOTO })
            assertTrue("não deve ficar item SERVER além do mp4: $found", found.none { it.kind == Kind.SERVER })
            assertTrue(found.none { it.kind == Kind.PHOTO || it.url.endsWith(".jpg") })
        } finally { env.stop() }
    }

    @Test fun singleVideoPageFallsBackToYtDlpWhenStaticAndJsEmpty() = runBlocking {
        val env = JsPlayerPage.start()
        try {
            val found = ArrayList<Found>()
            Crawler(
                CrawlOpts(0, 1, followCards = true, resolvePlayer = true, parallel = 4),
                jsFallback = { JsPage("<html><body><div id=player></div></body></html>", emptyList()) },
                probe = { "Aula só no servidor" },
            ).run(env.page, { }) { found += it }
            assertEquals("yt-dlp vira um item SERVER: $found", 1, found.size)
            assertEquals(Kind.SERVER, found[0].kind)
            assertEquals(env.page, found[0].url)
            assertTrue(found.none { it.kind == Kind.PHOTO })
        } finally { env.stop() }
    }
}

private class JsPlayerPage(val page: String, val mp4: String, private val http: MiniHttp) {
    fun stop() { http.stop() }
    companion object {
        fun start(): JsPlayerPage {
            val http = MiniHttp { _, _, _, _ -> 404 to ("text/plain" to ByteArray(0)) }
            val host = "http://127.0.0.1:${http.port}"
            // HTML estático: só o player, sem URL de vídeo (simula JS/JSON injetando depois).
            val shell = """
                <html><head><title>Aula JS</title></head><body>
                  <h1>Aula JS</h1>
                  <div id="player" class="video-js"></div>
                  <script src="/player.js"></script>
                </body></html>
            """.trimIndent()
            http.handler = { _, path, _, _ ->
                when (path) {
                    "/aula/js", "/aula/js/" -> 200 to ("text/html; charset=utf-8" to shell.toByteArray())
                    "/player.js" -> 200 to ("application/javascript" to "/* sources loaded at runtime */".toByteArray())
                    else -> 404 to ("text/plain" to ByteArray(0))
                }
            }
            return JsPlayerPage("$host/aula/js", "$host/media/secreta.mp4", http)
        }
    }
}

private class TeacherListing(val listing: String, val watch: String, private val http: MiniHttp) {
    fun stop() { http.stop() }

    companion object {
        fun start(qualities: Boolean = false): TeacherListing {
            val http = MiniHttp { _, _, _, _ -> 404 to ("text/plain" to ByteArray(0)) }
            val host = "http://127.0.0.1:${http.port}"
            fun html(title: String, body: String) =
                "<html><head><title>$title</title></head><body>$body</body></html>"
            fun card(id: String, title: String, dur: String) =
                """<div class="item video-item"><a href="/p/$id"><img src="/uploads/joao-$id.jpg" alt="$title"><h2>$title</h2><span class="duration">$dur</span></a></div>"""
            val listingBody = listOf(
                card("1", "Aula 1 — Gibson", "10:01"),
                card("2", "Aula 2 — pentatônico", "12:34"),
                card("3", "Aula 3 — notas equivalentes", "08:15"),
            ).joinToString("")
            http.handler = { _, path, _, _ ->
                fun ok(s: String) = 200 to ("text/html; charset=utf-8" to s.toByteArray())
                when {
                    path == "/professor/joao" || path == "/professor/joao/" ->
                        ok(html("Aulas do professor João", """<h1>Professor João</h1><div class="list-videos">$listingBody</div>"""))
                    path.startsWith("/p/") -> {
                        val id = path.substringAfter("/p/")
                        val title = "Aula $id"
                        val sources = if (qualities && id == "1") {
                            """<video><source src="/media/joao-1-720.mp4" size="720"><source src="/media/joao-1-1080.mp4" size="1080"></video>"""
                        } else {
                            """<video src="/media/joao-$id.mp4"></video>"""
                        }
                        ok(html(title, "<h2>$title</h2>$sources"))
                    }
                    path == "/aula/principal" -> {
                        val related = (1..3).joinToString("") { i ->
                            """<div class="item"><a href="/p/rel-$i"><img src="/uploads/rel-$i.jpg" alt="Rel $i"><h3>Relacionado $i</h3><span class="duration">0$i:0$i</span></a></div>"""
                        }
                        ok(html("Aula principal", """<h1>Aula principal</h1><video src="/media/principal.mp4"></video><aside class="related">$related</aside>"""))
                    }
                    else -> 404 to ("text/plain" to ByteArray(0))
                }
            }
            // se o teste só precisa do watch, ainda servimos a listagem
            return TeacherListing("$host/professor/joao", "$host/aula/principal", http)
        }
    }
}
