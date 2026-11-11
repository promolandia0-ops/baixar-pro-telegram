package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.ui.qualityLabel
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class V166Test {
    private fun f(t: String, u: String = "https://x.com/v/1", h: Int = 0) = Found(u, u, t, Kind.SERVER, height = h, bestQuality = true)
    private fun m(q: String, x: Found) = NameFilter.matches(x, NameFilter.words(q))

    @Test fun anySignificantWordAndTypoAndSlug() {
        assertTrue(m("Aline Faria", f("Aline Souza no clipe")))
        assertTrue(m("joao silva", f("João Pereira")))
        assertTrue(m("farin", f("Vídeo da Aline Faria")))
        assertTrue(m("pelada", f("x", "https://site.com/novinha-pelada-na-praia/")))
        assertFalse(m("zebu", f("Aline Souza")))
        assertTrue(NameFilter.matchesText("video-da-aline-faria-hd", NameFilter.words("aline")))
    }

    @Test fun siteSearchUsesFuzzyMatch() {
        assertTrue(SiteSearch.matches("Clip da Aline Souza", NameFilter.words("Aline Faria")))
        assertFalse(SiteSearch.matches("outro assunto", NameFilter.words("Aline Faria")))
    }

    @Test fun filterFallsBackToBestAvailableAndKeepsConfirmed() {
        QualitySearch.items.value = listOf(
            f("clipe 720p", "https://a/1", 720),
            f("filme 4K oficial", "https://a/2", 0),
            f("hd confirmado", "https://a/3", 1080),
        )
        QualitySearch.filter.value = QualitySearch.Filter.UHD
        val uhd = QualitySearch.visible()
        assertEquals(1, uhd.size)
        assertEquals("filme 4K oficial", uhd[0].title)
        assertFalse(QualitySearch.filterFallback())

        QualitySearch.items.value = listOf(f("sem marca", "https://a/4", 720), f("outro", "https://a/5", 480))
        QualitySearch.filter.value = QualitySearch.Filter.UHD
        val fallback = QualitySearch.visible()
        assertEquals(2, fallback.size)
        assertTrue(QualitySearch.filterFallback())

        QualitySearch.filter.value = QualitySearch.Filter.HD
        QualitySearch.items.value = listOf(f("só 720", "https://a/6", 720))
        assertEquals(1, QualitySearch.visible().size)
        assertTrue(QualitySearch.filterFallback())

        QualitySearch.filter.value = QualitySearch.Filter.ANY
        QualitySearch.items.value = emptyList()
    }

    @Test fun likelyQualityLabelFromTitle() {
        assertEquals("4K", qualityLabel(2160, "x"))
        assertEquals("provável 4K", qualityLabel(0, "Clipe oficial 4K"))
        assertEquals("provável 1080p", qualityLabel(0, "versão 1080p"))
        assertEquals("—", qualityLabel(0, "sem marca"))
        assertEquals(2160, QualitySearch.heightFromTitle("UHD 2160p release"))
        assertEquals(2160, QualitySearch.effectiveHeight(f("vídeo 4K", h = 0)))
        assertEquals(1080, QualitySearch.effectiveHeight(f("título", h = 1080)))
    }

    @Test fun parseQualityKeepsPendingFlag() {
        val raw = """{"ok":true,"query":"pelada","page":1,"has_more":true,"pending":true,"results":[{"url":"https://www.xvideos.com/video.abc/slug","title":"Novinha pelada","height":0,"site":"XVideos"}]}"""
        val p = Downloader.parseQuality(raw, 200, "pelada", 1)
        assertTrue(p.ok)
        assertTrue(p.pending)
        assertTrue(p.hasMore)
        assertEquals(1, p.results.size)
        assertEquals("Novinha pelada", p.results[0].title)
        val done = Downloader.parseQuality("""{"ok":true,"query":"pelada","page":1,"has_more":false,"pending":false,"results":[]}""", 200, "pelada", 1)
        assertTrue(done.ok)
        assertFalse(done.pending)
    }

    @Test fun qualitySearchBodyIncludesSite() {
        val payload = JSONObject().put("query", "aline").put("url", "").put("page", 1).put("site", "exemplo.com")
        assertEquals("exemplo.com", payload.getString("site"))
        assertEquals("aline", payload.getString("query"))
    }
}
