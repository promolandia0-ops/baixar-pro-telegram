package br.geracaoai.baixartg.core

import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Bunkr: a página /f/<slug> traz jsCDN (URL crua no cdn.cr) e signUrl; o CDN só aceita com ?token=&ex= assinados. */
object Bunkr {
    const val DEFAULT_SIGN = "https://glb-apisign.cdn.cr/sign"
    private val BUNKR_HOST = Regex("""(^|\.)bunkr+\.[a-z]+$|(^|\.)bunkr-?[a-z]*\.[a-z]+$""")

    fun isBunkrPage(u: String) = BUNKR_HOST.containsMatchIn(Extract.host(u)) && Regex("""/(f|v|i|d)/[^/?#]+""").containsMatchIn(u)
    fun isCdn(u: String) = Extract.host(u).endsWith("cdn.cr") && u.contains("/storage/") || Extract.host(u).let { it.endsWith("bunkr.ru") || it.endsWith("bunkrr.su") } && Regex("""\.(mp4|webm|mov|mkv|m4v)""").containsMatchIn(u)
    fun applies(f: Found) = isCdn(f.url) || isBunkrPage(f.url) || isBunkrPage(f.page)

    class Media(val url: String, val referer: String, val title: String)

    private fun unesc(s: String) = s.replace("\\/", "/").replace("\\u002F", "/")

    /** Abre a página /f/ e devolve a URL do CDN já assinada. */
    suspend fun resolve(pageUrl: String): Media {
        val html = Net.http.newCall(Request.Builder().url(pageUrl).header("Accept", "text/html").build()).await().use { r ->
            if (!r.isSuccessful) throw DlException("Bunkr: página HTTP ${r.code}"); r.body!!.string()
        }
        val cdn = Regex("""jsCDN\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unesc)
            ?: Regex("""<(?:source|video)[^>]+src="(https?://[^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""href="(https?://[^"]*(?:cdn\.cr|get\.bunkr[^"]*)/[^"]+)"[^>]*>\s*Download""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: throw DlException("Bunkr: não achei o arquivo na página")
        val sign = Regex("""signUrl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let(::unesc) ?: DEFAULT_SIGN
        val title = Regex("""<meta property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""<title>([^<]+)""").find(html)?.groupValues?.get(1)?.substringBefore(" | ") ?: "video"
        return Media(sign(cdn, sign, pageUrl), origin(pageUrl), org.jsoup.parser.Parser.unescapeEntities(title, false).trim())
    }

    fun origin(u: String) = try { URI(u).let { "${it.scheme}://${it.authority}/" } } catch (_: Exception) { "https://bunkr.cr/" }

    /** Pede token ao serviço de assinatura do Bunkr (igual ao player do site). */
    suspend fun sign(rawUrl: String, signUrl: String = DEFAULT_SIGN, referer: String = "https://bunkr.cr/"): String {
        val clean = rawUrl.substringBefore('?')
        val path = URLDecoder.decode(URI(clean).rawPath, "UTF-8")
        val o = origin(referer).trimEnd('/')
        val j = Net.http.newCall(Request.Builder().url(signUrl + "?path=" + URLEncoder.encode(path, "UTF-8").replace("+", "%20"))
            .header("Origin", o).header("Referer", "$o/").header("Accept", "application/json").build()).await().use { r ->
            if (!r.isSuccessful) throw DlException("Bunkr: assinatura HTTP ${r.code}"); JSONObject(r.body!!.string())
        }
        val token = j.optString("token").ifBlank { throw DlException("Bunkr: assinatura sem token") }
        return "$clean?token=$token&ex=${j.opt("ex")}"
    }
}
