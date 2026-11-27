package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.concurrent.thread

class V1912Test {
    @After fun cleanup() {
        Pipeline.resetForTest()
        Pipeline.testMode = false
    }

    /** Gera bytes de PNG mínimo válido com dimensões exatas (sem depender do Android Bitmap). */
    private fun makePng(w: Int, h: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        // assinatura PNG
        dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            dos.writeInt(data.size)
            val tb = type.toByteArray(Charsets.US_ASCII)
            dos.write(tb)
            dos.write(data)
            val crc = CRC32().apply { update(tb); update(data) }.value
            dos.writeInt(crc.toInt())
        }
        val ihdr = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(w); writeInt(h); writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk("IHDR", ihdr)
        // IDAT mínimo válido
        chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x63, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01))
        chunk("IEND", ByteArray(0))
        return baos.toByteArray()
    }

    @Test fun twentyOneItemsUnifiedFlow_tenActiveTogether_noLimbo_failuresFreeSlots() = runBlocking {
        Pipeline.resetForTest()
        Pipeline.testMode = true
        Pipeline.testParDown = 10
        Pipeline.testParUp = 5

        val curResolving = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        Pipeline.testResolve = { t ->
            val nowActive = curResolving.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, nowActive) }
            try {
                delay(220)
                // Itens 1 e 2 falham na resolução: devem liberar a vaga e ir para FAIL (nunca travar a fila)
                if (t.title.endsWith("#1") || t.title.endsWith("#2")) null
                else Found("https://cdn.example/stream.m3u8?id=${t.id}", t.page, t.title, Kind.HLS, ref = t.page)
            } finally {
                curResolving.decrementAndGet()
            }
        }
        Pipeline.testDownload = { t, dir ->
            delay(80)
            File(dir, "vid_${t.id}.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32)) }
        }
        Pipeline.testUpload = { _, _ -> delay(40) }

        val listing = "https://site.example/galeria"
        val batch = (1..21).map { i ->
            Pipeline.In(listing, "Video #$i", "", listOf("ip:$listing"), null, uniq = "card:$listing#$i")
        }
        assertEquals(21, Pipeline.add(batch))
        assertEquals(21, Pipeline.items.value.size)

        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            val all = Pipeline.items.value
            val active = all.count { it.st == Pipeline.PS.RESOLVING || it.st == Pipeline.PS.DOWN }
            maxActive.updateAndGet { maxOf(it, active) }
            if (all.all { Pipeline.isFinal(it.st) }) break
            delay(20)
        }

        val finalAll = Pipeline.items.value
        assertEquals(21, finalAll.size)
        assertTrue("esperado até 10 itens rodando ao mesmo tempo (max=${maxActive.get()})", maxActive.get() >= 10)
        assertEquals(19, finalAll.count { it.st == Pipeline.PS.DONE })
        assertEquals("states=" + finalAll.map { "${it.title}:${it.st}:${it.msg}" }, 2, finalAll.count { it.st == Pipeline.PS.FAIL })
        assertEquals("nenhum item em limbo", 0, finalAll.count { !Pipeline.isFinal(it.st) })
    }

    @Test fun siblingHlsInSameDirDoNotCollideAsDuplicates() {
        val a = Found("https://cdn.example/hls/playlist.m3u8?id=101", "https://site.example/v/101", "A", Kind.HLS)
        val b = Found("https://cdn.example/hls/playlist.m3u8?id=202", "https://site.example/v/202", "B", Kind.HLS)
        val ka = MediaIdentity.keys(a)
        val kb = MediaIdentity.keys(b)
        assertFalse("não deve gerar chave de diretório d:", ka.any { it.startsWith("d:") })
        assertTrue("chaves canônicas devem ser distintas", ka.intersect(kb).isEmpty())
    }

    @Test fun photoCardWithSquareThumbAndViewerLink_choosesLargeUncroppedImage() = runBlocking {
        val thumbBytes = makePng(300, 300)     // miniatura quadrada cortada da grade
        val fullBytes = makePng(1080, 1620)    // foto inteira na página aberta

        val server = ServerSocket(0)
        val port = server.localPort
        val requested = mutableListOf<String>()
        val srvThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                try {
                    val reader = sock.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: continue
                    val path = first.split(' ').getOrNull(1).orEmpty()
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                    }
                    synchronized(requested) { requested += path }
                    val out = sock.getOutputStream()
                    when {
                        path.startsWith("/thumb/") -> {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${thumbBytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(thumbBytes)
                        }
                        path.startsWith("/photo/123") -> {
                            val html = """
                                <html><head>
                                  <meta property="og:image" content="http://127.0.0.1:$port/full/portrait.png">
                                </head><body>
                                  <a href="http://127.0.0.1:$port/full/portrait.png">
                                    <img src="http://127.0.0.1:$port/full/portrait.png" data-full="http://127.0.0.1:$port/full/portrait.png">
                                  </a>
                                </body></html>
                            """.trimIndent().toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(html)
                        }
                        path.startsWith("/full/portrait.png") -> {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${fullBytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(fullBytes)
                        }
                        else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        }

        try {
            // 1) Verifica parsing do link do card no PhotoGrab
            val raw = """{"v":"g2","n":1,"raw":1,"p":[{"u":"http://127.0.0.1:$port/thumb/sq_300x300.png","w":300,"h":300,"th":"http://127.0.0.1:$port/thumb/sq_300x300.png","t":"Foto inteira","s":"img","o":0,"l":"http://127.0.0.1:$port/photo/123"}]}"""
            val parsed = PhotoGrab.parse(raw)
            assertTrue(parsed.ok)
            assertEquals("http://127.0.0.1:$port/photo/123", parsed.cands[0].link)

            // 2) Resolve dentro do mesmo slot seguindo o link do card + comparando bytes reais
            val res = PhotoResolve.resolveAndFetch(
                url = parsed.cands[0].url,
                pageUrl = parsed.cands[0].link,
                referer = "http://127.0.0.1:$port/gallery",
                fetchHtml = { u, _ -> java.net.URL(u).readText() },
                fetchBytes = { u, _ -> java.net.URL(u).openStream().use { it.readBytes() } }
            )
            assertNotNull(res)
            assertEquals("http://127.0.0.1:$port/full/portrait.png", res!!.url)
            assertEquals(1080, res.w)
            assertEquals(1620, res.h)
            assertFalse(PhotoResolve.isSmallSquare(res.w, res.h))
            assertTrue("deve ter aberto o link do visualizador", requested.any { it.startsWith("/photo/123") })
        } finally {
            server.close()
            srvThread.join(1_000)
        }
    }

    @Test fun photoPickBestRejectsSmallSquareCropWhenUncroppedExists() {
        val sq = PhotoResolve.LoadedPhoto("https://site.com/thumb_s.png", makePng(400, 400), "png", 400, 400)
        val full = PhotoResolve.LoadedPhoto("https://site.com/full.png", makePng(900, 1400), "png", 900, 1400)
        val picked = PhotoResolve.pickBest(listOf(sq, full))
        assertNotNull(picked)
        assertEquals(full.url, picked!!.url)
    }
}
