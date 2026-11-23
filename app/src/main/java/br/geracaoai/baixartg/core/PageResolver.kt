package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Resolve a página de UM vídeo (card) até o stream real, sempre o de maior qualidade:
 * 1) HTML estático; 2) WebView oculto com play/"assistir" (rede + <video> + JSON da API do player); 3) servidor yt-dlp.
 */
object PageResolver {
    /** "capa/thumb/poster" no caminho só derruba quando não é arquivo de vídeo (antes derrubava até .mp4 de verdade). */
    fun isReal(f: Found): Boolean = f.kind != Kind.PHOTO && !Extract.isSegmentUrl(f.url) && !(Extract.isPhotoUrl(f.url) && !Extract.isVideoUrl(f.url)) &&
        !(Extract.isVideoThumb(f.url) && !Extract.isVideoUrl(f.url) && !Extract.isHls(f.url)) && !TitleFix.isSubtitleUrl(f.url) &&
        (f.kind == Kind.SERVER || f.kind == Kind.HLS || f.kind == Kind.DIRECT || Extract.isVideoUrl(f.url))
    private fun isPreview(f: Found) = Extract.isPreviewVideo(f.url)

    /**
     * Melhor de uma lista (arquivo/stream antes de "via servidor"; depois maior qualidade).
     * "preview" fica de fora aqui (é a prévia curta); se não sobrar outra coisa, vira o último recurso depois do servidor.
     */
    fun best(list: List<Found>, allowPreview: Boolean = false): Found? {
        val vids = list.map { MediaIdentity.withQuality(it) }.filter { isReal(it) && (allowPreview || !isPreview(it)) && !MediaPick.isAdUrl(it.url) }
        val pool = vids.filter { it.kind != Kind.SERVER }.ifEmpty { vids }
        val picked = MediaPick.pick(pool.map { MediaPick.Cand(it.url, pageUrl = it.page) })
        if (picked != null) return pool.filter { it.url == picked.url }.reduceOrNull { a, b -> if (MediaIdentity.isBetter(b, a)) b else a }
            ?: pool.firstOrNull { it.url == picked.url }
        return pool.reduceOrNull { a, b -> if (MediaIdentity.isBetter(b, a)) b else a }
    }

    suspend fun fetch(url: String, referer: String?): String? = try {
        val b = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        Net.fast.newCall(b.build()).await().use { r ->
            val ct = r.header("Content-Type") ?: ""
            if (r.isSuccessful && (ct.isEmpty() || ct.contains("html", true) || ct.contains("json", true) || ct.contains("text", true) || ct.contains("javascript", true)))
                r.body?.source()?.let { src -> src.request(2_000_000); src.buffer.readUtf8(minOf(src.buffer.size, 2_000_000)) } else null
        }
    } catch (e: Exception) { if (e is CancellationException) throw e; null }

    /** JSON/APIs que o player chamou: procura URLs de vídeo dentro da resposta. */
    suspend fun fromApis(apis: List<String>, referer: String): List<Found> {
        val out = ArrayList<Found>()
        for (a in apis.distinct().take(10)) {
            val body = fetch(a, referer) ?: continue
            out += Extract.parse(a, body).videos.map { it.copy(ref = referer) }
        }
        return out
    }

    /** Página renderizada no WebView com play: devolve (título real da página, melhor vídeo). */
    suspend fun viaWebView(card: String, render: suspend (String) -> JsPage?): Pair<String, Found?> {
        val js = try { render(card) } catch (e: Exception) { if (e is CancellationException) throw e; null } ?: return "" to null
        val pr = js.html?.let { Extract.parse(card, it) }
        val title = TitleFix.pick(js.collected?.bestPageTitle, pr?.title)
        val cand = ArrayList<Found>()
        cand += js.media.map { it.copy(ref = it.ref ?: card) }
        synchronized(previews) { cand.filter { isPreview(it) }.forEach { previews[card] = it } }
        pr?.videos?.let { cand += it.filter { v -> v.kind != Kind.SERVER } }
        if (best(cand) == null && js.apis.isNotEmpty()) cand += fromApis(js.apis, card)
        if (best(cand) == null) pr?.videos?.let { cand += it }
        return title to best(cand)
    }

    /**
     * Fila de envio: página do card → vídeo de maior qualidade. HTML estático → WebView oculto com play
     * (só se houver Activity viva; em segundo plano sem Activity pula) → servidor yt-dlp.
     */
    /** Prévias vistas por página (último recurso: melhor enviar algo do que marcar como falhou). */
    private val previews = object : LinkedHashMap<String, Found>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Found>?) = size > 200 }

    suspend fun resolveFull(card: String, title: String, ctx: String): Found? {
        val r = resolveFull0(card, title, ctx)
        if (r != null) return r
        // nada além da prévia: usa a prévia (antes o item falhava)
        val pv = synchronized(previews) { previews.remove(card) } ?: return null
        val t = title.ifBlank { pv.title }
        return pv.copy(page = card, ref = pv.ref ?: card, title = t, extra = listOf(t, ctx, pv.extra).joinToString(" ").trim())
    }

    private suspend fun resolveFull0(card: String, title: String, ctx: String): Found? {
        val t0 = title.ifBlank { Extract.fileName(card).replace('-', ' ').replace('_', ' ') }.ifBlank { "vídeo" }
        // 1) o que o player já pediu nesta página (mesmos cookies/Referer)
        PlayCapture.bestFor(Found(card, card, t0, Kind.DIRECT, ref = card, extra = t0))?.let { hit ->
            val kind = Extract.mediaKindOf(hit.url) ?: Kind.DIRECT
            return Found(hit.url, card, t0, kind, ref = hit.referer ?: card, extra = t0)
        }
        if (Extract.isVideoUrl(card)) return Found(card, card, t0, Extract.mediaKindOf(card) ?: Kind.DIRECT, ref = card, extra = t0)
        if (Extract.YTDLP_HOSTS.containsMatchIn(card)) return Found(card, card, t0, Kind.SERVER, extra = t0)
        val html = fetch(card, null)
        val pr = html?.let { Extract.parse(card, it) }
        val t = TitleFix.pick(pr?.title, title).ifBlank { t0 }
        best(pr?.videos.orEmpty())?.let { return it.copy(page = card, ref = it.ref ?: card, title = t, extra = listOf(t, ctx, it.extra).joinToString(" ").trim()) }
        best(pr?.videos.orEmpty(), allowPreview = true)?.let { pv -> synchronized(previews) { previews[card] = pv.copy(title = t) } }
        // 2) fluxo confiável da 1.9.7: com navegador aberto tenta servidor antes de abrir WebView oculto; senão WebView oculto (pool 10) e depois servidor
        val browserOpen = JsRender.browserVisible
        if (browserOpen) {
            withTimeoutOrNull(45_000) { Downloader.probe(br.geracaoai.baixartg.Prefs.server, card) }?.let { pt ->
                return Found(card, card, TitleFix.pick(t, pt).ifBlank { pt }, Kind.SERVER, extra = listOf(t, ctx, pt).joinToString(" ").trim())
            }
        }
        if (JsRender.available()) {
            val (realTitle, wv) = withTimeoutOrNull(30_000) { viaWebView(card) { u -> JsRender.renderQueue(u, 18_000) } } ?: ("" to null)
            val tt = TitleFix.pick(realTitle, t).ifBlank { t }
            if (wv != null) return wv.copy(page = card, ref = wv.ref ?: card, title = tt, extra = listOf(tt, ctx, wv.extra).joinToString(" ").trim())
        }
        if (browserOpen) return null
        val pt = withTimeoutOrNull(60_000) { Downloader.probe(br.geracaoai.baixartg.Prefs.server, card) } ?: return null
        return Found(card, card, TitleFix.pick(t, pt).ifBlank { pt }, Kind.SERVER, extra = listOf(t, ctx, pt).joinToString(" ").trim())
    }
}
