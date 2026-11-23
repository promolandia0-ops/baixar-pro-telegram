package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.AnonReport
import br.geracaoai.baixartg.core.SiteAnalyzer
import br.geracaoai.baixartg.core.SiteHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class V1914Test {
    @Before fun salt() { SiteHash.initForTest("unit-test-salt-v1914-fixed") }

    private val secretTitle = "Aula do Professor Joao Silva"
    private val secretText = "conteudo privado da familia silva"
    private val pageUrl = "https://watch.site-teste.example/aula-secreta-joao"
    private val cdnUrl = "https://cdn.other-test.example/embed/aula-secreta"

    private fun assertClean(report: String, extra: List<String> = emptyList()) {
        val banned = listOf(
            "watch.site-teste.example", "site-teste", "other-test", "aula-secreta",
            "Joao", "Silva", "familia", "conteudo privado", "https://", "http://",
            "www.", pageUrl, cdnUrl, secretTitle, secretText, "127.0.0.1", "localhost",
        ) + extra
        for (b in banned) {
            assertFalse("o relatório não pode conter '$b':\n$report", report.contains(b, ignoreCase = true))
        }
        assertFalse("não pode ter e-mail", Regex("""[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}""", RegexOption.IGNORE_CASE).containsMatchIn(report))
    }

    private fun htmlHls(media: String = "$cdnUrl/master.m3u8") = """
        <html><head><title>$secretTitle</title></head>
        <body>
          <h1>$secretTitle</h1>
          <p>$secretText</p>
          <iframe src="$cdnUrl"></iframe>
          <video><source src="$media" type="application/x-mpegURL"></video>
        </body></html>
    """.trimIndent()

    private fun htmlMp4(media: String) = """
        <html><head><title>$secretTitle</title></head>
        <body><p>$secretText</p>
          <video src="$media" width="1280" height="720"></video>
        </body></html>
    """.trimIndent()

    private fun htmlCaptcha() = """
        <html><head><title>$secretTitle</title></head>
        <body>
          <div class="g-recaptcha" data-sitekey="fake"></div>
          <p>$secretText</p>
        </body></html>
    """.trimIndent()

    @Test fun sanitizerStripsUrlDomainTitleAndPageWords() {
        val dirty = """
            Veja https://watch.site-teste.example/aula-secreta-joao?token=abc
            email contato@site-teste.example e IP 203.0.113.44
            Título: $secretTitle
            $secretText
            cdn#abcd deve ficar
        """.trimIndent()
        val tokens = AnonReport.tokensOf(pageUrl, htmlHls(), secretTitle)
        val clean = AnonReport.sanitize(dirty, tokens)
        assertClean(clean)
        assertTrue(clean.contains("cdn#abcd"))
        assertTrue(clean.contains("[removido]"))
    }

    @Test fun hlsIframeRequiresReferer_suggestionAndNoLeak() {
        val fetch = SiteAnalyzer.FetchResult(true, 200, 850, 1, htmlHls())
        val snap = SiteAnalyzer.JsSnap(
            video = 1, iframe = 1, source = 1, needsClick = false,
            iframeHosts = listOf(cdnUrl), formatHints = listOf("HLS"),
        )
        val out = SiteAnalyzer.build(
            appVersion = "1.9.14",
            webViewVersion = "129.0.6668.54",
            pageUrl = pageUrl,
            fetch = fetch,
            snap = snap,
            captured = listOf("https://cdn.other-test.example/hls/master.m3u8"),
            header = SiteAnalyzer.HeaderGuess(needsReferer = true, needsCookie = false, needsSpecialHeader = false, expires = false),
            hidden = SiteAnalyzer.HiddenResult(true, listOf("HLS"), "OK"),
            ytdlp = SiteAnalyzer.YtdlpResult(false, extractor = "Generic", error = "Unsupported URL"),
            photosOn = false,
            photo = SiteAnalyzer.PhotoSnap(),
        )
        assertClean(out.report)
        assertTrue("sugestão esperada, veio: " + out.report, out.report.contains("SUGESTÃO: vídeo em HLS dentro de iframe e precisa de Referer"))
        assertTrue(out.report.contains("iframe de outro domínio=sim, cdn#"))
        assertTrue(out.report.contains("formato=HLS"))
        assertTrue(out.report.contains("precisa Referer=sim"))
        assertTrue(out.report.contains("WebView: 129.0"))
        assertFalse("versão do WebView não pode ir com 4 octetos tipo IP", out.report.contains("129.0.6668.54"))
        // extractor genérico, nunca o nome de extractor de um site
        assertTrue(out.report.contains("yt-dlp: falhou, motivo: UNSUPPORTED") || out.report.contains("EXTRACTOR_GENERIC") || out.report.contains("UNSUPPORTED"))
        assertFalse(out.report.contains("extractor específico"))
        // hash estável e irreversível
        val h = SiteHash.short("cdn", cdnUrl)
        assertTrue(h.matches(Regex("""cdn#[0-9a-f]{4}""")))
        assertFalse(h.contains("other-test"))
        assertTrue(out.report.contains(h))
        java.io.File("/tmp/v1914_example_report.txt").writeText(out.report)
    }

    @Test fun directMp4_suggestionAndNoLeak() {
        val media = "https://files.cdn-teste.example/v/arquivo-secreto.mp4"
        val out = SiteAnalyzer.build(
            appVersion = "1.9.14", webViewVersion = "120.0",
            pageUrl = pageUrl,
            fetch = SiteAnalyzer.FetchResult(true, 200, 200, 0, htmlMp4(media)),
            snap = SiteAnalyzer.JsSnap(video = 1, formatHints = listOf("MP4")),
            captured = listOf(media),
            header = SiteAnalyzer.HeaderGuess(needsReferer = false, needsCookie = false, needsSpecialHeader = false, expires = false),
            hidden = SiteAnalyzer.HiddenResult(true, listOf("MP4"), "OK"),
            ytdlp = SiteAnalyzer.YtdlpResult(true, extractor = "generic"),
            photosOn = false, photo = SiteAnalyzer.PhotoSnap(),
        )
        assertClean(out.report, listOf("arquivo-secreto", "cdn-teste"))
        assertTrue(out.report, out.report.contains("SUGESTÃO: O vídeo está em MP4 direto e não depende de Referer nem cookie."))
        assertTrue(out.report.contains("formato=MP4"))
        assertTrue(out.report.contains("yt-dlp: ok"))
    }

    @Test fun fakeChallenge_suggestionAndNoLeak() {
        val out = SiteAnalyzer.build(
            appVersion = "1.9.14", webViewVersion = "desconhecido",
            pageUrl = pageUrl,
            fetch = SiteAnalyzer.FetchResult(true, 200, 400, 0, htmlCaptcha()),
            snap = SiteAnalyzer.JsSnap(),
            captured = emptyList(),
            header = SiteAnalyzer.HeaderGuess(),
            hidden = SiteAnalyzer.HiddenResult(false, code = "NO_MEDIA"),
            ytdlp = SiteAnalyzer.YtdlpResult(false, error = "HTTP 403"),
            photosOn = false, photo = SiteAnalyzer.PhotoSnap(),
        )
        assertClean(out.report, listOf("recaptcha", "g-recaptcha", "sitekey"))
        assertTrue(out.report, out.report.contains("Anti-robô: sim  tipo=captcha"))
        assertTrue(out.report, out.report.contains("SUGESTÃO: O site pede um desafio (captcha) antes de liberar o vídeo."))
    }

    @Test fun specificExtractorIsHashed() {
        val r = SiteAnalyzer.mapYtdlp(SiteAnalyzer.YtdlpResult(false, extractor = "SiteSecretoIE", error = "extractor failed for https://watch.site-teste.example/x"))
        assertTrue(r.code.startsWith("EXTRACTOR_SPECIFIC#"))
        assertFalse(r.code.contains("SiteSecreto", ignoreCase = true))
        assertFalse(r.code.contains("site-teste"))
        val line = AnonReport.Attempt(false, r.code).line()
        val clean = AnonReport.sanitize("yt-dlp: $line", AnonReport.tokensOf(pageUrl, "", "SiteSecreto"))
        assertFalse(clean.contains("SiteSecreto", ignoreCase = true))
        assertFalse(clean.contains("https://"))
    }

    @Test fun localServerRefererHeaderGuessAndFullRunNoLeak() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val mediaPath = "/hls/master.m3u8"
        val th = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                try {
                    val reader = sock.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: continue
                    val path = first.split(' ').getOrNull(1).orEmpty()
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0) headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim()
                    }
                    val out = sock.getOutputStream()
                    if (path.startsWith(mediaPath)) {
                        val ok = headers["referer"]?.isNotBlank() == true
                        val body = if (ok) "#EXTM3U\n".toByteArray() else ByteArray(0)
                        val code = if (ok) "200 OK" else "403 Forbidden"
                        out.write("HTTP/1.1 $code\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (body.isNotEmpty()) out.write(body)
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.flush()
                } catch (_: Exception) {
                } finally { try { sock.close() } catch (_: Exception) {} }
            }
        }
        try {
            val media = "http://127.0.0.1:$port$mediaPath"
            val full = SiteAnalyzer.probeMedia(media, pageUrl, null, "Mozilla/5.0")
            val noRef = SiteAnalyzer.probeMedia(media, null, null, "Mozilla/5.0")
            assertTrue("com Referer deve passar", full.ok)
            assertFalse("sem Referer deve falhar", noRef.ok)
            val g = SiteAnalyzer.guessHeaders(full, noRef, full, full, media)
            assertEquals(true, g.needsReferer)

            val fetch = SiteAnalyzer.FetchResult(true, 200, 100, 0, htmlHls(media))
            val out = SiteAnalyzer.run(
                pageUrl = pageUrl,
                appVersion = "1.9.14",
                webViewVersion = "129.0",
                photosOn = false,
                snap = SiteAnalyzer.JsSnap(video = 1, iframe = 1, source = 1, iframeHosts = listOf(cdnUrl), formatHints = listOf("HLS")),
                captured = listOf(media),
                ytdlpFn = { SiteAnalyzer.YtdlpResult(false, extractor = "generic", error = "Unsupported URL") },
                hiddenFn = { SiteAnalyzer.HiddenResult(true, listOf("HLS"), "OK") },
                fetchFn = { fetch },
            )
            assertClean(out.report, listOf(":$port", "master.m3u8"))
            assertTrue(out.report, out.report.contains("SUGESTÃO: vídeo em HLS dentro de iframe e precisa de Referer"))
        } finally {
            server.close()
            th.join(1_000)
        }
    }

    @Test fun hashIsIrreversibleAndStable() {
        val a = SiteHash.short("cdn", "https://CDN.Other-Test.example/x")
        val b = SiteHash.short("cdn", "https://cdn.other-test.example/y")
        assertEquals(a, b) // mesmo host
        assertEquals(a, SiteHash.short("cdn", "https://www.cdn.other-test.example/"))
        assertNotEquals(a, SiteHash.short("cdn", "https://watch.site-teste.example/"))
        assertFalse(a.contains("other"))
        assertFalse(a.contains("example"))
    }
}
