package br.geracaoai.baixartg.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Histórico de vídeos já enviados ao Telegram (arquivo local, uma chave por linha).
 * Chaves: "v:<url do vídeo normalizada>", "p:<página>" (só quando o vídeo É a própria página) e
 * "bk:<id do arquivo no Bunkr>" / "bf:<slug /f/ do Bunkr>".
 */
object Sent {
    private val set: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var file: File? = null
    /** Vídeos que o usuário já mandou baixar no navegador (fila/enviando/enviados): nunca voltam a aparecer na lista. */
    private val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var qfile: File? = null

    fun init(dir: File) { file = File(dir, "sent_keys.txt"); qfile = File(dir, "queued_keys.txt"); load() }
    private fun load() {
        set.clear(); file?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) set += it.trim() }
        queued.clear(); qfile?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) queued += it.trim() }
    }
    @Synchronized fun markQueued(keys: Collection<String>) {
        val new = keys.filter { it.isNotBlank() && queued.add(it) }
        if (new.isNotEmpty()) try { qfile?.appendText(new.joinToString("\n", postfix = "\n")) } catch (_: Exception) {}
    }
    fun isQueued(keys: Collection<String>) = keys.any { it in queued }
    val size get() = set.size
    @Synchronized fun clear() { set.clear(); file?.delete(); queued.clear(); qfile?.delete() }
    @Synchronized private fun addAll(keys: Collection<String>): Int {
        val new = keys.filter { set.add(it) }
        if (new.isNotEmpty()) file?.appendText(new.joinToString("\n", postfix = "\n"))
        return new.size
    }

    /** Normaliza URL: sem query/fragmento, host minúsculo sem www, sem barra final, %xx decodificado. */
    fun norm(u: String): String = try {
        val x = URI(u.trim().substringBefore('#').replace(" ", "%20"))
        val host = (x.host ?: "").lowercase().removePrefix("www.").removePrefix("m.")
        val path = (try { URLDecoder.decode(x.rawPath ?: "", "UTF-8") } catch (_: Exception) { x.rawPath ?: "" }).trimEnd('/')
        host + path.lowercase()
    } catch (_: Exception) { u.trim().substringBefore('?').substringBefore('#').lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/') }

    private val BK_FILE = Regex("""cdn\.cr/(?:storage/)?(?:media/)?([0-9a-fA-F-]{36})""")
    private val BK_FILE2 = Regex("""/(?:storage/media|media)/([0-9a-fA-F-]{36})""")
    private val BK_SLUG = Regex("""bunkr+[a-z-]*\.[a-z]+/(?:f|v|d|i)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    fun bunkrKeys(u: String): List<String> {
        val out = ArrayList<String>()
        (BK_FILE.find(u) ?: if (Extract.host(u).endsWith("cdn.cr")) BK_FILE2.find(u) else null)?.let { out += "bk:" + it.groupValues[1].lowercase() }
        BK_SLUG.find(u)?.let { out += "bf:" + it.groupValues[1] }
        return out
    }

    /** Chaves que identificam um item da lista. */
    fun keysOf(f: Found): List<String> {
        val k = ArrayList<String>()
        k += "v:" + norm(f.url)
        k += "c:" + MediaIdentity.canonicalMedia(f.url)
        val stem = MediaIdentity.fileStem(f.url)
        if (MediaIdentity.usefulStem(stem) && stem.length >= 8) k += "s:" + stem
        k += bunkrKeys(f.url)
        if (Bunkr.applies(f)) k += bunkrKeys(f.page)
        if (norm(f.url) == norm(f.page) || f.kind == Kind.MEGA) k += "p:" + norm(f.page)
        return k
    }

    fun isSent(f: Found) = keysOf(f).any { it in set }

    /** Mesmo vídeo já enviado, mesmo em outra qualidade ou com assinatura de CDN diferente. */
    fun hasCanonical(f: Found): Boolean {
        if (isSent(f)) return true
        val media = MediaIdentity.canonicalMedia(f.url)
        val stem = MediaIdentity.fileStem(f.url)
        if (media.isBlank()) return false
        return set.any { key ->
            when {
                key.startsWith("c:") -> key.removePrefix("c:") == media
                key.startsWith("s:") -> MediaIdentity.usefulStem(stem) && stem.length >= 8 && key.removePrefix("s:") == stem
                key.startsWith("v:") -> {
                    val u = key.removePrefix("v:")
                    MediaIdentity.canonicalMedia(if (u.startsWith("http")) u else "https://$u") == media ||
                        (MediaIdentity.usefulStem(stem) && stem.length >= 8 && MediaIdentity.fileStem(if (u.startsWith("http")) u else "https://$u") == stem)
                }
                else -> false
            }
        }
    }

    /** Grava depois do envio com sucesso (chaves do item + chaves extras: página do card, hash do arquivo). */
    fun markSent(f: Found, extra: Collection<String> = emptyList()) = addAll(keysOf(f) + extra)
    @Synchronized fun has(k: String) = k in set
    fun anySent(keys: Collection<String>) = keys.any { has(it) }

    private val URL_RE = Regex("""https?://[^\s<>"']+""")

    /**
     * Legenda que o app grava: "título\npágina\n[url do vídeo]". Importa as chaves dela.
     * Se só houver um link, ele vale como página e como vídeo (vídeo = página, ou Bunkr sem link do CDN).
     */
    fun keysFromCaption(caption: String): List<String> {
        val links = URL_RE.findAll(caption).map { it.value.trimEnd('.', ',', ')', ']') }.toList()
        if (links.isEmpty()) return emptyList()
        val k = ArrayList<String>()
        links.forEach { l -> k += "v:" + norm(l); k += bunkrKeys(l) }
        if (links.size == 1) k += "p:" + norm(links[0])
        return k
    }

    fun importCaptions(caps: List<String>): Int = addAll(caps.flatMap { keysFromCaption(it) }.distinct())
}
