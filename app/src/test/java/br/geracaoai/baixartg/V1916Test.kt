package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class V1916Test {
    @After fun cleanup() {
        Pipeline.resetForTest()
        Pipeline.testMode = false
        Sent.onClear = null
        Pipeline.onCleared = null
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

    @Test fun queuedWithoutConfirmedSend_doesNotHide_andReappearsAfterClearAll() {
        val dir = Files.createTempDirectory("v1916_q").toFile()
        Sent.init(dir); Sent.clear()
        val f = Found("https://cdn.example/v/a.mp4", "https://site.example/v/1", "Aula 1", Kind.DIRECT)
        val keys = Sent.keysOf(f) + listOf("ip:https://site.example/v/1", "pg:site.example/v/1")
        Sent.markQueued(keys)
        assertTrue(Sent.isQueued(keys))
        // só "já pedido", sem envio confirmado e sem item ativo na fila → não esconde
        assertEquals(BrowserHide.Reason.NONE, Pipeline.hideReason(f, keys))
        assertFalse(BrowserHide.shouldHide(Pipeline.hideReason(f, keys)))

        var cleared = false
        Sent.onClear = { cleared = true }
        Pipeline.onCleared = { }
        Pipeline.resetForTest(); Pipeline.testMode = true
        Pipeline.clearAll(alsoSentMemory = true)
        assertTrue(cleared)
        assertEquals(0, Sent.size)
        assertFalse(Sent.isQueued(keys))
        assertEquals(BrowserHide.Reason.NONE, Pipeline.hideReason(f, keys))
    }

    @Test fun failAndSkipDoNotHide_doneConfirmedDoes() = runBlocking {
        val dir = Files.createTempDirectory("v1916_st").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "x_${t.id}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        Pipeline.testUpload = { _, _ -> }

        val failF = Found("https://cdn.example/v/fail.mp4", "https://site.example/v/fail", "Fail", Kind.DIRECT)
        Pipeline.testResolve = { t -> if (t.title == "Fail") null else Found("https://cdn.example/v/${t.title}.mp4", t.page, t.title, Kind.DIRECT) }
        // item sem Found resolve e falha
        Pipeline.add(listOf(Pipeline.In("https://site.example/v/fail", "Fail", "", listOf("ip:https://site.example/v/fail"), null, uniq = "fail")))
        waitPipeline()
        val failed = Pipeline.items.value.first { it.title == "Fail" }
        assertEquals(Pipeline.PS.FAIL, failed.st)
        val failKeys = listOf("ip:https://site.example/v/fail", "pg:site.example/v/fail")
        val failReason = Pipeline.hideReason(failF, failKeys)
        assertFalse("FAIL não pode esconder: $failReason", BrowserHide.shouldHide(failReason))
        assertEquals(BrowserHide.Reason.FAILED_BEFORE, failReason)
        assertEquals("falhou antes", BrowserHide.badge(failReason))

        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "y_${t.id}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        Pipeline.testUpload = { _, _ -> }
        val skipF = Found("https://cdn.example/v/skip.mp4", "https://site.example/v/skip", "Skip", Kind.DIRECT)
        Sent.markSent(skipF)
        Pipeline.add(listOf(Pipeline.In("https://site.example/v/skip", "Skip", "", emptyList(), skipF, uniq = "skip")))
        waitPipeline()
        val skipped = Pipeline.items.value.first { it.title == "Skip" }
        assertEquals(Pipeline.PS.SKIP, skipped.st)
        // envio já tinha sido confirmado antes (markSent) → continua escondido como SENT, não como SKIP
        assertEquals(BrowserHide.Reason.SENT, Pipeline.hideReason(skipF, Sent.keysOf(skipF)))
        assertTrue(BrowserHide.shouldHide(Pipeline.hideReason(skipF, Sent.keysOf(skipF))))

        // SKIP sem markSent: marca como pulado na fila e solta o queued; não esconde
        Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testDownload = { t, d -> File(d, "z_${t.id}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        Pipeline.testUpload = { _, _ -> }
        val a = Found("https://cdn.example/hls/x/master.m3u8", "https://site.example/v/a", "A", Kind.HLS)
        val a2 = Found("https://cdn.example/hls/x/720p.m3u8", "https://site.example/v/a", "A2", Kind.HLS)
        // mesmo vídeo, segundo vira SKIP por claimed; sem markSent prévio
        Pipeline.testParDown = 1
        Pipeline.add(listOf(
            Pipeline.In("https://site.example/v/a", "A", "", emptyList(), a, uniq = "a1"),
            Pipeline.In("https://site.example/v/a", "A2", "", emptyList(), a2, uniq = "a2"),
        ))
        waitPipeline()
        val after = Pipeline.items.value
        val skipOnly = after.filter { it.st == Pipeline.PS.SKIP }
        // o que foi pulado como variante do mesmo item não pode esconder o card
        if (skipOnly.isNotEmpty()) {
            val sk = skipOnly.first()
            assertFalse(Sent.isQueued(Pipeline.identityKeys(sk)))
            assertFalse(BrowserHide.shouldHide(Pipeline.hideReason(sk.f, Pipeline.identityKeys(sk))))
        }

        // DONE confirmado esconde
        val done = after.firstOrNull { it.st == Pipeline.PS.DONE }
        assertNotNull(done)
        assertTrue(Pipeline.confirmedSentFor(done!!.f, Pipeline.identityKeys(done)))
        assertEquals(BrowserHide.Reason.SENT, Pipeline.hideReason(done.f, Pipeline.identityKeys(done)))
        assertTrue(BrowserHide.shouldHide(Pipeline.hideReason(done.f, Pipeline.identityKeys(done))))
    }

    @Test fun activeInQueueHides_untilCleared() = runBlocking {
        val dir = Files.createTempDirectory("v1916_act").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.pause()
        val f = Found("https://cdn.example/v/live.mp4", "https://site.example/v/live", "Live", Kind.DIRECT)
        Pipeline.add(listOf(Pipeline.In("https://site.example/v/live", "Live", "", listOf("ip:https://site.example/v/live"), f, uniq = "live")))
        val keys = listOf("ip:https://site.example/v/live") + Sent.keysOf(f)
        assertTrue(Pipeline.isActiveFor(keys))
        assertEquals(BrowserHide.Reason.ACTIVE, Pipeline.hideReason(f, keys))
        assertTrue(BrowserHide.shouldHide(Pipeline.hideReason(f, keys)))

        Pipeline.clearQueue()
        assertFalse(Pipeline.isActiveFor(keys))
        assertFalse(BrowserHide.shouldHide(Pipeline.hideReason(f, keys)))
    }

    @Test fun loadingTimeoutIsSixtySeconds() {
        assertEquals(60_000L, BrowserHide.LOADING_TIMEOUT_MS)
        assertFalse(BrowserHide.loadingTimedOut(startedAt = 1_000L, now = 60_999L))
        assertTrue(BrowserHide.loadingTimedOut(startedAt = 1_000L, now = 61_000L))
        assertFalse(BrowserHide.loadingTimedOut(startedAt = 0L, now = 100_000L))
    }

    @Test fun androidBackGoesWebViewThenPreviousTab() {
        assertEquals(BrowserNav.BackAction.WEBVIEW, BrowserNav.onBack(true))
        assertEquals(BrowserNav.BackAction.PREVIOUS_TAB, BrowserNav.onBack(false))
    }

    @Test fun forceSendIgnoresConfirmedSent() = runBlocking {
        val dir = Files.createTempDirectory("v1916_force").toFile()
        Sent.init(dir); Sent.clear()
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testMarkSent = true
        Pipeline.testDownload = { t, d -> File(d, "f_${t.id}.mp4").apply { writeBytes(byteArrayOf(9, 9, 9)) } }
        Pipeline.testUpload = { _, _ -> }
        val f = Found("https://cdn.example/v/again.mp4", "https://site.example/v/again", "Again", Kind.DIRECT)
        Sent.markSent(f)
        Pipeline.add(listOf(Pipeline.In("https://site.example/v/again", "Again", "", emptyList(), f, uniq = "ag", force = true)))
        waitPipeline()
        assertEquals(Pipeline.PS.DONE, Pipeline.items.value.first().st)
    }
}
