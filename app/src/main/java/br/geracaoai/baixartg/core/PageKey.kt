package br.geracaoai.baixartg.core

/**
 * Chave de página (para "já pedido / já na fila"). Igual à de antes (sem query), EXCETO quando o caminho é genérico
 * (/watch, /search, /imgres, /view.php, /video, raiz…): aí a query é o que diferencia uma página da outra.
 * Antes "site/watch?v=A" e "site/watch?v=B" viravam a mesma chave e o segundo vídeo era pulado como "já na fila/enviado"
 * (ex.: todos os resultados de uma busca de imagens). Páginas com caminho próprio (slug/id) continuam com a chave de sempre.
 */
object PageKey {
    private val TRACK = Regex("""(?i)^(utm_.*|fbclid|gclid|dclid|msclkid|igshid|mc_[a-z]+|ref|ref_src|ref_url|referrer|source|src|si|feature|share|from|via|ved|ei|sa|usg|sca_esv|sca_upv|oq|gs_lp|gs_lcrp|sclient|uact|rlz|bih|biw|dpr|prmd|iflsig|client|sourceid|ie|oe|hl|gl|lang|t|ts|_|cb|nocache|amp)$""")
    private val DIGIT = Regex("""\d""")

    fun base(u: String) = u.substringBefore('#').substringBefore('?').lowercase().replace("://www.", "://").replace("://m.", "://").trimEnd('/')

    /** Caminho genérico = último pedaço curto e sem número (ou nenhum caminho). */
    fun genericPath(u: String): Boolean {
        val path = u.substringBefore('#').substringBefore('?').substringAfter("://", "").substringAfter('/', "").trimEnd('/')
        if (path.isEmpty()) return true
        val last = path.substringAfterLast('/').substringBeforeLast('.')
        return last.length <= 12 && !DIGIT.containsMatchIn(last)
    }

    /** "?a=1&b=2" (ordenado, sem rastreio) quando o caminho é genérico e há parâmetros que identificam a página; senão "". */
    fun idQuery(u: String): String {
        val q = u.substringBefore('#').substringAfter('?', "")
        if (q.isBlank() || !genericPath(u)) return ""
        val ps = q.split('&').filter { it.isNotBlank() && it.contains('=') && it.substringAfter('=').isNotBlank() && !TRACK.matches(it.substringBefore('=')) }
            .map { it.lowercase() }.sorted()
        return if (ps.isEmpty()) "" else "?" + ps.joinToString("&")
    }

    fun norm(u: String) = base(u) + idQuery(u)
}
