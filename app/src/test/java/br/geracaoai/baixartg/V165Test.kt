package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import org.junit.Assert.*
import org.junit.Test

class V165Test {
    @Test fun parseKeepsUnknownResolutionAndManyResults() {
        val raw = """
            {"ok":true,"page":1,"has_more":true,"query":"planta","results":[
              {"url":"https://www.youtube.com/watch?v=aaaaaaaaaaa","title":"Cuidados com a planta","thumbnail":"https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg","height":0,"duration":null,"site":"YouTube"},
              {"url":"https://www.youtube.com/watch?v=bbbbbbbbbbb","title":"Jardim 4K","height":2160,"duration":90.5,"site":"YouTube"},
              {"url":"https://vimeo.com/1","title":"Homem no parque","height":0,"duration":0,"site":"Vimeo"}
            ]}
        """.trimIndent()
        val p = Downloader.parseQuality(raw, 200, "planta", 1)
        assertTrue(p.ok)
        assertEquals(3, p.results.size)
        assertEquals(0, p.results[0].height)
        assertEquals(2160, p.results[1].height)
        assertEquals(90, p.results[1].duration)
        assertTrue(p.hasMore)
    }

    @Test fun parseShowsHttpErrorsInsteadOfEmptyOk() {
        val a = Downloader.parseQuality("", 502, "homem", 1)
        assertFalse(a.ok)
        assertTrue(a.error, a.error.contains("502"))
        val b = Downloader.parseQuality("not-json", 503, "mulher", 1)
        assertFalse(b.ok)
        assertTrue(b.error, b.error.contains("503"))
        val c = Downloader.parseQuality("""{"ok":false,"error":"Não achei vídeos com essa busca agora."}""", 422, "x", 1)
        assertFalse(c.ok)
        assertTrue(c.error, c.error.contains("Não achei"))
    }

    @Test fun defaultFilterIsAnyAndUnknownResolutionStaysVisible() {
        assertEquals(QualitySearch.Filter.ANY, QualitySearch.filter.value)
        QualitySearch.items.value = listOf(
            Found("https://a/1", "https://a/1", "sem resolução", Kind.SERVER, height = 0, bestQuality = true),
            Found("https://a/2", "https://a/2", "4k", Kind.SERVER, height = 2160, bestQuality = true),
        )
        QualitySearch.filter.value = QualitySearch.Filter.ANY
        val vis = QualitySearch.visible()
        assertEquals(2, vis.size)
        assertTrue(vis.any { it.height == 0 })
        QualitySearch.filter.value = QualitySearch.Filter.UHD
        assertEquals(1, QualitySearch.visible().size)
        QualitySearch.filter.value = QualitySearch.Filter.ANY
        QualitySearch.items.value = emptyList()
    }
}
