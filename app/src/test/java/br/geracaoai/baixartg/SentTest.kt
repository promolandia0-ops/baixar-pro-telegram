package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SentTest {
    @Test fun norm() {
        assertEquals("site.com/videos/a b", Sent.norm("https://www.Site.com/videos/a%20b/?utm=1#x"))
        assertEquals(Sent.norm("http://site.com/v/1"), Sent.norm("https://www.site.com/v/1/"))
        assertEquals(Sent.norm("https://cdn.x.com/a.mp4?token=1&ex=2"), Sent.norm("https://cdn.x.com/a.mp4?token=9&ex=8"))
        assertNotEquals(Sent.norm("https://s.com/v/1"), Sent.norm("https://s.com/v/2"))
    }
    @Test fun bunkr() {
        val a = Sent.bunkrKeys("https://c6no5-b.cdn.cr/storage/media/aab83954-b649-44eb-945a-51b0b6b8e26c.mp4?token=x")
        val b = Sent.bunkrKeys("https://c9xyz.cdn.cr/storage/media/AAB83954-b649-44eb-945a-51b0b6b8e26c.mp4")
        assertEquals(listOf("bk:aab83954-b649-44eb-945a-51b0b6b8e26c"), a); assertEquals(a, b)
        assertEquals(listOf("bf:Ik6Multt8LnnA"), Sent.bunkrKeys("https://bunkr.si/f/Ik6Multt8LnnA"))
    }
    @Test fun roundTrip() {
        val dir = Files.createTempDirectory("sent").toFile(); Sent.init(dir); Sent.clear()
        val bk = Found("https://c6no5-b.cdn.cr/storage/media/aab83954-b649-44eb-945a-51b0b6b8e26c.mp4", "https://bunkr.cr/f/Ik6Multt8LnnA", "x.mp4", Kind.DIRECT)
        val hls = Found("https://vazounudes.net/hls/6eb9322c-7122-44c1-89c7-daa60fb04b9b/playlist.m3u8", "https://xvideosputaria.com/elisa/", "Elisa", Kind.HLS)
        val other = Found("https://vazounudes.net/hls/aaaa/playlist.m3u8", "https://xvideosputaria.com/outra/", "Outra", Kind.HLS)
        val same = Found("https://site.com/watch/1", "https://site.com/watch/1", "t", Kind.SERVER)
        // legendas no formato do app (v1.0–1.3): título\npágina\n[vídeo]
        Sent.importCaptions(listOf(
            "x.mp4\nhttps://bunkr.cr/f/Ik6Multt8LnnA",                                   // Bunkr 1.1+ (sem CDN)
            "Elisa\nhttps://xvideosputaria.com/elisa/\nhttps://vazounudes.net/hls/6eb9322c-7122-44c1-89c7-daa60fb04b9b/playlist.m3u8",
            "t\nhttps://www.site.com/watch/1/"))
        assertTrue(Sent.isSent(bk)); assertTrue(Sent.isSent(hls)); assertTrue(Sent.isSent(same)); assertFalse(Sent.isSent(other))
        // Bunkr 1.0 (legenda com o CDN) e outro domínio do Bunkr
        Sent.clear(); Sent.importCaptions(listOf("x\nhttps://bunkr.cr/f/Zzz\nhttps://c6no5-b.cdn.cr/storage/media/aab83954-b649-44eb-945a-51b0b6b8e26c.mp4"))
        assertTrue(Sent.isSent(bk.copy(page = "https://bunkr.si/f/Outro")))
        // persistência
        Sent.clear(); Sent.markSent(other); Sent.init(dir); assertTrue(Sent.isSent(other)); assertFalse(Sent.isSent(hls))
        Sent.clear(); Sent.init(dir); assertEquals(0, Sent.size)
    }
}
