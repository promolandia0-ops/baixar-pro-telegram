package br.geracaoai.baixartg.core

import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Filtro por nome: sem diferenciar maiúsculas/acentos; todas as palavras precisam aparecer, em qualquer ordem. */
object NameFilter {
    private val MARKS = Regex("\\p{M}+")
    private val cache = ConcurrentHashMap<Found, String>()

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun words(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() }

    /** Título + texto ao redor (alt, link, figcaption) + página + URL (decodificada). */
    fun key(f: Found): String = cache.getOrPut(f) {
        val u = try { URLDecoder.decode(f.page + " " + f.url, "UTF-8") } catch (_: Exception) { f.page + " " + f.url }
        " " + norm(f.title + " " + f.extra + " " + u) + " "
    }

    fun matches(f: Found, w: List<String>): Boolean { if (w.isEmpty()) return true; val k = key(f); return w.all { k.contains(it) } }
}
