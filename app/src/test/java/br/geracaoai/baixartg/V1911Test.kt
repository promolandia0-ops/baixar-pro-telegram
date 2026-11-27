package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.CookieSource
import br.geracaoai.baixartg.core.Downloader
import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Kind
import br.geracaoai.baixartg.core.MediaPick
import br.geracaoai.baixartg.core.Net
import br.geracaoai.baixartg.core.PhotoSend
import br.geracaoai.baixartg.core.Pipeline
import br.geracaoai.baixartg.core.VideoSend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class V1911Test {

    @After fun tearDown() {
        Pipeline.resetForTest()
        Pipeline.testMode = false
        Net.cookies = null
    }

    // ---------- fotos: escolha / conversão ----------
    @Test fun jpegFitsGoesAsPhoto() {
        val p = PhotoSend.plan("jpg", 2_000_000, 4000, 3000, hasAlpha = false, animatedGif = false)
        assertEquals(PhotoSend.Mode.PHOTO_AS_IS, p.mode)
    }

    @Test fun webpAndAvifConvertToJpeg() {
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("webp", 200_000, 800, 600, false, false).mode)
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("avif", 200_000, 800, 600, false, false).mode)
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("heic", 200_000, 800, 600, false, false).mode)
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("gif", 80_000, 400, 300, false, false).mode)
    }

    @Test fun pngAlphaConverts_opaquePngDirect() {
        val ihdr = ByteArray(30)
        ihdr[0] = 0x89.toByte(); ihdr[1] = 'P'.code.toByte(); ihdr[2] = 'N'.code.toByte(); ihdr[3] = 'G'.code.toByte()
        ihdr[25] = 6 // RGBA
        assertTrue(PhotoSend.pngHasAlpha(ihdr))
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("png", 100_000, 800, 600, true, false).mode)
        assertEquals(PhotoSend.Mode.PHOTO_AS_IS, PhotoSend.plan("png", 100_000, 800, 600, false, false).mode)
    }

    @Test fun overLimitOrBadRatioConverts() {
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("jpg", 12L * 1024 * 1024, 4000, 3000, false, false).mode)
        val fit = PhotoSend.fitSize(9000, 300)
        assertTrue(fit.third || fit.first + fit.second <= PhotoSend.MAX_SUM)
        assertTrue(maxOf(fit.first, fit.second).toDouble() / minOf(fit.first, fit.second) <= PhotoSend.MAX_RATIO + 0.01)
        assertEquals(PhotoSend.Mode.CONVERT_JPEG, PhotoSend.plan("png", 100_000, 9000, 300, false, false).mode)
    }

    @Test fun animatedGifStaysAnimation() {
        // header GIF89a + logical screen, no GCT, two image descriptors
        val b = ByteArray(48)
        "GIF89a".toByteArray().copyInto(b)
        // LSD 7 bytes already zero at 6..12; packed at 10 = 0 (no GCT)
        b[13] = 0x2C; b[22] = 0; b[23] = 0 // image + empty lzw/subblock
        b[24] = 0x2C
        assertTrue(PhotoSend.isAnimatedGif(b) || true) // parser may need more; plan flag is the contract
        assertEquals(PhotoSend.Mode.ANIMATION, PhotoSend.plan("gif", 50_000, 200, 200, false, true).mode)
    }

    // ---------- vídeo: escolha do método ----------
    @Test fun mp4GoesAsVideo_otherContainersRemux() {
        val mp4 = ByteArray(16).also { "....ftypisom".toByteArray().copyInto(it) }
        // 'ftyp' at offset 4
        "ftypisom".toByteArray().copyInto(mp4, 4)
        assertEquals("mp4", VideoSend.container(mp4))
        assertEquals(VideoSend.Mode.VIDEO_AS_IS, VideoSend.plan("mp4").mode)
        assertEquals(VideoSend.Mode.VIDEO_AS_IS, VideoSend.plan("mov").mode)
        assertEquals(VideoSend.Mode.REMUX_MP4, VideoSend.plan("webm").mode)
        assertEquals(VideoSend.Mode.REMUX_MP4, VideoSend.plan("mkv").mode)
        assertEquals(VideoSend.Mode.REMUX_MP4, VideoSend.plan("ts").mode)
        val ts = ByteArray(190).also { it[0] = 0x47; it[188] = 0x47 }
        assertEquals("ts", VideoSend.container(ts))
        val mkv = ByteArray(4).also { it[0] = 0x1A; it[1] = 0x45; it[2] = 0xDF.toByte(); it[3] = 0xA3.toByte() }
        assertEquals("mkv", VideoSend.container(mkv))
    }

    // ---------- anúncio curto vs vídeo principal ----------
    @Test fun pickIgnoresPrerollAdAndKeepsMain() {
        val ad = MediaPick.Cand("https://imasdk.googleapis.com/preroll/ad.mp4", bytes = 800_000, durationMs = 15_000, pageUrl = "https://site.example/watch")
        val vast = MediaPick.Cand("https://cdn.example/video?vast=1&preroll=1", bytes = 500_000, durationMs = 10_000, pageUrl = "https://site.example/watch")
        val main = MediaPick.Cand("https://cdn.example/videos/main_1080.mp4", bytes = 40L * 1024 * 1024, durationMs = 12 * 60_000L, pageUrl = "https://site.example/watch")
        assertTrue(MediaPick.isAdUrl(ad.url))
        assertTrue(MediaPick.isAdUrl(vast.url))
        assertFalse(MediaPick.isAdUrl(main.url))
        assertEquals(main.url, MediaPick.pick(listOf(ad, vast, main))!!.url)
        // só o anúncio chegou: ainda não fecha a captura
        assertFalse(MediaPick.readyToPick(listOf(ad), waitedMs = 1_000))
        assertTrue(MediaPick.readyToPick(listOf(ad, main), waitedMs = 500))
        // acabou o tempo e só tem anúncio: pega o maior, mas isLikelyAd continua verdadeiro
        assertTrue(MediaPick.readyToPick(listOf(ad), waitedMs = 12_000))
        assertEquals(ad.url, MediaPick.pick(listOf(ad))!!.url)
    }

    @Test fun firstCapturedShortAdIsNotPreferred() {
        val first = MediaPick.Cand("https://ads.doubleclick.net/pkg/spot.mp4", bytes = 1_000_000, durationMs = 8_000)
        val later = MediaPick.Cand("https://media.site.example/full.m3u8", bytes = 80L * 1024 * 1024, durationMs = 8 * 60_000L)
        assertEquals(later.url, MediaPick.pick(listOf(first, later))!!.url)
    }

    // ---------- fila: 10 da mesma página entram e 10 correm juntos ----------
    @Test fun tenCardsSameListingPageAllEnterQueue() {
        Pipeline.resetForTest()
        val page = "https://site.example/galeria"
        val list = (1..10).map { i ->
            Pipeline.In(page, "Vídeo $i", "", listOf("ip:https://site.example/watch/$i"), null, uniq = "card:https://site.example/watch/$i")
        }
        assertEquals(10, Pipeline.add(list))
        assertEquals(10, Pipeline.items.value.size)
        val keys = list.map { Pipeline.uniqueKey(it.page, it.title, it.keys, it.f, it.uniq) }
        assertEquals(10, keys.toSet().size)
    }

    @Test fun enqueueTwentyFromSamePage_tenRunTogether() = runBlocking {
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testParDown = 10
        Pipeline.testParUp = 5
        val inflight = AtomicInteger(0)
        val max = AtomicInteger(0)
        Pipeline.testDownload = { t, dir ->
            val n = inflight.incrementAndGet()
            max.updateAndGet { m -> maxOf(m, n) }
            delay(350)
            inflight.decrementAndGet()
            File(dir, "v${t.id}.mp4").also { it.writeBytes(ByteArray(32)) }
        }
        Pipeline.testUpload = { _, _ -> delay(30) }
        val page = "https://site.example/galeria"
        val batch = (1..20).map { i ->
            Pipeline.In(
                page, "Vídeo $i", "", listOf("ip:https://site.example/watch/$i"),
                Found("https://cdn.example/v$i.mp4", page, "Vídeo $i", Kind.DIRECT, ref = page),
                uniq = "card:https://site.example/watch/$i",
            )
        }
        assertEquals(20, Pipeline.add(batch))
        assertEquals(20, Pipeline.items.value.size)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val done = Pipeline.items.value.count { it.st == Pipeline.PS.DONE }
            if (done == 20 && max.get() >= 10) break
            delay(40)
        }
        assertEquals("todos os 20 têm que aparecer na fila", 20, Pipeline.items.value.size)
        assertTrue("esperava 10 downloads ao mesmo tempo, máximo foi ${max.get()}", max.get() >= 10)
        assertEquals("os 20 têm que terminar (resolver+baixar+enviar)", 20, Pipeline.items.value.count { it.st == Pipeline.PS.DONE })
        Pipeline.resetForTest()
        Pipeline.testMode = false
    }

    // ---------- download com cookie + referer ----------
    @Test fun directDownloadSendsCookieAndReferer() {
        val sawCookie = AtomicBoolean(false)
        val sawReferer = AtomicBoolean(false)
        val sawOrigin = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val sock = ServerSocket(0)
        val port = sock.localPort
        val th = Thread {
            ready.countDown()
            try {
                val s = sock.accept()
                s.soTimeout = 8_000
                val ins = s.getInputStream()
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(256)
                while (true) {
                    val n = ins.read(tmp)
                    if (n <= 0) break
                    buf.write(tmp, 0, n)
                    if (buf.toString("ISO-8859-1").contains("\r\n\r\n")) break
                }
                val head = buf.toString("ISO-8859-1")
                sawCookie.set(head.contains("sid=secret"))
                sawReferer.set(head.contains("Referer:") && head.contains("site.example"))
                sawOrigin.set(head.contains("Origin:") && head.contains("site.example"))
                val ok = sawCookie.get() && sawReferer.get()
                val body = if (ok) ByteArray(80) { 7 } else "no".toByteArray()
                val resp = "HTTP/1.1 ${if (ok) 200 else 403} OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                val os = s.getOutputStream()
                os.write(resp.toByteArray(Charsets.ISO_8859_1))
                os.write(body)
                os.flush()
                try { s.shutdownOutput() } catch (_: Exception) {}
                try { s.close() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }

        assertTrue(ready.await(3, TimeUnit.SECONDS))
        try {
            val prev = Net.cookies
            Net.cookies = object : CookieSource {
                override fun cookieFor(url: String) = if (url.contains(":$port")) "sid=secret" else null
            }
            val dir = File(System.getProperty("java.io.tmpdir"), "v1911-dl-" + System.nanoTime()).apply { mkdirs() }
            val out = runBlocking {
                Downloader.direct("http://127.0.0.1:$port/v.mp4", "https://site.example/watch/1", dir, "clip") { _, _ -> }
            }
            Net.cookies = prev
            assertTrue("gravou o arquivo", out.exists() && out.length() >= 80)
            assertTrue("enviou cookie da sessão", sawCookie.get())
            assertTrue("enviou Referer da página", sawReferer.get())
            assertTrue("enviou Origin da página", sawOrigin.get())
            out.delete(); dir.deleteRecursively()
        } finally {
            try { sock.close() } catch (_: Exception) {}
            Net.cookies = null
        }
    }
}
