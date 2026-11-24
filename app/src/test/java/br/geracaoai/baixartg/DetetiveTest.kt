package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.DetKnowledge
import br.geracaoai.baixartg.core.DetScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetetiveTest {
    @Test fun enderecosEscondidos() {
        val js = """var c={"hls":{"url":"https:\/\/vod.akamaized.net\/exp=1~acl=x\/123\/master.m3u8?q=1"}}; x='//cdn.site.com/v/aula1.mp4';"""
        val h = DetScan.urlsIn(js, "https://site.com/curso/aula")
        assertTrue(h.any { it.t == DetScan.T.HLS && it.url == "https://vod.akamaized.net/exp=1~acl=x/123/master.m3u8?q=1" })
        assertTrue(h.any { it.url == "https://cdn.site.com/v/aula1.mp4" })
        assertTrue(h.none { it.url.startsWith("https://site.com/curso/x/") })
        val b = java.util.Base64.getEncoder().encodeToString("https://cdn.y.com/hls/aula/master.m3u8?t=9".toByteArray())
        assertTrue(DetScan.urlsIn("var s='$b';").any { it.t == DetScan.T.HLS })
    }

    @Test fun tipoPelosBytes() {
        assertEquals("hls", DetScan.sniff("#EXTM3U\n".toByteArray()))
        assertEquals("mp4", DetScan.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        assertEquals("html", DetScan.sniff("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(DetScan.hlsInfo("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"").drm)
    }

    @Test fun receitas() {
        assertTrue(DetKnowledge.leads("https://player-vz-7b6cf9e4-8bf.tv.pandavideo.com.br/embed/?v=abc-123")
            .any { it.url == "https://b-vz-7b6cf9e4-8bf.tv.pandavideo.com.br/abc-123/playlist.m3u8" })
        assertTrue(DetKnowledge.leads("https://player.vimeo.com/video/123456?h=abc123").any { it.url == "https://player.vimeo.com/video/123456/config?h=abc123" })
        assertTrue(DetKnowledge.leads("kWidget.embed({partner_id: 2503451, entry_id: '1_abcd1234'})").any { it.url.contains("entryId/1_abcd1234/") })
        assertTrue(DetKnowledge.isLoginUrl("https://curso.com/users/sign_in"))
    }
}
