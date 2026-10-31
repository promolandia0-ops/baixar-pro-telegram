package br.geracaoai.baixartg

import br.geracaoai.baixartg.core.*
import org.junit.Assert.*
import org.junit.Test

class NameFilterTest {
    private fun f(t: String, u: String = "https://x.com/v/1") = Found(u, u, t, Kind.DIRECT)
    private fun m(q: String, x: Found) = NameFilter.matches(x, NameFilter.words(q))
    @Test fun basic() {
        assertTrue(m("Aline Faria", f("Vídeo da ALINE FÁRIA no motel")))
        assertTrue(m("faria aline", f("Aline Fária")))          // qualquer ordem
        assertTrue(m("aline faria", f("x", "https://site.com/aline-faria-video-hd/"))) // pela URL
        assertTrue(m("joão", f("Joao Silva")))
        assertTrue(m("  ", f("qualquer")))
        assertFalse(m("Aline Faria", f("Aline Souza")))          // todas as palavras
        assertTrue(m("aline x", f("x", "https://s.com/Aline%20X.mp4")))
    }
    @Test fun perf5000() {
        val l = (0 until 5000).map { f("Vídeo número $it de Fulana Ciclana " + if (it % 7 == 0) "Aline Faria" else "", "https://site.com/video-$it/") }
        l.forEach { NameFilter.key(it) }
        val t = System.nanoTime(); var n = 0
        repeat(10) { n = l.count { NameFilter.matches(it, NameFilter.words("aline faria")) } }
        val ms = (System.nanoTime() - t) / 10 / 1e6
        println("FILTRO 5000 itens: $n encontrados em ${"%.2f".format(ms)} ms por busca"); assertEquals(715, n); assertTrue(ms < 50)
    }
}
