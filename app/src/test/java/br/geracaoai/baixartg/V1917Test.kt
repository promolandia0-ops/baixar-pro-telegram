package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.concurrent.thread

class V1917Test {
    private var prevCookies: CookieSource? = null

    @Before fun setup() {
        prevCookies = Net.cookies
        MseCapture.clearForTest()
        SiteHash.initForTest("v1917-salt")
    }

    @After fun teardown() {
        Net.cookies = prevCookies
        MseCapture.clearForTest()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val b = ByteBuffer.allocate(total)
        b.putInt(total)
        b.put(type.toByteArray(Charsets.ISO_8859_1), 0, 4)
        b.put(payload)
        return b.array()
    }

    private fun fakeInit(kind: String = "vide"): ByteArray {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isomiso6mp41".toByteArray(Charsets.ISO_8859_1))
        val hdlrPayload = ByteArray(24).also { arr ->
            "hdlr".toByteArray(Charsets.ISO_8859_1)
            val k = kind.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(k, 0, arr, 8, 4)
        }
        val hdlr = box("hdlr", hdlrPayload)
        val moov = box("moov", hdlr)
        return ftyp + moov
    }

    private fun fakeFrag(sizeBytes: Int = 1024): ByteArray {
        val moof = box("moof", ByteArray(32) { 1 })
        val mdat = box("mdat", ByteArray(sizeBytes) { 7 })
        return moof + mdat
    }

    private class MemCookies : CookieSource {
        private val map = HashMap<String, String>()
        override fun cookieFor(url: String): String? = map[Extract.host(url)]
        override fun store(url: String, setCookie: String) {
            val pair = setCookie.substringBefore(';').trim()
            if (pair.contains('=')) {
                val h = Extract.host(url)
                val cur = map[h]
                map[h] = if (cur.isNullOrBlank()) pair else "$cur; $pair"
            }
        }
    }

    @Test fun dashWithJsCookieAndSpecialHeaderProducesValidMp4(): Unit = runBlocking {
        val mem = MemCookies()
        Net.cookies = mem
        val pagePath = "/watch/42"
        val mpdPath = "/media/stream.mpd"
        val vInitBytes = fakeInit("vide")
        val vSeg1Bytes = fakeFrag(2048)
        val vSeg2Bytes = fakeFrag(2048)
        val aInitBytes = fakeInit("soun")
        val aSeg1Bytes = fakeFrag(512)
        val lowRequested = java.util.concurrent.atomic.AtomicBoolean(false)

        val srv = ServerSocket(0)
        val port = srv.localPort
        val baseUrl = "http://127.0.0.1:$port"
        val th = thread(isDaemon = true) {
            while (!srv.isClosed) {
                val sock = try { srv.accept() } catch (_: Exception) { break }
                try {
                    val reader = sock.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: continue
                    val path = first.split(' ').getOrNull(1).orEmpty()
                    val hdrs = HashMap<String, String>()
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) hdrs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val out = sock.getOutputStream()
                    fun send(code: String, ct: String, body: ByteArray, extraHdr: String = "") {
                        val h = "HTTP/1.1 $code\r\nContent-Type: $ct\r\nContent-Length: ${body.size}\r\n${extraHdr}Connection: close\r\n\r\n"
                        out.write(h.toByteArray())
                        out.write(body)
                        out.flush()
                    }
                    val hasToken = hdrs["x-player-token"] == "secret-xyz-123"
                    val hasCookie = hdrs["cookie"]?.contains("js_ok=1") == true
                    when {
                        path == pagePath -> {
                            send("200 OK", "text/html", "<html><video></video><script>document.cookie='js_ok=1; path=/';</script></html>".toByteArray(), "Set-Cookie: js_ok=1; Path=/\r\n")
                        }
                        !hasToken || !hasCookie -> {
                            send("403 Forbidden", "text/plain", "forbidden".toByteArray())
                        }
                        path == mpdPath -> {
                            val mpd = """
                                <MPD mediaPresentationDuration="PT10S">
                                  <Period>
                                    <AdaptationSet contentType="video" mimeType="video/mp4">
                                      <Representation id="480" bandwidth="500000" width="854" height="480">
                                        <SegmentTemplate initialization="/v/480/init.mp4" media="/v/480/seg-${'$'}Number%02d${'$'}.m4s" startNumber="1" duration="5" timescale="1"/>
                                      </Representation>
                                      <Representation id="1080" bandwidth="3000000" width="1920" height="1080">
                                        <SegmentTemplate initialization="/v/1080/init.mp4" media="/v/1080/seg-${'$'}Number%02d${'$'}.m4s" startNumber="1" duration="5" timescale="1"/>
                                      </Representation>
                                    </AdaptationSet>
                                    <AdaptationSet contentType="audio" mimeType="audio/mp4" codecs="mp4a.40.2">
                                      <Representation id="a1" bandwidth="128000">
                                        <SegmentTemplate initialization="/a/init.mp4" media="/a/seg-${'$'}Number${'$'}.m4s" startNumber="1" duration="10" timescale="1"/>
                                      </Representation>
                                    </AdaptationSet>
                                  </Period>
                                </MPD>
                            """.trimIndent()
                            send("200 OK", "application/dash+xml", mpd.toByteArray())
                        }
                        path.startsWith("/v/480/") -> { lowRequested.set(true); send("200 OK", "video/mp4", vSeg1Bytes) }
                        path == "/v/1080/init.mp4" -> send("200 OK", "video/mp4", vInitBytes)
                        path == "/v/1080/seg-01.m4s" -> send("200 OK", "video/mp4", vSeg1Bytes)
                        path == "/v/1080/seg-02.m4s" -> send("200 OK", "video/mp4", vSeg2Bytes)
                        path == "/a/init.mp4" -> send("200 OK", "audio/mp4", aInitBytes)
                        path == "/a/seg-1.m4s" -> send("200 OK", "audio/mp4", aSeg1Bytes)
                        else -> send("404 Not Found", "text/plain", ByteArray(0))
                    }
                } catch (_: Exception) {}
                finally { try { sock.close() } catch (_: Exception) {} }
            }
        }
        try {
            val pageUrl = "$baseUrl$pagePath"
            val mpdUrl = "$baseUrl$mpdPath"
            // Simula desafio JS definindo cookie + script do player fazendo fetch com X-Player-Token
            mem.store(pageUrl, "js_ok=1; Path=/")
            MseCapture.recordMse(pageUrl, "sb_1", "video/mp4; codecs=\"avc1.42E01E\"")
            MseCapture.ingestJsReqLog(
                pageUrl,
                """[{"u":"$mpdUrl","m":"GET","h":{"X-Player-Token":"secret-xyz-123"},"ct":"application/dash+xml","src":"fetch"}]""",
            )
            val snap = MseCapture.snapshot(pageUrl)
            assertTrue(snap.hasMse)
            assertTrue(snap.hasSpecialHeader)
            assertEquals(1, snap.manifests.size)
            assertTrue(MseCapture.shouldUseBrowserFirst(Found(pageUrl, pageUrl, "Aula", Kind.SERVER)))

            val dir = Files.createTempDirectory("v1917_dash").toFile()
            var muxCalled = false
            val outFile = MseDownloader.downloadDash(
                mpdReq = snap.manifests.first(),
                referer = pageUrl,
                dir = dir,
                title = "Aula MSE",
                progress = { _, _ -> },
                muxFn = { v, a, out ->
                    muxCalled = true
                    assertTrue("video fMP4 deve ser válido", Mp4Boxes.isValidMp4(v))
                    assertNotNull("faixa de áudio separada deve existir", a)
                    assertTrue("audio fMP4 deve ser válido", Mp4Boxes.isValidMp4(a!!))
                    v.copyTo(out, true)
                    out
                },
            )
            assertTrue(muxCalled)
            assertFalse("não devia baixar 480p quando 1080p está disponível", lowRequested.get())
            assertTrue(Mp4Boxes.isValidMp4(outFile))
            val expectedVideoLen = vInitBytes.size + vSeg1Bytes.size + vSeg2Bytes.size
            assertEquals(expectedVideoLen.toLong(), outFile.length())
            dir.deleteRecursively()
        } finally {
            srv.close()
            th.join(1_000)
        }
    }

    @Test fun sequentialRangeDownloadWithSpecialHeaderAndContentRange(): Unit = runBlocking {
        val payload = fakeInit("vide") + fakeFrag(18_000) + fakeFrag(18_000)
        val srv = ServerSocket(0)
        val port = srv.localPort
        val th = thread(isDaemon = true) {
            while (!srv.isClosed) {
                val sock = try { srv.accept() } catch (_: Exception) { break }
                try {
                    val reader = sock.getInputStream().bufferedReader()
                    reader.readLine() ?: continue
                    val hdrs = HashMap<String, String>()
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                        val i = l.indexOf(':')
                        if (i > 0) hdrs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                    }
                    val out = sock.getOutputStream()
                    if (hdrs["x-media-auth"] != "tok999") {
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush(); continue
                    }
                    val rng = hdrs["range"].orEmpty()
                    val m = Regex("""bytes=(\d+)-(\d*)""").find(rng)
                    val start = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val reqEnd = m?.groupValues?.get(2)?.toIntOrNull()
                    if (start >= payload.size) {
                        out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush(); continue
                    }
                    // Força chunks de no máx. 10 KB mesmo quando cliente pede bytes=0-
                    val end = minOf(payload.size - 1, reqEnd ?: (start + 10_000 - 1), start + 10_000 - 1)
                    val slice = payload.copyOfRange(start, end + 1)
                    val head = "HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes $start-$end/${payload.size}\r\nContent-Length: ${slice.size}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    out.write(slice)
                    out.flush()
                } catch (_: Exception) {}
                finally { try { sock.close() } catch (_: Exception) {} }
            }
        }
        try {
            val dir = Files.createTempDirectory("v1917_rng").toFile()
            val req = CapturedReq(
                url = "http://127.0.0.1:$port/stream/bin",
                headers = mapOf("X-Media-Auth" to "tok999"),
                contentRange = "bytes 0-1023/${payload.size}",
            )
            var lastGot = 0L
            var lastTot = 0L
            val f = MseDownloader.downloadRanges(req, "http://127.0.0.1:$port/watch", dir, "RangeVid", chunkSize = 12_000) { g, t ->
                lastGot = g; lastTot = t
            }
            assertEquals(payload.size.toLong(), f.length())
            assertEquals(payload.size.toLong(), lastGot)
            assertEquals(payload.size.toLong(), lastTot)
            assertTrue(Mp4Boxes.isValidMp4(f))
            assertArrayEquals(payload, f.readBytes())
            dir.deleteRecursively()
        } finally {
            srv.close()
            th.join(1_000)
        }
    }

    @Test fun fmp4CleanConcatStripsDuplicateInitAndValidates() {
        val init = fakeInit("vide")
        val s1 = fakeFrag(500)
        val s2 = fakeFrag(700)
        val out = ByteArrayOutputStream()
        var hasInit = false
        hasInit = Mp4Boxes.appendFmp4Clean(hasInit, init, out)
        assertTrue(hasInit)
        // segmento repetindo ftyp/moov antes do moof+mdat
        hasInit = Mp4Boxes.appendFmp4Clean(hasInit, init + s1, out)
        hasInit = Mp4Boxes.appendFmp4Clean(hasInit, s2, out)
        val res = out.toByteArray()
        assertEquals((init.size + s1.size + s2.size), res.size)
        val boxes = Mp4Boxes.parseBoxes(res)
        assertEquals(1, boxes.count { it.type == "ftyp" })
        assertEquals(1, boxes.count { it.type == "moov" })
        assertEquals(2, boxes.count { it.type == "moof" })
        assertEquals(2, boxes.count { it.type == "mdat" })
        assertEquals(TrackKind.VIDEO, Mp4Boxes.detectTrackKind(init))
    }

    @Test fun memoryCapReducesUnderLowMemoryWithWarning() {
        val (capOk, warnOk) = BrowserMseDownloader.effectiveConcurrency(10, 2_000L shl 20, 6_000L shl 20, false)
        assertEquals(10, capOk)
        assertNull(warnOk)

        val (capTight, warnTight) = BrowserMseDownloader.effectiveConcurrency(10, 380L shl 20, 4_000L shl 20, false)
        assertEquals(4, capTight)
        assertNotNull(warnTight)

        val (capLow, warnLow) = BrowserMseDownloader.effectiveConcurrency(10, 180L shl 20, 4_000L shl 20, true)
        assertEquals(2, capLow)
        assertNotNull(warnLow)
    }

    @Test fun anonReportSuggestsBrowserMseStrategy() {
        val fields = AnonReport.Fields(
            appVersion = "1.9.16",
            webViewVersion = "157.0",
            pageLoaded = true,
            httpCode = 200,
            challenge = true,
            challengeType = "js_challenge",
            videoCount = 3,
            sourceCount = 6,
            needsClick = true,
            format = "MSE",
            needsSpecialHeader = true,
            htmlDirect = AnonReport.Attempt(true, "OK"),
            hiddenAutoplay = AnonReport.Attempt(true, "OK"),
            mediaCapture = AnonReport.Attempt(true, "OK"),
            ytdlp = AnonReport.Attempt(false, "HTTP_403"),
        )
        val report = AnonReport.render(fields)
        assertTrue(report, report.contains("baixar pelo navegador (MSE)"))
    }

    @Test fun addressBarStateDoesNotOverwriteWhileFocused() {
        val st = BrowserAddress.State("https://www.grok.com")
        assertEquals("https://www.grok.com", st.text)

        // Focar seleciona todo o texto
        st.onFocusChanged(true)
        assertEquals(0, st.selStart)
        assertEquals("https://www.grok.com".length, st.selEnd)

        // Limpar / editar enquanto focado não é sobrescrito por onPageUrl
        st.onClear()
        assertEquals("", st.text)
        st.onEdit("meu site")
        st.onPageUrl("https://www.grok.com/chat/123")
        assertEquals("meu site", st.text)

        // Sair do foco sem enviar volta para a URL atual
        st.onFocusChanged(false)
        assertEquals("https://www.grok.com/chat/123", st.text)

        // Enviar URL vs pesquisa
        assertEquals("https://exemplo.com.br/a", BrowserAddress.toLoadUrl("exemplo.com.br/a"))
        assertEquals("https://www.google.com/search?q=curso+kotlin", BrowserAddress.toLoadUrl("curso kotlin"))

        st.onFocusChanged(true)
        st.onEdit("curso kotlin")
        assertEquals("https://www.google.com/search?q=curso+kotlin", st.onSubmit())
        assertFalse(st.focused)
    }

    private fun startHttpServer(handler: (path: String, cookie: String) -> String): Pair<Int, () -> Unit> {
        val ss = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    val br = s.getInputStream().bufferedReader()
                    val first = br.readLine() ?: ""
                    val path = first.split(' ').getOrNull(1) ?: "/"
                    var cookie = ""
                    while (true) {
                        val line = br.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Cookie:", ignoreCase = true)) cookie = line.substringAfter(':').trim()
                    }
                    val body = handler(path, cookie).toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body)
                    out.flush()
                    s.close()
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
        return ss.localPort to { try { ss.close() } catch (_: Exception) {}; t.interrupt() }
    }

    private fun httpGet(url: String): String =
        Net.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    @Test fun ageGateConfirmAndNegativeButtons() {
        val confirmInCtx = listOf(
            "tenho 18", "sou maior", "I am 18", "I'm over 18",
            "Enter", "Entrar", "Continuar", "Yes", "Agree", "Concordo", "Accept", "Confirm"
        )
        for (lbl in confirmInCtx) {
            assertTrue("Should confirm '$lbl' in age context", AgeGate.isConfirmLabel(lbl, inAgeContext = true))
        }
        val neverClick = listOf("Sair", "Leave", "No", "Não", "nao", "Menor de 18", "Under 18", "Recusar")
        for (lbl in neverClick) {
            assertFalse("Must NEVER click '$lbl'", AgeGate.isConfirmLabel(lbl, inAgeContext = true))
            assertFalse("Must NEVER click '$lbl'", AgeGate.isConfirmLabel(lbl, inAgeContext = false))
        }
        assertFalse(AgeGate.isConfirmLabel("Entrar", inAgeContext = false))
        assertFalse(AgeGate.isConfirmLabel("Continuar", inAgeContext = false))
        assertFalse(AgeGate.isConfirmLabel("Yes", inAgeContext = false))
    }

    @Test fun anonReportIncludesAgeGateBypassedLine() {
        val passedReport = AnonReport.render(AnonReport.Fields(
            appVersion = "1.9.17",
            pageLoaded = true,
            httpCode = 200,
            challenge = true,
            challengeType = "age_gate",
            ageGateBypassed = true,
            format = "MSE",
        ))
        assertTrue(passedReport, passedReport.contains("Anti-robô: sim  tipo=age_gate"))
        assertTrue(passedReport, passedReport.contains("age_gate: passou automaticamente sim"))
        val sanitized = AnonReport.sanitize(passedReport, setOf("video", "age", "gate"))
        assertTrue(sanitized, sanitized.contains("age_gate: passou automaticamente sim"))

        val failedReport = AnonReport.render(AnonReport.Fields(
            appVersion = "1.9.17",
            pageLoaded = true,
            httpCode = 200,
            challenge = true,
            challengeType = "age_gate",
            ageGateBypassed = false,
        ))
        assertTrue(failedReport, failedReport.contains("age_gate: passou automaticamente não"))
    }

    @Test fun fakeLocalAgeGateHtmlAndCookieUnlock() {
        val (port, stop) = startHttpServer { _, cookie ->
            if (cookie.contains("age_ok=1")) {
                """<html><body><div id="player"><video id="v1" src="/media.mp4"></video></div></body></html>"""
            } else {
                """
                <html><body>
                  <div id="age-gate-modal" class="age-overlay" role="dialog">
                    <p>Verificação de idade: você tem mais de 18 anos?</p>
                    <button id="btn-enter" onclick="document.cookie='age_ok=1;path=/';">Tenho 18 anos - Entrar</button>
                    <button id="btn-leave" onclick="location.href='/sair'">Sair</button>
                  </div>
                </body></html>
                """.trimIndent()
            }
        }
        try {
            val url = "http://127.0.0.1:$port/video"
            val gatedHtml = httpGet(url)
            assertEquals(true to "age_gate", SiteAnalyzer.detectChallenge(gatedHtml, 200))
            assertTrue(AgeGate.isAgeContextText(gatedHtml))
            val doc = org.jsoup.Jsoup.parse(gatedHtml, url)
            val enterBtn = doc.selectFirst("#btn-enter")!!.text()
            val leaveBtn = doc.selectFirst("#btn-leave")!!.text()
            assertTrue(AgeGate.isConfirmLabel(enterBtn, inAgeContext = true))
            assertFalse(AgeGate.isConfirmLabel(leaveBtn, inAgeContext = true))

            Net.cookies = object : CookieSource {
                override fun cookieFor(url: String) = "age_ok=1"
                override fun store(url: String, setCookie: String) {}
            }
            val unlockedHtml = httpGet(url)
            assertEquals(false to "none", SiteAnalyzer.detectChallenge(unlockedHtml, 200))
            assertTrue(unlockedHtml.contains("media.mp4"))
        } finally {
            Net.cookies = null
            stop()
        }
    }

    @Test fun loadMoreLabelsAndPaginationServer(): Unit = runBlocking {
        assertTrue(LoadMore.isLoadMoreLabel("Ver mais"))
        assertTrue(LoadMore.isLoadMoreLabel("Carregar mais vídeos"))
        assertTrue(LoadMore.isLoadMoreLabel("Load more"))
        assertFalse(LoadMore.isLoadMoreLabel("Comprar agora"))
        assertFalse(LoadMore.isLoadMoreLabel("Login"))

        val (port, stop) = startHttpServer { path, _ ->
            val p = Regex("page=(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val next = if (p < 3) "<a rel=\"next\" href=\"/cat?page=${p + 1}\">Próxima</a>" else ""
            "<html><body><a href=\"/v/$p\">Video $p</a>$next</body></html>"
        }
        try {
            val base = "http://127.0.0.1:$port/cat?page=1"
            val html1 = httpGet(base)
            val nextUrls = LoadMore.extractNextUrls(base, html1, setOf(base), 1)
            assertTrue(nextUrls.any { it.endsWith("/cat?page=2") })

            val collectedPages = ArrayList<Int>()
            val pagesVisited = LoadMore.runPagination(
                startUrl = base,
                seedUrls = nextUrls,
                maxPages = 5,
                isStopped = { false },
                itemCount = { collectedPages.size },
                fetchHtml = { pgUrl, _ -> httpGet(pgUrl) },
                collectPage = { _, pgHtml, _ ->
                    val num = Regex("/v/(\\d+)").find(pgHtml)?.groupValues?.get(1)?.toIntOrNull()
                    if (num != null) { collectedPages += num; 1 } else 0
                },
                onProgress = { _, _ -> },
            )
            assertEquals(listOf(2, 3), collectedPages)
            assertEquals(3, pagesVisited)
        } finally {
            stop()
        }
    }
}
