package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Resolve a página de UM vídeo (card) até o stream real, sempre o de maior qualidade:
 * 1) HTML estático; 2) WebView oculto com play/"assistir" (rede + <video> + JSON da API do player); 3) servidor yt-dlp.
 */
object PageResolver {
    fun isReal(f: Found): Boolean = f.kind != Kind.PHOTO && !(Extract.isPhotoUrl(f.url) && !Extract.isVideoUrl(f.url)) &&
        !Extract.isVideoThumb(f.url) && !TitleFix.isSubtitleUrl(f.url) && !f.url.contains("preview", true) &&
        (f.kind == Kind.SERVER || f.kind == Kind.HLS || f.kind == Kind.DIRECT || Extract.isVideoUrl(f.url))

    /** Melhor de uma lista (arquivo/stream antes de "via servidor"; depois maior qualidade). */
    fun best(list: List<Found>): Found? {
        val vids = list.map { MediaIdentity.withQuality(it) }.filter { isReal(it) }
        val pool = vids.filter { it.kind != Kind.SERVER }.ifEmpty { vids }
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
        pr?.videos?.let { cand += it.filter { v -> v.kind != Kind.SERVER } }
        if (best(cand) == null && js.apis.isNotEmpty()) cand += fromApis(js.apis, card)
        if (best(cand) == null) pr?.videos?.let { cand += it }
        return title to best(cand)
    }

    /**
     * Fila de envio: página do card → vídeo de maior qualidade. HTML estático → WebView oculto com play
     * (só se houver Activity viva; em segundo plano sem Activity pula) → servidor yt-dlp.
     */
    suspend fun resolveFull(card: String, title: String, ctx: String): Found? {
        val t0 = title.ifBlank { Extract.fileName(card).replace('-', ' ').replace('_', ' ') }.ifBlank { "vídeo" }
        if (Extract.isVideoUrl(card)) return Found(card, card, t0, Extract.mediaKindOf(card) ?: Kind.DIRECT, ref = card, extra = t0)
        if (Extract.YTDLP_HOSTS.containsMatchIn(card)) return Found(card, card, t0, Kind.SERVER, extra = t0)
        val html = fetch(card, null)
        val pr = html?.let { Extract.parse(card, it) }
        val t = TitleFix.pick(pr?.title, title).ifBlank { t0 }
        best(pr?.videos.orEmpty())?.let { return it.copy(page = card, ref = it.ref ?: card, title = t, extra = listOf(t, ctx, it.extra).joinToString(" ").trim()) }
        // navegador aberto na tela: servidor yt-dlp primeiro (não abre página oculta na janela do navegador);
        // página oculta só se o servidor não achar
        val browserOpen = JsRender.browserVisible
        if (browserOpen) {
            withTimeoutOrNull(45_000) { Downloader.probe(br.geracaoai.baixartg.Prefs.server, card) }?.let { pt ->
                return Found(card, card, TitleFix.pick(t, pt).ifBlank { pt }, Kind.SERVER, extra = listOf(t, ctx, pt).joinToString(" ").trim())
            }
        }
        if (JsRender.available()) {
            // ~30 s no máximo na página (incluindo espera por vaga); depois cai para o servidor yt-dlp
            val (realTitle, wv) = withTimeoutOrNull(30_000) { viaWebView(card) { u -> JsRender.renderQueue(u, 18_000) } } ?: ("" to null)
            val tt = TitleFix.pick(realTitle, t).ifBlank { t }
            if (wv != null) return wv.copy(page = card, ref = wv.ref ?: card, title = tt, extra = listOf(tt, ctx, wv.extra).joinToString(" ").trim())
        }
        if (browserOpen) return null   // o servidor já foi tentado acima
        val pt = withTimeoutOrNull(60_000) { Downloader.probe(br.geracaoai.baixartg.Prefs.server, card) } ?: return null
        return Found(card, card, TitleFix.pick(t, pt).ifBlank { pt }, Kind.SERVER, extra = listOf(t, ctx, pt).joinToString(" ").trim())
    }
}
