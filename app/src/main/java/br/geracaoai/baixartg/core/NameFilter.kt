package br.geracaoai.baixartg.core

import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/** Filtro por nome: sem acento/maiúsculas; basta uma palavra significativa; typo e radical leves; também vale o slug da URL. */
object NameFilter {
    private val MARKS = Regex("\\p{M}+")
    private val cache = ConcurrentHashMap<Found, String>()
    private val STOP = setOf(
        "de", "da", "do", "das", "dos", "e", "em", "a", "o", "as", "os", "um", "uma",
        "the", "and", "of", "for", "com", "pra", "para", "no", "na", "nos", "nas", "por", "que", "se", "ao"
    )

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun words(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() }

    fun significant(w: List<String>): List<String> {
        val sig = w.filter { it.length >= 3 && it !in STOP }
        return sig.ifEmpty { w.filter { it.isNotEmpty() } }
    }

    /** Título + texto ao redor (alt, link, figcaption) + página + URL (decodificada; hífen vira espaço). */
    fun key(f: Found): String = cache.getOrPut(f) {
        val u = try { URLDecoder.decode(f.page + " " + f.url, "UTF-8") } catch (_: Exception) { f.page + " " + f.url }
        " " + norm(f.title + " " + f.extra + " " + u) + " "
    }

    fun matches(f: Found, w: List<String>): Boolean { if (w.isEmpty()) return true; return score(key(f), w) > 0 }

    fun matchesText(text: String, w: List<String>): Boolean { if (w.isEmpty()) return true; return score(" " + norm(text) + " ", w) > 0 }

    fun score(haystack: String, w: List<String>): Int {
        val sig = significant(w)
        if (sig.isEmpty()) return 1
        val hay = haystack.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        return sig.sumOf { tokenHit(it, hay, haystack) }
    }

    private fun tokenHit(q: String, hay: Set<String>, blob: String): Int {
        if (q.isEmpty()) return 0
        if (q in hay || blob.contains(q)) return 2
        if (hay.any { it.contains(q) || (q.length >= 4 && q.contains(it) && it.length >= 4) }) return 2
        if (q.length >= 4 && hay.any { it.startsWith(q.take(4)) || (it.length >= 4 && q.startsWith(it.take(4))) }) return 1
        val maxd = if (q.length >= 7) 2 else if (q.length >= 5) 1 else 0
        if (maxd > 0 && hay.any { abs(it.length - q.length) <= maxd && levenshtein(it, q) <= maxd }) return 1
        return 0
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (abs(a.length - b.length) > 2) return 3
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(prev[j] + 1, min(cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1))
            }
            for (j in prev.indices) prev[j] = cur[j]
        }
        return prev[b.length]
    }
}
