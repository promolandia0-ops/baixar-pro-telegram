package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Kind
import br.geracaoai.baixartg.core.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class V1910Test {
    @Test fun pageKeyDoesNotBindSiblingVideos() {
        val a = Found("https://cdn.com/a.mp4", "https://site.com/galeria", "A", Kind.DIRECT)
        val b = Found("https://cdn.com/b.mp4", "https://site.com/galeria", "B", Kind.DIRECT)
        val ka = Pipeline.mediaKeys(listOf("pg:site.com/galeria", "ip:https://site.com/galeria"), a, false)
        val kb = Pipeline.mediaKeys(listOf("pg:site.com/galeria", "ip:https://site.com/galeria"), b, false)
        assertTrue("não pode usar a página como chave de fila", ka.none { it.startsWith("pg:") || it.startsWith("ip:") || it.startsWith("p:") })
        assertTrue(kb.none { it.startsWith("pg:") || it.startsWith("ip:") || it.startsWith("p:") })
        assertTrue("dois vídeos da mesma página não podem compartilhar chave de mídia", ka.intersect(kb).isEmpty())
        assertTrue(ka.any { it.contains("cdn.com/a") || it.contains("a.mp4") })
        assertTrue(kb.any { it.contains("cdn.com/b") || it.contains("b.mp4") })
    }

    @Test fun nJobsRunConcurrently() = runBlocking {
        val n = 10
        val inflight = AtomicInteger(0)
        val max = AtomicInteger(0)
        val started = AtomicInteger(0)
        List(n) {
            async(Dispatchers.Default) {
                started.incrementAndGet()
                val now = inflight.incrementAndGet()
                max.updateAndGet { m -> maxOf(m, now) }
                delay(80)
                inflight.decrementAndGet()
            }
        }.awaitAll()
        assertEquals(n, started.get())
        assertTrue("esperava $n ao mesmo tempo, máximo foi ${max.get()}", max.get() >= n)
    }
}
