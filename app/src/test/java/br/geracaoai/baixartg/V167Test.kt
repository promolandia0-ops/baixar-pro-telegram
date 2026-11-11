package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.Kind
import br.geracaoai.baixartg.web.PageVideos
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class V167Test {

    @After fun reset() { PageVideos.clear() }

    @Test fun detectsDirectAndHlsAndDashUrls() {
        assertTrue(PageVideos.isMediaRequest("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4"))
        assertTrue(PageVideos.isMediaRequest("https://cdn.example.com/hls/master.m3u8?token=1"))
        assertTrue(PageVideos.isMediaRequest("https://cdn.example.com/movie.webm"))
        assertTrue(PageVideos.isMediaRequest("https://cdn.example.com/stream.mpd"))
        assertFalse(PageVideos.isMediaRequest("https://cdn.example.com/seg/001.ts"))
        assertFalse(PageVideos.isMediaRequest("https://cdn.example.com/init.m4s"))
        assertFalse(PageVideos.isMediaRequest("https://rr2---sn-xxx.googlevideo.com/videoplayback?expire=1"))
        assertFalse(PageVideos.isMediaRequest("https://example.com/photo.jpg"))
        assertEquals(Kind.DIRECT, PageVideos.kindOf("https://x.com/a.mp4"))
        assertEquals(Kind.HLS, PageVideos.kindOf("https://x.com/a.m3u8"))
        assertEquals(Kind.SERVER, PageVideos.kindOf("https://x.com/a.mpd"))
    }

    @Test fun youtubePageIsYtdlpAndNotAMediaFile() {
        val yt = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        assertTrue(PageVideos.isYtdlpPage(yt))
        assertTrue(PageVideos.isYtdlpPage("https://youtu.be/aqz-KE-bpKQ"))
        assertTrue(PageVideos.isYtdlpPage("https://www.youtube.com/shorts/aqz-KE-bpKQ"))
        assertFalse(PageVideos.isMediaRequest(yt))
        assertEquals(Kind.SERVER, PageVideos.kindOf(yt))
        assertFalse(PageVideos.isYtdlpPage("https://test-videos.co.uk/bigbuckbunny/mp4-h264"))
    }

    @Test fun leakAndPiracyHostsAreBlocked() {
        assertTrue(PageVideos.blocked("https://leakgallery.com/video/1"))
        assertTrue(PageVideos.blocked("https://www.thepiratebay.org/torrent/1"))
        assertFalse(PageVideos.blocked("https://www.youtube.com/watch?v=1"))
        assertFalse(PageVideos.blocked("https://test-videos.co.uk/vids/x.mp4"))
        assertFalse(PageVideos.isMediaRequest("https://leakgallery.com/f/video.mp4"))
        PageVideos.considerPage("https://www.thepiratebay.org/torrent/1")
        assertTrue(PageVideos.hits.value.isEmpty())
    }

    @Test fun hookListensToPlayAndVideoTags() {
        val js = PageVideos.hookScript()
        assertTrue(js.contains("querySelectorAll('video')"))
        assertTrue(js.contains("addEventListener('play'"))
        assertTrue(js.contains("BaixarPage.onVideo"))
        assertTrue(js.contains("currentSrc"))
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("blob:"))
    }

    @Test fun foundUsesBestQualityAndKeepsPageAsReferer() {
        val h = PageVideos.Hit(
            "https://test-videos.co.uk/vids/a.mp4",
            "https://test-videos.co.uk/page",
            "Big Buck Bunny",
            Kind.DIRECT,
            played = true,
        )
        val f = PageVideos.toFound(h)
        assertTrue(f.bestQuality)
        assertEquals(Kind.DIRECT, f.kind)
        assertEquals("https://test-videos.co.uk/page", f.page)
        assertEquals("Big Buck Bunny", f.title)
        val yt = PageVideos.toFound(PageVideos.Hit("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://www.youtube.com/watch?v=aqz-KE-bpKQ", "BBB", Kind.SERVER))
        assertEquals(Kind.SERVER, yt.kind)
        assertTrue(yt.bestQuality)
    }

    @Test fun jsPlayOnBlobFallsBackToYtdlpPage() {
        PageVideos.resetIfNewPage("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "Big Buck Bunny")
        PageVideos.offerFromJs("blob:https://www.youtube.com/uuid", "Big Buck Bunny", true)
        val urls = PageVideos.hits.value.map { it.url }
        assertTrue(urls.any { it.contains("youtube.com/watch") })
        assertTrue(PageVideos.hits.value.any { it.played })
        assertFalse(urls.any { it.startsWith("blob:") })
    }

    @Test fun offerMediaOnTestVideosPageAndCountDedupes() {
        PageVideos.resetIfNewPage("https://test-videos.co.uk/bigbuckbunny/mp4-h264", "BBB")
        PageVideos.offerMedia("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4", "https://test-videos.co.uk/bigbuckbunny/mp4-h264", "BBB")
        PageVideos.offerMedia("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4", "https://test-videos.co.uk/bigbuckbunny/mp4-h264", "BBB")
        assertEquals(1, PageVideos.hits.value.size)
        PageVideos.offerMedia("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4")
        assertEquals(2, PageVideos.hits.value.size)
    }
}
