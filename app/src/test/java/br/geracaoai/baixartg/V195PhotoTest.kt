package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.PhotoGrab
import br.geracaoai.baixartg.core.Sent
import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Kind
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class V195PhotoTest {
    @Test fun dumpsExactScript() { File("/tmp/grab_app.js").writeText(PhotoGrab.JS); assertTrue(PhotoGrab.JS.contains("VER='g2'")) }

    @Test fun parsesWebViewQuotedJson() {
        val inner = """{"v":"g1","n":3,"raw":3,"p":[{"u":"https://a.com/x.jpg","w":1200,"h":800,"th":"https://a.com/x-300.jpg","t":"carro","s":"img","o":0}],"r":{"pequena":2}}"""
        val quoted = org.json.JSONObject.quote(inner)   // evaluateJavascript devolve a string JSON entre aspas
        for (raw in listOf(inner, quoted)) {
            val r = PhotoGrab.parse(raw)
            assertTrue(r.ok); assertEquals(1, r.cands.size); assertEquals(1200, r.cands[0].w); assertEquals(2, r.rejects["pequena"]); assertEquals(3, r.imgs)
        }
        assertFalse(PhotoGrab.parse(null).ok); assertFalse(PhotoGrab.parse("null").ok)
        assertTrue(PhotoGrab.parse(org.json.JSONObject.quote("""{"v":"g1","same":1,"n":5}""")).same)
    }

    @Test fun keysNeverCollide() {
        val us = listOf("https://www.google.com/images?q=tbn:AAA", "https://www.google.com/images?q=tbn:BBB",
            "https://x.com/photo.php?id=1", "https://x.com/photo.php?id=2", "https://cdn.x.com/a/123.jpg", "https://cdn.x.com/b/123.jpg",
            "https://img.x.com/p.jpg?w=400", "https://img.x.com/p.jpg?w=2000")
        assertEquals(us.size, us.map { PhotoGrab.key(it) }.toSet().size)
        assertEquals(PhotoGrab.key("http://WWW.A.com/x.jpg#f"), PhotoGrab.key("https://a.com/x.jpg"))
        assertTrue(PhotoGrab.key("https://a.com/x.jpg").startsWith("pu:"))
    }

    @Test fun videoKeysUnchanged() {
        val f = Found("https://cdn.site.com/videos/abc123.mp4", "https://site.com/v/abc123", "t", Kind.DIRECT)
        assertEquals(listOf("v:cdn.site.com/videos/abc123.mp4", "c:" + br.geracaoai.baixartg.core.MediaIdentity.canonicalMedia(f.url), "s:abc123").take(2), Sent.keysOf(f).take(2))
    }

    @Test fun selectDropsJunkKeepsPhotos() {
        fun c(u: String, w: Int = 0, h: Int = 0, o: Boolean = false) = PhotoGrab.Cand(u, w, h, "", "", "img", o)
        val got = PhotoGrab.select(listOf(
            c("https://images.unsplash.com/profile-123image?w=32&h=32"), c("https://images.pexels.com/photos/1/p.jpeg?h=40&w=40&fit=crop"),
            c("https://www.bing.com/rp/abc.png"), c("https://pt.wikipedia.org/static/images/donate/donate.gif"),
            c("https://images.pexels.com/photos/2/p.jpeg?auto=compress&w=1260", 1260, 840),
            c("https://thumb.wikimedia.org/wikipedia/commons/thumb/4/45/Fusca.JPG/500px-Fusca.JPG"),
            c("https://plus.unsplash.com/premium_photo-1?w=2000", 2000, 1500)))
        assertEquals(listOf("https://images.pexels.com/photos/2/p.jpeg?auto=compress&w=1260", "https://upload.wikimedia.org/wikipedia/commons/4/45/Fusca.JPG",
            "https://plus.unsplash.com/premium_photo-1?w=2000"), got.map { it.url })
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/4/45/Fusca.JPG/1280px-Fusca.JPG", PhotoGrab.fallback("https://upload.wikimedia.org/wikipedia/commons/4/45/Fusca.JPG"))
    }

    @Test fun contentSniffAndTelegramFallback() {
        assertEquals("jpg", PhotoGrab.imageExt(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("webp", PhotoGrab.imageExt("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("avif", PhotoGrab.imageExt("\u0000\u0000\u0000\u001cftypavif".toByteArray(Charsets.ISO_8859_1)))
        assertNull(PhotoGrab.imageExt("<!doctype html>".toByteArray()))
        assertTrue(PhotoGrab.sendAsPhoto("jpg", 2_000_000, 4000, 3000))
        assertFalse(PhotoGrab.sendAsPhoto("jpg", 12L * 1024 * 1024, 4000, 3000))
        assertFalse(PhotoGrab.sendAsPhoto("avif", 100_000, 1000, 800))
        assertFalse(PhotoGrab.sendAsPhoto("png", 100_000, 9000, 300))
    }

    /** Saídas REAIS do script rodado no Chrome (celular 412×915) — decodificadas e filtradas pelo código do app. */
    @Test fun realSiteOutputs() {
        val dir = File("/workspace/phototest/app_raw"); if (!dir.exists()) return
        val sb = StringBuilder()
        dir.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }.forEach { f ->
            val r = PhotoGrab.parse(org.json.JSONObject.quote(f.readText()))
            sb.append(f.nameWithoutExtension).append('\t').append(r.cands.size).append('\t').append(PhotoGrab.select(r.cands).size).append('\n')
        }
        File("/workspace/phototest/kotlin_select.tsv").writeText(sb.toString())
    }
}
