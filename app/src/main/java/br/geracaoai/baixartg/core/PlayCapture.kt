package br.geracaoai.baixartg.core

import java.util.concurrent.ConcurrentHashMap

/**
 * URLs de mídia que o player realmente pediu (WebView visível ou oculto), com Referer.
 * A fila baixa primeiro isso, com os mesmos cookies/UA, em vez de ir no servidor.
 */
object PlayCapture {
    data class Hit(
        val url: String,
        val page: String,
        val referer: String?,
        val bytes: Long = -1,
        val durationMs: Long = -1,
        val at: Long = System.currentTimeMillis(),
    )

    private val byPage = ConcurrentHashMap<String, MutableList<Hit>>()
    private const val MAX_PAGES = 80
    private const val MAX_HITS = 40

    private fun key(page: String) = PageKey.norm(page)

    fun record(url: String, page: String, referer: String? = page, bytes: Long = -1, durationMs: Long = -1) {
        if (page.isBlank() || url.isBlank() || !url.startsWith("http", true)) return
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) return
        if (Extract.isPhotoUrl(url) && !Extract.isVideoUrl(url) && !Extract.isHls(url)) return
        if (MediaPick.isAdUrl(url)) return
        if (Extract.mediaKindOf(url) == null && !Extract.isVideoUrl(url) && !Extract.isHls(url) && !Extract.isDash(url)) return
        val k = key(page)
        val list = byPage.getOrPut(k) { ArrayList() }
        synchronized(list) {
            val u = url.substringBefore('#')
            val i = list.indexOfFirst { it.url.substringBefore('#') == u }
            val hit = Hit(url, page, referer ?: page, bytes, durationMs)
            if (i >= 0) {
                val old = list[i]
                list[i] = hit.copy(
                    bytes = if (bytes > 0) bytes else old.bytes,
                    durationMs = if (durationMs > 0) durationMs else old.durationMs,
                )
            } else {
                list += hit
                if (list.size > MAX_HITS) list.removeAt(0)
            }
        }
        if (byPage.size > MAX_PAGES) {
            val oldest = byPage.entries.minByOrNull { e -> synchronized(e.value) { e.value.minOfOrNull { it.at } ?: 0L } }
            oldest?.let { byPage.remove(it.key) }
        }
    }

    fun hits(page: String): List<Hit> {
        val list = byPage[key(page)] ?: return emptyList()
        return synchronized(list) { list.filter { it.url.substringBefore('#') !in dead }.toList() }
    }

    /** Links que o servidor recusou de vez (423/410/403/401: token vencido ou trancado). Não voltam a ser escolhidos. */
    private val dead: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun markDead(url: String) {
        if (url.isBlank()) return
        dead += url.substringBefore('#')
        if (dead.size > 2000) dead.clear()
    }

    fun isDead(url: String) = url.substringBefore('#') in dead

    /** Erro de link vencido/trancado: vale pegar um link novo na página em vez de insistir. */
    fun isExpiredError(e: Throwable?): Boolean {
        val m = e?.message ?: return false
        return Regex("""HTTP (401|403|404|410|423|451)\b""").containsMatchIn(m)
    }

    fun bestFor(f: Found): Hit? {
        val pages = listOf(f.page, f.url, f.ref.orEmpty()).filter { it.isNotBlank() }.distinct()
        val cands = pages.flatMap { hits(it) }.distinctBy { it.url }
        if (cands.isEmpty()) return null
        val picked = MediaPick.pick(cands.map { MediaPick.Cand(it.url, it.bytes, it.durationMs, f.page) }) ?: return null
        return cands.firstOrNull { it.url == picked.url }
    }

    fun toFound(f: Found, hit: Hit): Found {
        val kind = Extract.mediaKindOf(hit.url) ?: if (Extract.isHls(hit.url)) Kind.HLS else Kind.DIRECT
        return f.copy(url = hit.url, kind = kind, ref = hit.referer ?: f.ref ?: f.page)
    }

    fun clearForTest() { byPage.clear() }
}
