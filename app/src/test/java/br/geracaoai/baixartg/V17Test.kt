package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import org.junit.Assert.*
import org.junit.Test

class V17Test {
    private fun m(q: String, x: Found) = NameFilter.matches(x, NameFilter.words(q))
    private fun parse(html: String, page: String = "https://aulas.exemplo.com/indice") = Extract.parse(page, html)

    @Test fun guitarraDoesNotMatchGuitarrada() {
        val aula = Found("https://cdn.exemplo.com/aulas/guitarra-basico.mp4", "https://aulas.exemplo.com/1", "Aula de guitarra", Kind.DIRECT, extra = "Aula de guitarra")
        val outra = Found("https://cdn.exemplo.com/aulas/guitarrada-festa.mp4", "https://aulas.exemplo.com/2", "Guitarrada no forró", Kind.DIRECT, extra = "Guitarrada no forró")
        assertTrue(m("guitarra", aula))
        assertFalse("guitarra não pode casar com guitarrada", m("guitarra", outra))
        assertTrue(m("guitarrada", outra))
        assertFalse(m("guitarrada", aula))
    }

    @Test fun multiWordAndDedilhadoPentatonico() {
        val aula = Found("https://cdn.exemplo.com/v/aula.mp4", "https://aulas.exemplo.com/x", "Dedilhado pentatônico", Kind.DIRECT, extra = "Dedilhado pentatônico")
        val soDed = Found("https://cdn.exemplo.com/v/outro.mp4", "https://aulas.exemplo.com/y", "Dedilhado clássico", Kind.DIRECT, extra = "Dedilhado clássico")
        val soPent = Found("https://cdn.exemplo.com/v/penta.mp4", "https://aulas.exemplo.com/z", "Escala pentatônica", Kind.DIRECT, extra = "Escala pentatônica")
        val q = "dedilhado pentatônico"
        assertTrue(m(q, aula))
        assertTrue(m("DEDILHADO PENTATONICO", aula))
        assertFalse(m(q, soDed))
        assertFalse(m(q, soPent))
        val longTitle = Found("https://cdn.exemplo.com/v/n.mp4", "https://aulas.exemplo.com/n", "Notas equivalentes ao processo", Kind.DIRECT, extra = "Notas equivalentes ao processo")
        assertTrue(m("notas equivalentes ao processo", longTitle))
        assertFalse(m("notas equivalentes ao processo", aula))
    }

    @Test fun filenameSlugWholeWordsOnly() {
        val byFile = Found("https://cdn.exemplo.com/files/gibson-aula-01.mp4", "https://aulas.exemplo.com/p", "arquivo", Kind.DIRECT)
        assertTrue(m("Gibson", byFile))
        assertTrue(m("aula", byFile))
        assertFalse(m("tar", byFile))
        val onCategoryPage = Found(
            "https://cdn.exemplo.com/files/bateria-basico.mp4",
            "https://aulas.exemplo.com/aulas/guitarra/indice",
            "Bateria básico",
            Kind.DIRECT,
            extra = "Bateria básico",
        )
        assertFalse(m("guitarra", onCategoryPage))
    }

    @Test fun gibsonHeadingTagsPhotoAndTwoVideos() {
        val html = """
            <html><body>
              <h1>Escola de guitarra</h1>
              <p>Bem-vindo ao site. Aqui estão as aulas da semana, com material de apoio e recados da secretaria.</p>
              <section class="lesson-card">
                <h2>Gibson</h2>
                <img src="/fotos/gibson-capa.jpg" alt="">
                <video src="/videos/gibson-frente.mp4"></video>
                <video src="/videos/gibson-palco.mp4"></video>
              </section>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val words = NameFilter.words("Gibson")
        val hits = r.videos.filter { NameFilter.matches(it, words) }
        assertEquals("2 vídeos (capa não entra como foto)", 2, hits.size)
        assertTrue(hits.none { it.url.contains("capa") || it.kind == Kind.PHOTO })
        assertEquals(2, hits.count { it.kind != Kind.PHOTO && it.url.contains("gibson") })
        hits.forEach { f ->
            val blob = (f.title + " " + f.extra).lowercase()
            assertTrue("${f.url} devia trazer Gibson no texto associado, title=${f.title} extra=${f.extra}", blob.contains("gibson"))
        }
    }

    @Test fun gibsonAsPreviousSiblingsWithoutWrapper() {
        val html = """
            <html><body>
              <h2>Gibson</h2>
              <img src="https://cdn.exemplo.com/g.jpg">
              <video src="https://cdn.exemplo.com/a.mp4"></video>
              <video src="https://cdn.exemplo.com/b.mp4"></video>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val hits = r.videos.filter { NameFilter.matches(it, NameFilter.words("Gibson")) }
        assertEquals("2 vídeos; a imagem ao lado do player é capa, não foto da aula", 2, hits.size)
        assertTrue(hits.none { it.kind == Kind.PHOTO })
    }

    @Test fun distantH1DoesNotAttach() {
        val html = """
            <html><head><title>Guitarra — portal de aulas</title>
              <meta property="og:title" content="Guitarra — portal de aulas">
            </head><body>
              <h1>Guitarra</h1>
              <p>Bem-vindo ao portal. Aqui publicamos recados, agenda de shows e avisos da escola, um texto longo de introdução que não é o título de nenhuma aula específica.</p>
              <p>Mais um parágrafo de apresentação do professor e da metodologia, ainda sem relação com o clipe abaixo.</p>
              <div class="outro">
                <video src="https://cdn.exemplo.com/bateria-livre.mp4"></video>
              </div>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val vid = r.videos.first { it.url.endsWith("bateria-livre.mp4") }
        assertFalse("h1 distante / título da página não pode marcar o vídeo", m("guitarra", vid))
        assertFalse(vid.extra.contains("Guitarra", true))
        assertFalse(vid.title.equals("Guitarra", true) || vid.title.startsWith("Guitarra"))
    }

    @Test fun jsonLdNameDoesNotLeakToOtherMedia() {
        val html = """
            <html><head>
              <meta property="og:title" content="Portal de aulas de guitarra">
              <script type="application/ld+json">
                {"@type":"VideoObject","name":"Dedilhado pentatônico",
                 "contentUrl":"https://cdn.exemplo.com/ld/penta.mp4"}
              </script>
            </head><body>
              <video src="https://cdn.exemplo.com/solto.mp4"></video>
              <h2>Bateria</h2>
              <video src="https://cdn.exemplo.com/bateria.mp4"></video>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val penta = r.videos.first { it.url.endsWith("penta.mp4") }
        val solto = r.videos.first { it.url.endsWith("solto.mp4") }
        val bateria = r.videos.first { it.url.endsWith("bateria.mp4") }
        assertTrue(m("dedilhado pentatônico", penta))
        assertFalse(m("dedilhado pentatônico", solto))
        assertFalse(m("dedilhado pentatônico", bateria))
        assertFalse(m("guitarra", solto))
        assertTrue(m("bateria", bateria))
    }

    @Test fun parseDoesNotCollectPhotos() {
        val html = """
            <html><body>
              <img src="/icons/logo.png" width="32" height="32" alt="logo">
              <img src="/fotos/aula-gibson.jpg" width="800" height="600" alt="Gibson">
              <a href="/arquivos/capa.webp">material</a>
              <video src="/videos/aula.mp4"></video>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        assertTrue(r.videos.any { it.url.endsWith("aula.mp4") && it.kind != Kind.PHOTO })
        assertTrue("v1.7.4 não coleta foto: ${r.videos}", r.videos.none { it.kind == Kind.PHOTO || it.url.endsWith(".jpg") || it.url.endsWith(".webp") || it.url.endsWith(".png") })
    }

    @Test fun contentTypeAndSniffCandidates() {
        assertEquals(Kind.HLS, Extract.kindFromContentType("application/vnd.apple.mpegurl"))
        assertNull(Extract.kindFromContentType("image/jpeg; charset=binary"))
        assertEquals(Kind.DIRECT, Extract.kindFromContentType("video/mp4"))
        assertEquals(Kind.SERVER, Extract.kindFromContentType("application/dash+xml"))
        assertNull(Extract.kindFromContentType("text/html; charset=utf-8"))
        assertTrue(Extract.isSniffCandidate("https://exemplo.com/player/get-file.php?id=12"))
        assertTrue(Extract.isSniffCandidate("https://exemplo.com/api/stream?v=1"))
        assertFalse(Extract.isSniffCandidate("https://exemplo.com/videos/aula.mp4"))
        assertFalse(Extract.isSniffCandidate("https://exemplo.com/aulas/gibson"))
        assertTrue(Extract.isDash("https://cdn.exemplo.com/play.mpd?token=1"))
    }

    @Test fun noTypoOrPrefixInNamedSearch() {
        val aula = Found("https://cdn.exemplo.com/gibson.mp4", "https://x", "Gibson", Kind.DIRECT, extra = "Gibson")
        assertFalse(m("gibso", aula))
        assertFalse(m("gibs", aula))
        assertTrue(m("gibson", aula))
        assertTrue(m("GIBSON", aula))
        assertTrue(m("Gíbsón", aula))
    }

    @Test fun playerScriptAndDataAttrsAndJsonLd() {
        val html = """
            <html><body>
              <div data-video="/media/aula.mp4" data-title="Dedilhado"></div>
              <script>var player = { file: "https://cdn.exemplo.com/player/setup.mp4" };</script>
              <script type="application/ld+json">
                {"@type":"VideoObject","name":"Notas equivalentes ao processo",
                 "contentUrl":"https://cdn.exemplo.com/ld/notas.mp4"}
              </script>
              <iframe data-url="https://www.youtube.com/embed/jNQXAC9IVRw"></iframe>
            </body></html>
        """.trimIndent()
        val r = parse(html)
        val urls = r.videos.map { it.url }
        assertTrue(urls.any { it.endsWith("aula.mp4") })
        assertTrue(urls.any { it.endsWith("setup.mp4") })
        assertTrue(urls.any { it.endsWith("notas.mp4") })
        assertTrue(r.videos.any { it.kind == Kind.SERVER && it.url.contains("youtube.com/embed") })
        assertTrue(m("notas equivalentes ao processo", r.videos.first { it.url.endsWith("notas.mp4") }))
    }
}
