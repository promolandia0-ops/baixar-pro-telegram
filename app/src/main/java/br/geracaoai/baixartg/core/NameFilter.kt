package br.geracaoai.baixartg.core

import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Filtro por nome (busca nomeada e “Filtrar por nome”).
 * Sem acento/maiúsculas; palavras inteiras; várias palavras = E (todas).
 * Sem typo, prefixo ou aproximação. O slug/arquivo da mídia conta só como palavras inteiras.
 * A URL da página não entra (evita que /aulas/guitarra/ marque todas as aulas).
 */
object NameFilter {
    private val MARKS = Regex("\\p{M}+")
    private val EXT = Regex("""\.(mp4|webm|mov|m4v|mkv|m3u8|mpd|ts|jpe?g|png|webp|gif|avif|bmp)$""", RegexOption.IGNORE_CASE)
    private val tokenCache = ConcurrentHashMap<Found, Set<String>>()

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun words(q: String): List<String> = norm(q).split(' ').filter { it.isNotEmpty() }

    fun required(w: List<String>): List<String> {
        val r = w.filter { it.length >= 2 }
        return r.ifEmpty { w.filter { it.isNotEmpty() } }
    }

    fun textTokens(s: String): List<String> = norm(s).split(' ').filter { it.isNotEmpty() }

    fun slugTokens(urlOrPath: String): List<String> {
        if (urlOrPath.isBlank()) return emptyList()
        val raw = try { URLDecoder.decode(urlOrPath, "UTF-8") } catch (_: Exception) { urlOrPath }
        val path = try {
            val u = if (raw.startsWith("http", true)) URI(raw) else null
            if (u != null) (u.path ?: "") else raw
        } catch (_: Exception) { raw }
        val file = EXT.replace(path.substringAfterLast('/'), "")
        val bits = (file + " " + EXT.replace(path, "")).replace(Regex("[/\\\\]+"), " ")
        return norm(bits.replace(Regex("[-_.]+"), " ")).split(' ').filter { it.isNotEmpty() }
    }

    fun tokens(f: Found): Set<String> = tokenCache.getOrPut(f) {
        val out = LinkedHashSet<String>()
        out += textTokens(f.title)
        out += textTokens(f.extra)
        out += slugTokens(f.url)
        out
    }

    fun key(f: Found): String = " " + tokens(f).joinToString(" ") + " "

    fun matches(f: Found, w: List<String>): Boolean {
        if (w.isEmpty()) return true
        val need = required(w)
        if (need.isEmpty()) return true
        val hay = tokens(f)
        return need.all { it in hay }
    }

    fun matchesText(text: String, w: List<String>): Boolean {
        if (w.isEmpty()) return true
        val need = required(w)
        if (need.isEmpty()) return true
        val hay = (textTokens(text) + slugTokens(text)).toHashSet()
        return need.all { it in hay }
    }

    /** Prioriza links na varredura: qualquer palavra inteira, sem typo. */
    fun matchesAny(text: String, w: List<String>): Boolean {
        if (w.isEmpty()) return true
        val need = required(w)
        if (need.isEmpty()) return true
        val hay = (textTokens(text) + slugTokens(text)).toHashSet()
        return need.any { it in hay }
    }
}
