package br.geracaoai.baixartg.core

/**
 * Detetive — APRENDER COM UM VÍDEO QUE JÁ FOI BAIXADO.
 * Você escolhe um vídeo do histórico (no próprio celular). O app compara a página da aula com o endereço do vídeo
 * que funcionou e descobre o "método": de que servidor/CDN o vídeo vem, o formato do endereço, qual Referer abriu,
 * se foi pelo servidor yt-dlp ou gravando os pedaços, e se o número/código da aula aparece no endereço do vídeo.
 * Depois repete isso nas outras aulas do mesmo site: monta o endereço direto (quando dá), procura primeiro endereços
 * com o mesmo formato e usa o mesmo Referer. Puro Kotlin (testável).
 */
object DetLearn {
    data class Lesson(
        val site: String,
        val pageExample: String,
        val mediaExample: String,
        val kind: String,               // HLS, DIRECT, SERVER, MSE
        val ref: DetKnowledge.Ref,
        val refFixed: String?,          // origem do player quando o Referer era o próprio player (ex.: Panda)
        val mediaPattern: String,       // formato do endereço do vídeo (regex)
        val pagePattern: String,        // formato do endereço das aulas (regex)
        val pageIdRegex: String,        // regex da aula com o código capturado (vazio = sem ligação)
        val mediaTemplate: String,      // endereço do vídeo com {id} no lugar do código da aula
        val recipe: String,
        val method: String,             // explicação em português
        val via: String = "",           // como o vídeo foi achado na página (varredura, rede, receita, play, servidor…)
        val signed: Boolean = false,    // link temporário (token/expira): precisa pegar um novo em cada aula
    )

    private val SIGNED = Regex("""(?i)[?&/~](?:exp|expires?|e|token|tkn|sig|signature|hmac|hash|policy|key-pair-id|acl|st|md5|auth|validfrom|validto|hdnts|hdntl|x-amz-signature|x-goog-signature)=|~hmac=|/exp=\d""")
    fun isSigned(u: String) = SIGNED.containsMatchIn(u)

    /** Mesmo vídeo (outro link do mesmo arquivo): mesmo caminho sem a query, ou mesmo formato com o mesmo código. */
    fun sameVideo(a: String, b: String): Boolean {
        fun core(u: String) = u.substringBefore('#').substringBefore('?').lowercase().replace(Regex("""/(?:exp|acl|hmac)=[^/]*"""), "")
        if (core(a) == core(b)) return true
        val ia = ids(a).filter { it.length >= 6 }.toSet(); val ib = ids(b).filter { it.length >= 6 }.toSet()
        return host(a) == host(b) && ia.intersect(ib).isNotEmpty()
    }

    private val UUID = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE)
    private val HEX = Regex("""^[0-9a-f]{8,}$""", RegexOption.IGNORE_CASE)
    private val NUM = Regex("""^\d{2,}$""")
    private val TOKEN = Regex("""^[A-Za-z0-9_\-]{16,}$""")

    fun isIdLike(s: String) = UUID.matches(s) || HEX.matches(s) && s.any { it.isDigit() } || NUM.matches(s) || TOKEN.matches(s) && s.count { it.isDigit() } >= 3

    fun host(u: String): String = try { (java.net.URI(u).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }
    private fun esc(s: String) = Regex.escape(s)

    data class U(val scheme: String, val host: String, val segs: List<String>, val query: List<Pair<String, String>>)
    fun split(u: String): U? = try {
        val x = java.net.URI(u.substringBefore('#'))
        if (x.host == null) null else U(x.scheme ?: "https", x.host.lowercase(), (x.rawPath ?: "").split('/').filter { it.isNotEmpty() },
            (x.rawQuery ?: "").split('&').filter { it.contains('=') }.map { it.substringBefore('=') to it.substringAfter('=') })
    } catch (_: Exception) { null }

    /** Formato do endereço: partes fixas iguais, códigos (números, hashes, uuids) viram curinga. */
    fun pattern(u: String): String {
        val p = split(u) ?: return esc(u)
        val host = p.host.split('.').joinToString("\\.") { l -> if (l.any { it.isDigit() } || l.length > 20) "[a-z0-9\\-]+" else esc(l) }
        val segs = p.segs.joinToString("/") { s -> segPattern(s) }
        return "^https?://$host/" + segs + "(?:[/?#].*)?$"
    }

    private fun segPattern(s: String): String = when {
        isIdLike(s) -> "[^/?#]+"
        s.any { it.isDigit() } -> s.split(Regex("""\d+""")).joinToString("\\d+") { esc(it) }
        else -> esc(s)
    }

    /** Partes da página que podem ser o código da aula (caminho e valores da query). */
    fun ids(u: String): List<String> {
        val p = split(u) ?: return emptyList()
        val cands = p.segs + p.query.map { it.second }
        return cands.filter { it.length >= 2 && (isIdLike(it) || Regex("""\d{2,}""").containsMatchIn(it) && it.length <= 40) }.distinct()
    }

    fun analyze(page: String, media: String, kind: String, ref: String?): Lesson {
        val site = host(page)
        val pageO = DetKnowledge.originOf(page)
        val (r, fixed) = when {
            ref.isNullOrBlank() -> DetKnowledge.Ref.NONE to null
            ref.substringBefore('#') == page.substringBefore('#') -> DetKnowledge.Ref.PAGE to null
            ref == pageO || ref.trimEnd('/') == pageO?.trimEnd('/') -> DetKnowledge.Ref.ORIGIN to null
            host(ref) == site -> DetKnowledge.Ref.PAGE to null
            host(ref) == host(media) -> DetKnowledge.Ref.NONE to null
            else -> DetKnowledge.Ref.FRAME to DetKnowledge.originOf(ref)
        }
        // código da aula que aparece no endereço do vídeo (o mais comprido ganha)
        val shared = ids(page).filter { it.length >= 3 && media.contains(it) }.maxByOrNull { it.length }
        val (pageId, tpl) = if (shared != null) {
            val p = split(page)
            val rx = if (p == null) "" else {
                val host = p.host.split('.').joinToString("\\.") { l -> if (l.any { it.isDigit() }) "[a-z0-9\\-]+" else esc(l) }
                val segs = p.segs.joinToString("/") { s -> if (s == shared) "([^/?#]+)" else segPattern(s) }
                val q = p.query.firstOrNull { it.second == shared }?.let { "[?&]" + esc(it.first) + "=([^&#]+)" }
                if (q != null) "^https?://$host/" + segs + ".*?" + q else "^https?://$host/" + segs + "(?:[/?#].*)?$"
            }
            rx to media.replace(shared, "{id}")
        } else "" to ""
        val rc = DetKnowledge.recipeFor(media) ?: ref?.let { DetKnowledge.recipeFor(it) }
        val how = buildString {
            append(when (kind) {
                "HLS" -> "lista HLS (.m3u8) baixada em pedaços"
                "DIRECT" -> "arquivo de vídeo direto"
                "SERVER" -> "servidor yt-dlp"
                "MSE" -> "gravando os pedaços enquanto o player toca"
                else -> kind
            })
            append(" em ").append(host(media).ifBlank { "?" })
            if (rc != null) append(" (player ${rc.name})")
            append(". Origem (Referer): ").append(when (r) {
                DetKnowledge.Ref.PAGE -> "a página do vídeo"
                DetKnowledge.Ref.ORIGIN -> "o endereço do site"
                DetKnowledge.Ref.FRAME -> "o player (${host(fixed ?: "")})"
                DetKnowledge.Ref.NONE -> "nenhuma"
            }).append('.')
            if (tpl.isNotBlank()) append(" O código da página aparece no endereço do vídeo: dá para montar o vídeo das outras páginas direto.")
        }
        return Lesson(site, page, media, kind, r, fixed, pattern(media), pattern(page), pageId, tpl, rc?.id.orEmpty(), how, signed = isSigned(media))
    }

    /** Endereço do vídeo de outra aula montado pelo modelo aprendido (null se a aula não tem o mesmo formato). */
    fun mediaFor(l: Lesson, page: String): String? {
        if (l.pageIdRegex.isBlank() || l.mediaTemplate.isBlank()) return null
        val m = try { Regex(l.pageIdRegex, RegexOption.IGNORE_CASE).find(page) } catch (_: Exception) { null } ?: return null
        val id = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return l.mediaTemplate.replace("{id}", id)
    }

    fun sameMedia(l: Lesson, u: String) = try { Regex(l.mediaPattern, RegexOption.IGNORE_CASE).containsMatchIn(u) } catch (_: Exception) { false }
    fun samePage(l: Lesson, u: String) = try { Regex(l.pagePattern, RegexOption.IGNORE_CASE).containsMatchIn(u) } catch (_: Exception) { false }
}
