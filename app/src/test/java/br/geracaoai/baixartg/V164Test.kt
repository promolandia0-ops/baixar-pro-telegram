package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import br.geracaoai.baixartg.ui.fmtDur
import br.geracaoai.baixartg.ui.qualityLabel
import br.geracaoai.baixartg.web.AdBlock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V164Test {
    @Test fun adBlockDoesNotKillFirstPartyButBlocksAds() {
        AdBlock.loadForTest(
            hostList = listOf("doubleclick.net", "googlesyndication.com", "ads.google.com", "tracker.example.com"),
            pathList = listOf("/pagead/"),
            hostPathList = listOf("youtube.com" to "/ptracking")
        )
        assertFalse(AdBlock.shouldBlock("https://www.youtube.com/watch?v=abc", mainFrame = true))
        assertFalse(AdBlock.shouldBlock("https://google.com/search?q=bunny", mainFrame = true))
        assertFalse(AdBlock.shouldBlock("https://i.ytimg.com/vi/abc/hqdefault.jpg", mainFrame = false, pageUrl = "https://www.youtube.com/"))
        assertTrue(AdBlock.shouldBlock("https://doubleclick.net/gampad", mainFrame = false))
        assertTrue(AdBlock.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", mainFrame = false))
        assertTrue(AdBlock.shouldBlock("https://ads.google.com/xyz", mainFrame = false))
        assertTrue(AdBlock.shouldBlock("https://www.youtube.com/ptracking?ev=1", mainFrame = false))
        assertTrue(AdBlock.shouldBlock("https://example.com/pagead/js/ads.js", mainFrame = false))
        assertFalse(AdBlock.isProtectedHost("doubleclick.net"))
        assertTrue(AdBlock.isProtectedHost("www.youtube.com"))
        assertFalse(AdBlock.isProtectedHost("ads.google.com"))
    }

    @Test fun parseEasyListOnlyFullHostRules() {
        val hosts = LinkedHashSet<String>()
        val hp = ArrayList<Pair<String, String>>()
        AdBlock.parseFilterList(
            """
            [Adblock Plus 2.0]
            ! comment
            ||doubleclick.net^
            ||googlesyndication.com^${'$'}third-party
            ||youtube.com/ptracking
            ||google.com/pagead/js
            ||youtube.com^
            @@||example.com^
            """.trimIndent(),
            hosts,
            hp
        )
        assertTrue(hosts.contains("doubleclick.net"))
        assertTrue(hosts.contains("googlesyndication.com"))
        assertFalse("youtube.com should not be a full-host block", hosts.contains("youtube.com"))
        assertFalse(hosts.contains("google.com"))
        assertTrue(hp.any { it.first == "youtube.com" && it.second.startsWith("/ptracking") })
        assertTrue(hp.any { it.first == "google.com" && it.second.startsWith("/pagead") })
    }

    @Test fun sessionSendsCookiesAndUserAgentButCookieJarIsPerHost() {
        val prev = Net.cookies
        val prevUa = Net.userAgent
        try {
            Net.userAgent = "TestUA/1.0"
            Net.cookies = object : CookieSource {
                override fun cookieFor(url: String): String? {
                    val h = java.net.URI(url).host ?: return null
                    return when {
                        "youtube" in h -> "SID=yt; LOGIN_INFO=1"
                        "vimeo" in h -> "player=1"
                        else -> null
                    }
                }
                override fun knownHosts() = listOf("www.youtube.com", "vimeo.com")
            }
            val j = Downloader.session(JSONObject().put("url", "https://www.youtube.com/watch?v=x"), "https://www.youtube.com/watch?v=x", withJar = true)
            assertEquals("SID=yt; LOGIN_INFO=1", j.getString("cookies"))
            assertEquals("TestUA/1.0", j.getString("user_agent"))
            assertTrue(j.has("referer"))
            val jar = j.getJSONObject("cookie_jar")
            assertEquals("SID=yt; LOGIN_INFO=1", jar.getString("www.youtube.com"))
            assertEquals("player=1", jar.getString("vimeo.com"))
            assertFalse("cookies must not be written as a file field", j.has("cookie_file"))
        } finally {
            Net.cookies = prev
            Net.userAgent = prevUa
        }
    }

    @Test fun qualityLabelsAndDuration() {
        assertEquals("4K", qualityLabel(2160))
        assertEquals("1080p", qualityLabel(1080))
        assertEquals("720p", qualityLabel(720))
        assertEquals("—", qualityLabel(0))
        assertEquals("1:05", fmtDur(65))
        assertEquals("1:02:03", fmtDur(3723))
    }

    @Test fun foundBestQualityFlagDefaultsFalse() {
        val f = Found("https://x/v.mp4", "https://x/", "Bunny", Kind.DIRECT)
        assertFalse(f.bestQuality)
        assertEquals(0, f.height)
        val q = f.copy(bestQuality = true, height = 2160, duration = 596, site = "YouTube")
        assertTrue(q.bestQuality)
        assertEquals(2160, q.height)
        assertEquals("YouTube", q.site)
    }

    @Test fun qualityFilterKeeps4kAndPrefersOrder() {
        QualitySearch.filter.value = QualitySearch.Filter.UHD
        QualitySearch.items.value = listOf(
            Found("https://a/1", "https://a/1", "low", Kind.SERVER, height = 360, duration = 10, bestQuality = true),
            Found("https://a/2", "https://a/2", "uhd", Kind.SERVER, height = 2160, duration = 600, bestQuality = true),
            Found("https://a/3", "https://a/3", "hd", Kind.SERVER, height = 1080, duration = 400, bestQuality = true),
        )
        val only4k = QualitySearch.visible()
        assertEquals(1, only4k.size)
        assertEquals("uhd", only4k[0].title)
        QualitySearch.filter.value = QualitySearch.Filter.HD
        assertEquals(setOf("uhd", "hd"), QualitySearch.visible().map { it.title }.toSet())
        QualitySearch.filter.value = QualitySearch.Filter.ANY
        assertEquals(3, QualitySearch.visible().size)
        QualitySearch.items.value = emptyList()
    }
}
