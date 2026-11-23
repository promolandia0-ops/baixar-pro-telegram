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
    /**
     * Índice do histórico para hasCanonical (antes: percorria TODAS as chaves, com URI + regex em cada uma, na thread
     * da tela, a cada vídeo detectado). Mesmo resultado: mídia canônica e "stem" de cada chave guardados uma vez.
     */
    private val canonIdx: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val stemIdx: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private fun index(key: String) {
        try {
            when {
                key.startsWith("c:") -> canonIdx += key.removePrefix("c:")
                key.startsWith("s:") -> stemIdx += key.removePrefix("s:")
                key.startsWith("v:") -> {
                    val u0 = key.removePrefix("v:"); val u = if (u0.startsWith("http")) u0 else "https://$u0"
                    canonIdx += MediaIdentity.canonicalMedia(u)
                    stemIdx += MediaIdentity.fileStem(u)
                }
            }
        } catch (_: Exception) {}
    }
    private fun load() {
        set.clear(); canonIdx.clear(); stemIdx.clear()
        file?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) { val k = it.trim(); set += k; index(k) } }
        queued.clear(); qfile?.takeIf { it.exists() }?.forEachLine { if (it.isNotBlank()) queued += it.trim() }
    }
    @Synchronized fun markQueued(keys: Collection<String>) {
        val new = keys.filter { it.isNotBlank() && queued.add(it) }
        if (new.isNotEmpty()) try { qfile?.appendText(new.joinToString("\n", postfix = "\n")) } catch (_: Exception) {}
    }
    /** Tira da memória “já pedido”: o item pode voltar a aparecer no navegador (falhou, cancelou ou saiu da fila). */
    @Synchronized fun unqueue(keys: Collection<String>) {
        val rm = keys.filter { it.isNotBlank() && queued.remove(it) }
        if (rm.isNotEmpty()) writeQueued()
    }
    @Synchronized private fun writeQueued() {
        try {
            val f = qfile ?: return
            if (queued.isEmpty()) f.delete()
            else f.writeText(queued.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {}
    }
    fun isQueued(keys: Collection<String>) = keys.any { it in queued }
    val size get() = set.size
    /** Só a memória de “já enviado” (não apaga o histórico visível nem o “já pedido”). */
    @Synchronized fun clearSentOnly() { set.clear(); canonIdx.clear(); stemIdx.clear(); file?.delete() }
    @Synchronized fun clear() { clearSentOnly(); queued.clear(); qfile?.delete() }
    @Synchronized private fun addAll(keys: Collection<String>): Int {
        val new = keys.filter { set.add(it) }
        new.forEach { index(it) }
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
        // foto cujo endereço só se diferencia pela query (ex.: ".../images?q=ID", "/photo.php?id=N"): a query entra na chave.
        // Antes todas essas fotos tinham a MESMA chave e, depois de enviar uma, as outras eram puladas como "já enviada".
        // Só fotos; vídeos continuam exatamente com as mesmas chaves.
        val pq = photoQuery(f)
        k += "v:" + norm(f.url) + pq
        k += "c:" + MediaIdentity.canonicalMedia(f.url) + pq
        val stem = MediaIdentity.fileStem(f.url)
        if (MediaIdentity.usefulStem(stem) && stem.length >= 8) k += "s:" + stem
        k += bunkrKeys(f.url)
        if (Bunkr.applies(f)) k += bunkrKeys(f.page)
        if (norm(f.url) == norm(f.page) || f.kind == Kind.MEGA) k += "p:" + norm(f.page)
        return k
    }

    fun isSent(f: Found) = keysOf(f).any { it in set }

    /** Mesmo vídeo já enviado, mesmo em outra qualidade ou com assinatura de CDN diferente. */
    private fun photoQuery(f: Found) = if (f.kind == Kind.PHOTO) PageKey.idQuery(f.url) else ""
    fun hasCanonical(f: Found): Boolean {
        if (isSent(f)) return true
        if (photoQuery(f).isNotEmpty()) return false
        val media = MediaIdentity.canonicalMedia(f.url)
        val stem = MediaIdentity.fileStem(f.url)
        if (media.isBlank()) return false
        if (media in canonIdx) return true
        return MediaIdentity.usefulStem(stem) && stem.length >= 8 && stem in stemIdx
    }

    /** Grava depois do envio com sucesso (chaves do item + chaves extras: página do card, hash do arquivo). */
    fun markSent(f: Found, extra: Collection<String> = emptyList()) = addAll(keysOf(f) + extra)
    /** Foto enviada: grava só as chaves exatas da foto (não mexe nos índices de vídeo). */
    fun markPhotoSent(keys: Collection<String>) = addAll(keys.filter { it.isNotBlank() })
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
