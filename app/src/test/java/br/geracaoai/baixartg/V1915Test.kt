package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V1915Test {
    @After fun cleanup() {
        Pipeline.resetForTest()
        Pipeline.testMode = false
        Sent.onClear = null
        Sent.clear()
        VisualHash.clear()
    }

    private fun waitPipeline(timeoutMs: Long = 10_000) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val all = Pipeline.items.value
            if (all.isNotEmpty() && all.all { Pipeline.isFinal(it.st) }) break
            delay(20)
        }
    }

    @Test fun sevenCardsWithMasterVariantsSegmentsPreviewThumb_showsExactlySeven() {
        val cardsHtml = (1..7).joinToString("\n") { i ->
            """
            <article class="video-card">
              <a href="/video/$i">
                <img src="https://cdn.example/thumbs/card_$i.jpg" alt="Aula $i">
                <h3>Aula $i</h3>
              </a>
              <a href="/set/999" class="set-link">Set $i</a>
              <video muted loop src="https://cdn.example/previews/card_${i}_preview.mp4"></video>
              <script>
                var m$i = "https://cdn.example/hls/vid$i/master.m3u8?q=auto&cdn=1";
                var v1_$i = "https://cdn.example/hls/vid$i/360p/index.m3u8?q=360&bitrate=800";
                var v2_$i = "https://cdn.example/hls/vid$i/720p/index.m3u8?q=720&bitrate=2500";
                var v3_$i = "https://cdn.example/hls/vid$i/1080p.m3u8?q=1080&token=abc";
                var seg1_$i = "https://cdn.example/hls/vid$i/720p/seg-001.ts";
                var seg2_$i = "https://cdn.example/hls/vid$i/720p/chunk-002.m4s";
                var init_$i = "https://cdn.example/hls/vid$i/720p/init.mp4";
              </script>
            </article>
            """.trimIndent()
        }
        val html = "<html><body><div class=\"videos-grid\">$cardsHtml</div></body></html>"
        val items = BrowserGrouping.extractCardVideos("https://site.example/galeria", html)

        assertEquals("esperado exatamente 7 itens, veio ${items.map { it.url }}", 7, items.size)
        assertTrue(items.all { it.url.contains("master.m3u8") })
        assertFalse(items.any { Extract.isSegmentUrl(it.url) })
        assertFalse(items.any { Extract.isPreviewVideo(it.url) })
        assertFalse(items.any { Extract.isPhotoUrl(it.url) })
        assertTrue(BrowserGrouping.isNonVideoNavLink("https://site.example/set/999"))
    }

    @Test fun sevenCardsVariantsInSameSelectionMergeInsteadOfSkipping() = runBlocking {
        val dir = Files.createTempDirectory("v1915_merge").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "v_${t.id}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        Pipeline.testUpload = { _, _ -> }

        // Card 1 traz master + 2 variantes na mesma seleção; cards 2..7 trazem 1 vídeo cada
        val batch = listOf(
            Pipeline.In("https://site.example/v/1", "Video 1", "", emptyList(),
                Found("https://cdn.example/hls/v1/360p.m3u8?q=360", "https://site.example/v/1", "Video 1", Kind.HLS), uniq = "v1_360"),
            Pipeline.In("https://site.example/v/1", "Video 1", "", emptyList(),
                Found("https://cdn.example/hls/v1/master.m3u8", "https://site.example/v/1", "Video 1", Kind.HLS), uniq = "v1_master"),
            Pipeline.In("https://site.example/v/1", "Video 1", "", emptyList(),
                Found("https://cdn.example/hls/v1/720p/index.m3u8?q=720", "https://site.example/v/1", "Video 1", Kind.HLS), uniq = "v1_720"),
        ) + (2..7).map { i ->
            Pipeline.In("https://site.example/v/$i", "Video $i", "", emptyList(),
                Found("https://cdn.example/hls/v$i/master.m3u8", "https://site.example/v/$i", "Video $i", Kind.HLS), uniq = "v$i")
        }

        val added = Pipeline.add(batch)
        assertEquals(7, added)
        waitPipeline()

        val finalAll = Pipeline.items.value
        assertEquals(7, finalAll.size)
        assertEquals(0, finalAll.count { it.st == Pipeline.PS.SKIP })
        assertEquals(7, finalAll.count { it.st == Pipeline.PS.DONE })
        assertTrue(finalAll.first().f!!.url.contains("master.m3u8"))
    }

    @Test fun downloadSevenAfterClearHistory_zeroRepeats() = runBlocking {
        val dir = Files.createTempDirectory("v1915_clear").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "v_${t.id}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        Pipeline.testUpload = { _, _ -> }

        val seven = (1..7).map { i ->
            Pipeline.In("https://site.example/v/$i", "Video $i", "", listOf("ip:https://site.example/v/$i"),
                Found("https://cdn.example/hls/vid$i/master.m3u8?q=720&cdn=2", "https://site.example/v/$i", "Video $i", Kind.HLS), uniq = "card:$i")
        }

        // 1) Envia os 7 pela primeira vez
        Pipeline.add(seven)
        waitPipeline()
        assertEquals(7, Pipeline.items.value.count { it.st == Pipeline.PS.DONE })
        assertTrue(Sent.size > 0)

        // 2) Limpa tudo (histórico + memória de repetidos)
        var clearedCallback = false
        Sent.onClear = { clearedCallback = true }
        Pipeline.clearAll(alsoSentMemory = true)
        assertTrue(clearedCallback)
        assertEquals(0, Sent.size)

        // 3) Baixa os 7 novamente -> 0 repetidos
        Pipeline.add(seven)
        waitPipeline()
        val after = Pipeline.items.value
        assertEquals(7, after.size)
        assertEquals("não deve haver repetidos após limpar", 0, after.count { it.st == Pipeline.PS.SKIP })
        assertEquals(7, after.count { it.st == Pipeline.PS.DONE })
    }

    @Test fun twoDifferentCardsWithQueryIdStreams_doNotCollide() = runBlocking {
        val dir = Files.createTempDirectory("v1915_idq").toFile()
        Sent.init(dir); Sent.clear()

        val f1 = Found("https://cdn.example/stream.m3u8?id=1", "https://site.example/galeria#1", "Card 1", Kind.HLS)
        val f2 = Found("https://cdn.example/stream.m3u8?id=2", "https://site.example/galeria#2", "Card 2", Kind.HLS)

        assertTrue(MediaIdentity.keys(f1).intersect(MediaIdentity.keys(f2).toSet()).isEmpty())
        assertTrue(Sent.keysOf(f1).intersect(Sent.keysOf(f2).toSet()).isEmpty())

        Sent.markSent(f1)
        assertTrue(Sent.isSent(f1))
        assertFalse(Sent.isSent(f2))
        assertFalse(Sent.hasCanonical(f2))

        Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "s_${t.id}.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) } }
        Pipeline.testUpload = { _, _ -> }

        Pipeline.add(listOf(
            Pipeline.In("https://site.example/galeria", "Card 1", "", emptyList(), f1, uniq = "card:1"),
            Pipeline.In("https://site.example/galeria", "Card 2", "", emptyList(), f2, uniq = "card:2"),
        ))
        waitPipeline()
        val res = Pipeline.items.value
        assertEquals(2, res.size)
        assertEquals(0, res.count { it.st == Pipeline.PS.SKIP })
        assertEquals(2, res.count { it.st == Pipeline.PS.DONE })
    }

    @Test fun forceSendAndForceSendAllSkipped_ignoreRepeatedCheck() = runBlocking {
        val dir = Files.createTempDirectory("v1915_force").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "f_${t.id}.mp4").apply { writeBytes(byteArrayOf(7, 8, 9)) } }
        Pipeline.testUpload = { _, _ -> }

        val f1 = Found("https://cdn.example/v/alpha.mp4", "https://site.example/v/1", "Alpha", Kind.DIRECT)
        val f2 = Found("https://cdn.example/v/beta.mp4", "https://site.example/v/2", "Beta", Kind.DIRECT)
        Sent.markSent(f1)
        Sent.markSent(f2)

        Pipeline.add(listOf(
            Pipeline.In("https://site.example/v/1", "Alpha", "", emptyList(), f1, uniq = "a"),
            Pipeline.In("https://site.example/v/2", "Beta", "", emptyList(), f2, uniq = "b"),
        ))
        waitPipeline()
        assertEquals(2, Pipeline.items.value.count { it.st == Pipeline.PS.SKIP })

        // Individual: "Baixar mesmo assim" no primeiro
        val id1 = Pipeline.items.value.first { it.title == "Alpha" }.id
        Pipeline.forceSend(id1)
        waitPipeline()
        assertEquals(Pipeline.PS.DONE, Pipeline.items.value.first { it.id == id1 }.st)
        assertEquals(1, Pipeline.items.value.count { it.st == Pipeline.PS.SKIP })

        // Resumo: "Baixar repetidos mesmo assim" no restante
        assertEquals(1, Pipeline.forceSendAllSkipped())
        waitPipeline()
        assertEquals(0, Pipeline.items.value.count { it.st == Pipeline.PS.SKIP })
        assertEquals(2, Pipeline.items.value.count { it.st == Pipeline.PS.DONE })
    }
}
