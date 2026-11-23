package br.geracaoai.baixartg.core

/**
 * Relatório anônimo em whitelist: só campos fixos (enums, números, booleanos, hashes).
 * Nenhum texto livre da página, URL, domínio, título ou exceção entra no relatório.
 */
object AnonReport {
    data class Attempt(val ok: Boolean, val code: String) {
        fun line(): String = if (ok) "ok" else "falhou, motivo: ${safeCode(code)}"
    }

    data class Fields(
        val appVersion: String,
        val webViewVersion: String = "desconhecido",
        val pageLoaded: Boolean = false,
        val httpCode: Int = 0,
        val loadMs: Long = 0,
        val redirectCount: Int = 0,
        val challenge: Boolean = false,
        val challengeType: String = "none",
        val ageGateBypassed: Boolean? = null,
        val videoCount: Int = 0,
        val iframeCount: Int = 0,
        val sourceCount: Int = 0,
        val needsClick: Boolean = false,
        val iframeCrossOrigin: Boolean = false,
        val iframeCdnHash: String = "",
        val format: String = "none",
        val linkExpires: Boolean? = null,
        val needsReferer: Boolean? = null,
        val needsCookie: Boolean? = null,
        val needsSpecialHeader: Boolean? = null,
        val drm: Boolean = false,
        val drmType: String = "none",
        val htmlDirect: Attempt = Attempt(false, "NO_MEDIA"),
        val hiddenAutoplay: Attempt = Attempt(false, "SKIPPED"),
        val mediaCapture: Attempt = Attempt(false, "NO_MEDIA"),
        val ytdlp: Attempt = Attempt(false, "SKIPPED"),
        val photosOn: Boolean = false,
        val imgCount: Int = 0,
        val photoCands: Int = 0,
        val photoRejects: Map<String, Int> = emptyMap(),
        val photoScriptErrors: Int = 0,
        val appJsErrors: List<String> = emptyList(),
        val pageJsErrorCounts: Map<String, Int> = emptyMap(),
    )

    val CHALLENGE_TYPES = setOf("none", "captcha", "js_challenge", "cookie_required", "geo_block", "age_gate")
    val FORMATS = setOf("none", "MP4", "HLS", "DASH", "blob", "MSE", "desconhecido")
    val DRM_TYPES = setOf("none", "widevine", "playready", "fairplay", "desconhecido")
    val ATTEMPT_CODES = setOf(
        "OK", "NO_MEDIA", "SKIPPED", "TIMEOUT", "SSL", "DNS", "CONNECTION", "NETWORK", "CANCELLED",
        "UNSUPPORTED", "EXTRACTOR_ERROR", "EXTRACTOR_GENERIC", "HTTP_400", "HTTP_401", "HTTP_403",
        "HTTP_404", "HTTP_429", "HTTP_451", "HTTP_500", "HTTP_502", "HTTP_503", "HTTP_504",
    )
    val PHOTO_REASONS = setOf(
        "pequena", "icone", "miniatura", "capa", "anuncio", "sprite", "avatar", "logo",
        "sem-endereco", "banner", "lixo", "outro",
    )
    val JS_TYPES = setOf(
        "TypeError", "ReferenceError", "SyntaxError", "RangeError", "URIError", "EvalError",
        "SecurityError", "NetworkError", "AbortError", "NotAllowedError", "NotSupportedError",
        "QuotaExceededError", "TimeoutError", "DataError", "EncodingError", "UnknownError",
    )

    fun safeChallenge(t: String) = if (t in CHALLENGE_TYPES) t else "none"
    fun safeFormat(t: String) = if (t in FORMATS) t else "desconhecido"
    fun safeDrm(t: String) = if (t in DRM_TYPES) t else "desconhecido"
    fun safeCode(t: String): String {
        val raw = t.trim()
        if (raw in ATTEMPT_CODES) return raw
        val http = Regex("""^HTTP_([1-5][0-9]{2})$""").matchEntire(raw)
        if (http != null) return raw
        val ext = Regex("""^EXTRACTOR_SPECIFIC#[0-9a-f]{4}$""").matchEntire(raw)
        if (ext != null) return raw
        return "EXTRACTOR_ERROR"
    }
    fun safePhotoReason(t: String): String {
        val k = t.lowercase().replace('í', 'i').replace('ã', 'a').replace('ç', 'c').replace(' ', '-')
        val compact = k.replace(Regex("[^a-z0-9-]"), "")
        return if (compact in PHOTO_REASONS) compact else "outro"
    }
    fun safeJsType(t: String) = if (t in JS_TYPES) t else "UnknownError"
    fun yn(v: Boolean) = if (v) "sim" else "não"
    fun yn(v: Boolean?) = when (v) { null -> "desconhecido"; true -> "sim"; false -> "não" }
    fun http(code: Int) = if (code in 100..599) code.toString() else "desconhecido"

    fun suggest(f: Fields): String {
        if (!f.pageLoaded && f.httpCode == 403) return "O site recusou o acesso (HTTP 403)."
        if (!f.pageLoaded && f.htmlDirect.code == "DNS") return "Não foi possível encontrar o site (DNS)."
        if (!f.pageLoaded && f.htmlDirect.code == "SSL") return "Falha de certificado (SSL) ao abrir a página."
        if (!f.pageLoaded && f.htmlDirect.code == "TIMEOUT") return "A página demorou demais para abrir (timeout)."
        if (!f.pageLoaded) return "A página não carregou."
        if (f.drm) return "O vídeo tem DRM e o app não consegue baixar."
        val fmtNow = safeFormat(f.format)
        if (f.pageLoaded && (fmtNow in setOf("MSE", "blob") || (fmtNow == "DASH" && (f.needsSpecialHeader == true || f.challenge)) ||
            (f.challenge && safeChallenge(f.challengeType) == "js_challenge" && (f.videoCount > 0 || f.mediaCapture.ok || f.hiddenAutoplay.ok || f.needsSpecialHeader == true)))) {
            return "O site usa player protegido (MSE/JS) — o app vai baixar pelo navegador (MSE)."
        }
        if (f.challenge) return when (safeChallenge(f.challengeType)) {
            "captcha" -> "O site pede um desafio (captcha) antes de liberar o vídeo."
            "js_challenge" -> "O site pede um desafio em JavaScript antes de liberar o vídeo — o app vai baixar pelo navegador (MSE)."
            "cookie_required" -> "O site exige cookie de sessão para mostrar o vídeo."
            "geo_block" -> "O site bloqueia o acesso por país."
            "age_gate" -> "O site pede verificação de idade antes de liberar o vídeo."
            else -> "O site pede um desafio antes de liberar o vídeo."
        }
        if (safeFormat(f.format) == "HLS" && f.iframeCrossOrigin && f.needsReferer == true)
            return "vídeo em HLS dentro de iframe e precisa de Referer"
        if (safeFormat(f.format) == "DASH" && f.iframeCrossOrigin && f.needsReferer == true)
            return "vídeo em DASH dentro de iframe e precisa de Referer"
        if (f.iframeCrossOrigin && f.needsReferer == true)
            return "vídeo dentro de iframe e precisa de Referer"
        if (f.needsCookie == true) return "O vídeo exige cookie de sessão."
        if (f.needsReferer == true) return "O link do vídeo exige o cabeçalho Referer."
        if (f.needsSpecialHeader == true) return "O vídeo exige um cabeçalho especial (User-Agent)."
        if (f.linkExpires == true) return "O link do vídeo expira rápido."
        if (safeFormat(f.format) in setOf("blob", "MSE") && !f.mediaCapture.ok)
            return "O vídeo é montado no player (MSE/blob) e a captura não pegou o stream."
        if (f.needsClick && !f.hiddenAutoplay.ok)
            return "O vídeo só carrega depois de um toque no play."
        if (f.ytdlp.code == "HTTP_403" && !f.htmlDirect.ok)
            return "O site recusou o acesso (HTTP 403)."
        if (f.ytdlp.code == "UNSUPPORTED" && !f.htmlDirect.ok && !f.mediaCapture.ok)
            return "O servidor não reconhece este tipo de página."
        if (safeFormat(f.format) == "none" && f.videoCount == 0 && !f.mediaCapture.ok)
            return "Não foi encontrado vídeo nesta página."
        if (safeFormat(f.format) == "MP4" && f.needsReferer != true && f.needsCookie != true)
            return "O vídeo está em MP4 direto e não depende de Referer nem cookie."
        if (f.mediaCapture.ok || f.htmlDirect.ok)
            return "Há um stream de vídeo acessível; se o download falhar, veja as tentativas acima."
        return "Não deu pra apontar uma causa única. Veja as tentativas acima."
    }

    fun render(f: Fields): String = buildString {
        appendLine("Baixar pro Telegram v${f.appVersion.filter { it.isLetterOrDigit() || it == '.' }}")
        appendLine("WebView: ${safeWebView(f.webViewVersion)}")
        appendLine()
        appendLine("Página: carregou=${yn(f.pageLoaded)}  HTTP=${http(f.httpCode)}  tempo=${f.loadMs.coerceIn(0, 3_600_000)}ms  redirecionamentos=${f.redirectCount.coerceIn(0, 20)}")
        appendLine()
        if (f.challenge) {
            appendLine("Anti-robô: sim  tipo=${safeChallenge(f.challengeType)}")
            if (safeChallenge(f.challengeType) == "age_gate" || f.ageGateBypassed != null) {
                appendLine("  age_gate: passou automaticamente ${yn(f.ageGateBypassed)}")
            }
        } else appendLine("Anti-robô: não")
        appendLine()
        appendLine("Vídeo:")
        appendLine("  elementos: video=${f.videoCount.coerceIn(0, 999)}  iframe=${f.iframeCount.coerceIn(0, 999)}  source=${f.sourceCount.coerceIn(0, 999)}")
        appendLine("  precisa de toque=${yn(f.needsClick)}")
        if (f.iframeCrossOrigin) {
            val h = f.iframeCdnHash
            val shown = if (h.matches(Regex("""^cdn#[0-9a-f]{4}$"""))) h else "cdn#0000"
            appendLine("  iframe de outro domínio=sim, $shown")
        } else appendLine("  iframe de outro domínio=não")
        appendLine("  formato=${safeFormat(f.format)}")
        appendLine("  link expira=${yn(f.linkExpires)}")
        appendLine("  precisa Referer=${yn(f.needsReferer)}")
        appendLine("  precisa cookie=${yn(f.needsCookie)}")
        appendLine("  precisa cabeçalho especial=${yn(f.needsSpecialHeader)}")
        if (f.drm) appendLine("  DRM=sim  tipo=${safeDrm(f.drmType)}")
        else appendLine("  DRM=não")
        appendLine()
        appendLine("Tentativas:")
        appendLine("  HTML estático: ${f.htmlDirect.line()}")
        appendLine("  navegador oculto (auto-play): ${f.hiddenAutoplay.line()}")
        appendLine("  captura de mídia: ${f.mediaCapture.line()}")
        appendLine("  yt-dlp: ${f.ytdlp.line()}")
        appendLine()
        if (!f.photosOn) appendLine("Fotos: modo=desligado")
        else {
            val rej = f.photoRejects.entries
                .map { safePhotoReason(it.key) to it.value.coerceIn(0, 9999) }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
                .entries.sortedByDescending { it.value }
                .joinToString("; ") { "${it.key}x${it.value}" }
                .ifBlank { "nenhum" }
            appendLine("Fotos: img=${f.imgCount.coerceIn(0, 9999)}  candidatas=${f.photoCands.coerceIn(0, 9999)}  descartes: $rej  erros do script=${f.photoScriptErrors.coerceIn(0, 999)}")
        }
        appendLine()
        if (f.appJsErrors.isEmpty()) appendLine("Erros JS do app: nenhum")
        else {
            val items = f.appJsErrors.take(8).mapNotNull { raw ->
                val m = Regex("""^(${JS_TYPES.joinToString("|")})@L(\d{1,5})$""").matchEntire(raw.trim())
                if (m != null) "${m.groupValues[1]}@L${m.groupValues[2]}" else null
            }
            appendLine("Erros JS do app: " + items.ifEmpty { listOf("UnknownError@L0") }.joinToString(", "))
        }
        if (f.pageJsErrorCounts.isEmpty()) appendLine("Erros JS da página: nenhum")
        else {
            val items = f.pageJsErrorCounts.entries
                .map { safeJsType(it.key) to it.value.coerceIn(0, 9999) }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.sum() }
                .entries.sortedByDescending { it.value }
                .joinToString("; ") { "${it.key}x${it.value}" }
            appendLine("Erros JS da página: $items")
        }
        appendLine()
        appendLine("SUGESTÃO: ${suggest(f)}")
    }

    private fun safeWebView(v: String): String {
        val t = v.trim()
        if (t.isEmpty() || t == "desconhecido") return "desconhecido"
        // major.minor ou major.minor.patch (evita 4 octetos com cara de IP)
        val m = Regex("""^(\d{1,4})(?:\.(\d{1,4}))?(?:\.(\d{1,5}))?""").find(t) ?: return "desconhecido"
        val a = m.groupValues[1]
        val b = m.groupValues[2].ifBlank { "0" }
        return "$a.$b"
    }

    /** Palavras do modelo do relatório: nunca são apagadas pelo filtro de tokens da página. */
    private val RESERVED_WORDS = setOf(
        "baixar", "pro", "telegram", "webview", "pagina", "página", "carregou", "sim", "nao", "não",
        "tempo", "redirecionamentos", "anti", "robo", "robô", "tipo", "captcha", "video", "vídeo",
        "elementos", "iframe", "source", "precisa", "toque", "outro", "dominio", "domínio",
        "formato", "mp4", "hls", "dash", "blob", "mse", "desconhecido", "link", "expira", "referer",
        "cookie", "cabecalho", "cabeçalho", "especial", "drm", "widevine", "playready", "fairplay",
        "tentativas", "html", "estatico", "estático", "falhou", "motivo", "navegador", "oculto",
        "auto", "play", "captura", "midia", "mídia", "fotos", "modo", "desligado", "img",
        "candidatas", "descartes", "erros", "script", "nenhum", "app", "sugestao", "sugestão",
        "site", "recusou", "acesso", "http", "certificado", "ssl", "demorou", "demais", "abrir",
        "timeout", "carregou", "pede", "desafio", "antes", "liberar", "javascript", "exige",
        "sessao", "sessão", "mostrar", "bloqueia", "pais", "país", "verificacao", "verificação",
        "idade", "consegue", "baixar", "dentro", "direto", "depende", "montado", "player",
        "stream", "carrega", "depois", "servidor", "reconhece", "este", "encontrado", "nesta",
        "acessivel", "acessível", "download", "falhar", "veja", "acima", "apontar", "causa",
        "unica", "única", "rapido", "rápido", "pegou", "toque", "recusou", "dns", "conexão",
        "conexao", "network", "connection", "cancelled", "unsupported", "extractor", "generic",
        "specific", "error", "skipped", "media", "none", "removido", "ok",
        "pequena", "icone", "ícone", "miniatura", "capa", "anuncio", "anúncio", "sprite",
        "avatar", "logo", "banner", "lixo", "sem-endereco", "typeerror", "referenceerror",
        "syntaxerror", "rangeerror", "urierror", "evalerror", "securityerror", "networkerror",
        "aborterror", "notallowederror", "notsupportederror", "quotaexceedederror",
        "timeouterror", "dataerror", "encodingerror", "unknownerror",
        "js_challenge", "cookie_required", "geo_block", "age_gate", "passou", "automaticamente",
        "no_media", "extractor_error", "extractor_generic",
    )

    /**
     * Filtro final: tira URL, domínio com TLD, IP, e-mail, caminho, querystring
     * e qualquer token que apareça no título/texto da página.
     */
    fun sanitize(text: String, pageTokens: Set<String> = emptySet()): String {
        // protege versão do app e WebView (números estruturados)
        val held = ArrayList<String>()
        fun park(s: String): String { held += s; return "§H${held.lastIndex}§" }
        var s = text
        s = Regex("""Baixar pro Telegram v[\d.]+""").replace(s) { park(it.value) }
        s = Regex("""WebView: \d+\.\d+""").replace(s) { park(it.value) }
        s = Regex("""tempo=\d+ms""").replace(s) { park(it.value) }
        s = Regex("""HTTP=\d{3}""").replace(s) { park(it.value) }
        s = Regex("""HTTP_\d{3}""").replace(s) { park(it.value) }
        s = Regex("""cdn#[0-9a-f]{4}""").replace(s) { park(it.value) }
        s = Regex("""ext#[0-9a-f]{4}""").replace(s) { park(it.value) }
        s = Regex("""EXTRACTOR_SPECIFIC#[0-9a-f]{4}""").replace(s) { park(it.value) }
        s = Regex("""@L\d{1,5}""").replace(s) { park(it.value) }
        // a linha de sugestão é texto fixo do app (nunca traz palavra da página)
        s = Regex("""SUGESTÃO: [^\n]*""").replace(s) { park(it.value) }

        s = Regex("""(?i)https?://[^\s<>"'')\]]+""").replace(s, "[removido]")
        s = Regex("""(?i)www\.[^\s<>"'')\]]+""").replace(s, "[removido]")
        s = Regex("""(?i)\b[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}\b""").replace(s, "[removido]")
        s = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""").replace(s, "[removido]")
        s = Regex("""(?i)\b(?:[a-z0-9-]+\.)+(?:com|net|org|io|co|tv|me|info|xyz|app|dev|biz|edu|gov|br|uk|us|ru|de|fr|it|es|jp|cn|in|au|ca|nl|pl|ir|ua|tr|kr|mx|ar|cl|pt|ch|se|no|fi|be|at|cz|sk|hu|ro|bg|lt|lv|ee|ie|nz|za|sg|hk|tw|id|ph|vn|th|my|pk|bd|sa|ae|il|eg|ng|ke|online|site|store|shop|cloud|video|media|cdn|live|stream|watch|xxx|mov|pro|cc|to|ws|pw|top|click|link|download|pics|photo|images?|example|test|invalid|local|localhost)\b""")
            .replace(s, "[removido]")
        s = Regex("""[?&][a-z0-9_\-]+=[^\s&]+""", RegexOption.IGNORE_CASE).replace(s, "[removido]")
        s = Regex("""(?<![A-Za-z0-9])/(?:[A-Za-z0-9._~\-]+/)+[A-Za-z0-9._~\-]*""").replace(s, "[removido]")

        val banned = pageTokens.map { it.lowercase() }.filter { it.length >= 3 }.toHashSet()
        if (banned.isNotEmpty()) {
            s = Regex("""[A-Za-z0-9_\-]{3,}""").replace(s) { m ->
                val w = m.value
                val low = w.lowercase()
                if (w.startsWith("§H")) w
                else if (low in RESERVED_WORDS) w
                else if (low in banned) "[removido]"
                else w
            }
        }

        held.forEachIndexed { i, v -> s = s.replace("§H${i}§", v) }
        return s
    }

    /** Tokens do HTML/título/URL usados só na sanitização (nunca entram no relatório). */
    fun tokensOf(pageUrl: String, html: String, title: String = ""): Set<String> {
        val out = HashSet<String>()
        fun addWord(w: String) {
            val t = w.lowercase().trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']')
            if (t.length >= 3) out += t
        }
        fun addUrl(u: String) {
            if (u.isBlank()) return
            addWord(u)
            try {
                val uri = java.net.URI(u)
                (uri.host ?: "").lowercase().removePrefix("www.").split('.').forEach { addWord(it) }
                (uri.path ?: "").split('/').forEach { addWord(it.substringBefore('.')) }
                (uri.query ?: "").split('&').forEach { p ->
                    addWord(p.substringBefore('='))
                    addWord(p.substringAfter('=', ""))
                }
            } catch (_: Exception) {}
        }
        addUrl(pageUrl)
        title.split(Regex("""\s+""")).forEach { addWord(it) }
        // título e texto visível (sem scripts)
        val stripped = html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("&[a-z]+;"), " ")
            .replace(Regex("\\s+"), " ")
        stripped.split(' ').forEach { addWord(it) }
        Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.get(1)?.split(Regex("\\s+"))?.forEach { addWord(it) }
        return out
    }
}
