package br.geracaoai.baixartg.core

// expressões fixas compiladas uma vez (antes: recompiladas a cada chamada)
private val RX_TITLEFIX_1 = Regex("""\s+""")
private val RX_TITLEFIX_2 = Regex("""\s+""")
private val RX_TITLEFIX_3 = Regex("""\s*[\(\[].*?[\)\]]\s*""")
private val RX_TITLEFIX_4 = Regex("""\s+""")
private val RX_TITLEFIX_5 = Regex("""\s+""")
private val RX_TITLEFIX_6 = Regex("""\s+[|–—·•]\s+""")
private val RX_TITLEFIX_7 = Regex("""(?i)\.(vtt|srt|ass|ssa|ttml|dfxp)(?:$|[?#])|/(?:subtitles?|captions?|legendas?|subs)/""")


/** Rejeita "títulos" que são rótulos de player: idiomas de áudio/legenda, qualidade, menus, nomes de arquivo genéricos. */
object TitleFix {
    private val LANGS = setOf(
        "english", "ingles", "inglés", "anglais", "englisch", "deutsch", "german", "alemao", "alemão", "allemand", "aleman", "alemán",
        "francais", "français", "french", "frances", "francês", "francés", "franzosisch", "französisch",
        "italiano", "italian", "italien", "italienisch", "espanol", "español", "spanish", "espanhol", "espagnol", "spanisch", "castellano",
        "portugues", "português", "portuguese", "portugais", "portugiesisch", "brasileiro", "pt-br", "pt br", "portugues brasil", "português (brasil)",
        "nederlands", "dutch", "polski", "polish", "russian", "русский", "russkij", "japanese", "日本語", "nihongo", "chinese", "中文", "简体中文", "繁體中文",
        "korean", "한국어", "arabic", "العربية", "turkish", "türkçe", "turkce", "svenska", "swedish", "dansk", "danish", "norsk", "norwegian",
        "suomi", "finnish", "čeština", "cestina", "czech", "magyar", "hungarian", "română", "romana", "romanian", "ελληνικά", "greek",
        "hindi", "हिन्दी", "हिंदी", "indonesian", "bahasa indonesia", "vietnamese", "tiếng việt", "thai", "ไทย", "hebrew", "עברית", "ukrainian", "українська",
        "catala", "català", "galego", "euskara", "latino", "latin", "multi", "multilanguage", "dual audio", "dublado", "legendado", "dubbed", "original",
    )
    private val GENERIC = setOf(
        "arquivo", "video", "vídeo", "videos", "vídeos", "player", "audio", "áudio", "legendas", "legenda", "subtitles", "subtitle", "captions", "caption",
        "cc", "auto", "automatico", "automático", "off", "on", "desativado", "desligado", "default", "padrao", "padrão", "main", "stream", "playlist",
        "index", "master", "media", "mídia", "midia", "file", "download", "baixar", "play", "reproduzir", "pause", "settings", "configuracoes", "configurações",
        "quality", "qualidade", "speed", "velocidade", "normal", "fullscreen", "tela cheia", "mute", "unmute", "volume", "live", "ao vivo", "watch", "assistir",
        "embed", "source", "fonte", "hd", "sd", "fhd", "uhd", "4k", "hq", "lq", "chapters", "capitulos", "capítulos", "share", "compartilhar",
    )
    private val QUALITY = Regex("""(?i)^(?:\d{3,4}p\d{0,2}|[1-8]k|\d{3,4}x\d{3,4}|\d+(?:\.\d+)?\s*(?:kbps|mbps|mb|gb|kb)|(?:low|medium|high|best|worst)(?:\s*quality)?)$""")
    private val FILEISH = Regex("""(?i)^[\w.\-]+\.(?:mp4|m3u8|mpd|webm|mkv|mov|m4v|ts|vtt|srt|php|html?)$|^[0-9a-f]{12,}$|^[\w\-]{20,}$""")

    private fun n(s: String) = NameFilter.norm(s).replace(RX_TITLEFIX_1, " ").trim()
    private val LANGS_N by lazy { LANGS.map { n(it) }.toHashSet() }
    private val GENERIC_N by lazy { GENERIC.map { n(it) }.toHashSet() }

    fun isJunk(t0: String?): Boolean {
        val t = t0?.replace(RX_TITLEFIX_2, " ")?.trim().orEmpty()
        if (t.length < 2) return true
        val x = n(t)
        val noParen = n(t.replace(RX_TITLEFIX_3, " "))
        if (x in LANGS_N || noParen in LANGS_N || x in GENERIC_N || noParen in GENERIC_N) return true
        if (QUALITY.matches(t)) return true
        if (FILEISH.matches(t)) return true
        // lista de idiomas/menus colada ("Deutsch Francais English Italiano")
        val words = x.split(' ').filter { it.isNotBlank() }
        if (words.size in 2..20 && words.all { it in LANGS_N || it in GENERIC_N || QUALITY.matches(it) }) return true
        return false
    }

    /** Remove trechos-lixo de um texto de contexto (mantém o resto). */
    fun clean(t: String?): String {
        val s = t?.replace(RX_TITLEFIX_4, " ")?.trim().orEmpty()
        if (s.isEmpty() || isJunk(s)) return ""
        return s
    }

    /** Primeiro candidato que não é lixo. */
    fun pick(vararg c: String?): String = c.firstOrNull { !isJunk(it) }?.replace(RX_TITLEFIX_5, " ")?.trim()?.take(150).orEmpty()

    /** "Título | Site" -> "Título". */
    fun stripSite(t: String, site: String = ""): String {
        var s = t.trim()
        if (site.length > 2) s = s.replace(Regex("""\s*[|\-–—:·•]\s*""" + Regex.escape(site) + """\s*$""", RegexOption.IGNORE_CASE), "")
        val parts = s.split(RX_TITLEFIX_6)
        if (parts.size >= 2 && parts.last().length <= 30) s = parts.dropLast(1).joinToString(" | ")
        return s.trim()
    }

    fun isSubtitleUrl(u: String) = RX_TITLEFIX_7.containsMatchIn(u)
}
