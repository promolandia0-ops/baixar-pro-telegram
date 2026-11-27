package br.geracaoai.baixartg.core

/**
 * Detetive — o "estudo" do app: como reconhecer cada player/plataforma de vídeo e onde cada um guarda o arquivo.
 * Cada receita diz: como reconhecer (endereço do player/iframe/script) e quais pistas seguir:
 *  - MEDIA: o endereço do vídeo montado direto (ex.: Panda → playlist.m3u8);
 *  - JSON: um arquivo de configuração do player que lista os vídeos (Vimeo /config, Wistia .json, JW Player);
 *  - PAGE: abrir a página do player pela rede (com o login e o Referer certo) e ler os vídeos de dentro;
 *  - SERVER: o servidor yt-dlp conhece o site (YouTube, Drive, Dailymotion…);
 *  - BRIGHTCOVE: API de reprodução com a chave do próprio player.
 * O Referer diz de onde o pedido deve parecer vir: PAGE (página da aula), FRAME (o próprio player), ORIGIN, NONE.
 * Novas receitas podem chegar pela internet (DetRecipes) sem atualizar o app. Puro Kotlin (testável).
 */
object DetKnowledge {
    enum class LeadKind { MEDIA, JSON, PAGE, SERVER, BRIGHTCOVE }
    enum class Ref { PAGE, FRAME, ORIGIN, NONE }
    data class LeadT(val to: String, val kind: LeadKind, val ref: Ref = Ref.PAGE)
    data class Recipe(val id: String, val name: String, val match: Regex, val leads: List<LeadT>, val note: String = "")
    data class Lead(val url: String, val kind: LeadKind, val ref: Ref, val recipe: String)

    private fun r(id: String, name: String, re: String, vararg leads: LeadT, note: String = "") =
        Recipe(id, name, Regex(re, RegexOption.IGNORE_CASE), leads.toList(), note)
    private fun m(to: String, ref: Ref = Ref.FRAME) = LeadT(to, LeadKind.MEDIA, ref)
    private fun j(to: String, ref: Ref = Ref.PAGE) = LeadT(to, LeadKind.JSON, ref)
    private fun p(to: String = "$0", ref: Ref = Ref.PAGE) = LeadT(to, LeadKind.PAGE, ref)
    private fun s(to: String = "$0", ref: Ref = Ref.PAGE) = LeadT(to, LeadKind.SERVER, ref)

    val builtIn: List<Recipe> = listOf(
        // ---- players de curso (mais comuns no Brasil) ----
        r("panda", "Panda Video", """https?://player-vz-([a-z0-9\-]+)\.tv\.pandavideo\.com(?:\.br)?/embed/?\?(?:[^#]*&)?v=([a-z0-9\-]+)""",
            m("https://b-vz-$1.tv.pandavideo.com.br/$2/playlist.m3u8"), m("https://b-vz-$1.tv.pandavideo.com/$2/playlist.m3u8"), p(), s()),
        r("panda-cdn", "Panda Video", """https?://b-vz-([a-z0-9\-]+)\.tv\.pandavideo\.com(?:\.br)?/([a-z0-9\-]+)/""",
            m("https://b-vz-$1.tv.pandavideo.com.br/$2/playlist.m3u8")),
        r("vimeo", "Vimeo", """https?://player\.vimeo\.com/video/(\d+)(?:/?\?(?:[^#]*&)?h=([0-9a-f]+))?""",
            j("https://player.vimeo.com/video/$1/config?h=$2"), j("https://player.vimeo.com/video/$1/config"), p(), s(), note = "Vídeo privado do Vimeo só abre com o endereço da aula como origem."),
        r("vimeo-page", "Vimeo", """https?://(?:www\.)?vimeo\.com/(?:video/)?(\d+)(?:/([0-9a-f]{6,}))?""",
            j("https://player.vimeo.com/video/$1/config?h=$2"), j("https://player.vimeo.com/video/$1/config"), s()),
        r("bunny", "Bunny Stream", """https?://(?:iframe|player)\.mediadelivery\.net/(?:embed|play)/(\d+)/([a-f0-9\-]{36})""",
            p(), s()),
        r("bunny-cdn", "Bunny Stream", """https?://(vz-[a-z0-9\-]+\.b-cdn\.net)/([a-f0-9\-]{36})/""",
            m("https://$1/$2/playlist.m3u8")),
        r("wistia", "Wistia", """https?://(?:fast\.)?wistia\.(?:net|com)/embed/(?:iframe|medias)/([a-z0-9]{6,})""",
            j("https://fast.wistia.com/embed/medias/$1.json", Ref.NONE), m("https://fast.wistia.com/embed/medias/$1.m3u8", Ref.NONE)),
        r("wistia-async", "Wistia", """wistia_async_([a-z0-9]{6,})""",
            j("https://fast.wistia.com/embed/medias/$1.json", Ref.NONE), m("https://fast.wistia.com/embed/medias/$1.m3u8", Ref.NONE)),
        r("jw", "JW Player", """https?://(?:cdn|content)\.jwplayer\.com/(?:players|previews|v2/media|manifests|videos)/([a-zA-Z0-9]{8})""",
            j("https://cdn.jwplayer.com/v2/media/$1", Ref.NONE), m("https://cdn.jwplayer.com/manifests/$1.m3u8", Ref.NONE)),
        r("vturb", "Vturb / ConverteAI", """https?://scripts\.converteai\.net/([a-z0-9\-]+)/players/([a-z0-9]+)(?:/[^"'\s]*)?""",
            p("https://scripts.converteai.net/$1/players/$2/player.js"), p("https://scripts.converteai.net/$1/players/$2/embed.html")),
        r("hotmart", "Hotmart", """https?://(?:player|contentplayer|play)\.hotmart\.com/[^\s"'<>]+""", p(), s()),
        r("spotlightr", "Spotlightr", """https?://(?:videos\.cdn\.spotlightr\.com|[a-z0-9\-]+\.spotlightr\.com)/(?:watch|embed)/[^\s"'<>]+""", p()),
        r("sprout", "SproutVideo", """https?://videos\.sproutvideo\.com/embed/[a-f0-9]+/[a-f0-9]+""", p()),
        r("scaleup", "ScaleUp", """https?://(?:player|play)\.scaleup\.com\.br/[^\s"'<>]+""", p()),
        r("smartplayer", "SmartPlayer", """https?://(?:player|cdn)\.smartplayer\.io/[^\s"'<>]+""", p()),
        r("cademi", "Cademí", """https?://[a-z0-9\-]+\.cademi\.com\.br/[^\s"'<>]*(?:player|embed|video)[^\s"'<>]*""", p(), s()),
        r("memberkit", "Memberkit", """https?://[a-z0-9\-.]*memberkit\.com\.br/[^\s"'<>]*(?:player|embed|video)[^\s"'<>]*""", p()),
        r("voomly", "Voomly", """https?://embed\.voomly\.com/embed/[^\s"'<>]+""", p()),
        // ---- APIs/CDNs com endereço previsível ----
        r("cloudflare", "Cloudflare Stream", """https?://(customer-[a-z0-9]+\.cloudflarestream\.com|iframe\.cloudflarestream\.com|iframe\.videodelivery\.net|videodelivery\.net|watch\.cloudflarestream\.com)/([a-f0-9]{32})""",
            m("https://videodelivery.net/$2/manifest/video.m3u8"), m("https://$1/$2/manifest/video.m3u8")),
        r("mux", "Mux", """https?://(?:stream|player|image)\.mux\.com/([A-Za-z0-9]{20,})""", m("https://stream.mux.com/$1.m3u8", Ref.NONE)),
        r("mux-attr", "Mux", """playback-id=["']?([A-Za-z0-9]{20,})""", m("https://stream.mux.com/$1.m3u8", Ref.NONE)),
        r("kaltura", "Kaltura", """(?:/p/|partner_?id["'=:/\s]+)(\d{3,})[\s\S]{0,400}?entry_?id["'=:/\s]+([01]_[a-z0-9]{8})""",
            m("https://cdnapisec.kaltura.com/p/$1/sp/$100/playManifest/entryId/$2/format/applehttp/protocol/https/a.m3u8", Ref.NONE),
            m("https://cdnapisec.kaltura.com/p/$1/sp/$100/playManifest/entryId/$2/format/url/protocol/https/a.mp4", Ref.NONE)),
        r("brightcove", "Brightcove", """https?://players\.brightcove\.net/(\d+)/([A-Za-z0-9\-]+)_([a-z]+)/index\.html\?(?:[^#]*&)?videoId=(?:ref:)?([A-Za-z0-9\-_]+)""",
            LeadT("$1|$2|$3|$4", LeadKind.BRIGHTCOVE, Ref.PAGE), s()),
        r("vidyard", "Vidyard", """https?://(?:play|embed)\.vidyard\.com/(?:watch/)?([A-Za-z0-9_\-]{10,})""", j("https://play.vidyard.com/player/$1.json", Ref.NONE), s()),
        r("loom", "Loom", """https?://(?:www\.)?loom\.com/(?:share|embed)/([a-f0-9]{32})""", s("https://www.loom.com/share/$1")),
        // ---- sites que o servidor yt-dlp conhece ----
        r("youtube", "YouTube", """https?://(?:www\.|m\.)?(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|shorts/|live/|v/)|youtu\.be/)([A-Za-z0-9_\-]{11})""", s("https://www.youtube.com/watch?v=$1")),
        r("drive", "Google Drive", """https?://(?:drive|docs)\.google\.com/(?:file/d/|open\?id=|uc\?(?:[^#]*&)?id=)([A-Za-z0-9_\-]{20,})""", s("https://drive.google.com/file/d/$1/view")),
        r("dailymotion", "Dailymotion", """https?://(?:www\.)?(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/|geo\.dailymotion\.com/player[^"'\s]*video=)([a-z0-9]+)""", s("https://www.dailymotion.com/video/$1")),
        r("streamable", "Streamable", """https?://(?:www\.)?streamable\.com/(?:e/|o/)?([a-z0-9]+)""", s("https://streamable.com/$1")),
        r("facebook", "Facebook", """https?://(?:www\.|m\.)?facebook\.com/(?:plugins/video\.php\?[^"'\s]+|[^/\s"']+/videos/\d+|watch/?\?v=\d+|reel/\d+)""", s()),
        r("instagram", "Instagram", """https?://(?:www\.)?instagram\.com/(?:p|reel|tv)/[A-Za-z0-9_\-]+""", s()),
        r("tiktok", "TikTok", """https?://(?:www\.)?tiktok\.com/(?:@[^/\s"']+/video/\d+|embed(?:/v2)?/\d+)""", s()),
        r("twitch", "Twitch", """https?://(?:clips\.|player\.)?twitch\.tv/[^\s"'<>]+""", s()),
        r("rumble", "Rumble", """https?://rumble\.com/embed/[a-z0-9]+""", s()),
        r("odysee", "Odysee", """https?://odysee\.com/\$/embed/[^\s"'<>]+""", s()),
        r("x", "X / Twitter", """https?://(?:x|twitter)\.com/[^/\s"']+/status/\d+""", s()),
        r("soundcloud", "SoundCloud", """https?://(?:w\.)?soundcloud\.com/(?:player/\?url=)?[^\s"'<>]+""", s()),
    )

    @Volatile private var remote: List<Recipe> = emptyList()
    @Volatile private var remoteHosts: List<Regex> = emptyList()
    @Volatile var remoteGlobals: List<String> = emptyList()
    fun setRemote(rs: List<Recipe>, hosts: List<Regex>, globals: List<String>) { remote = rs; remoteHosts = hosts; remoteGlobals = globals }
    val remoteCount get() = remote.size
    fun all(): List<Recipe> = remote + builtIn

    fun recipeFor(u: String): Recipe? = all().firstOrNull { it.match.containsMatchIn(u) && it.id != "kaltura" && it.id != "mux-attr" && it.id != "wistia-async" }

    /** Pistas (endereços a testar) a partir de um endereço de player ou de um trecho de HTML. */
    fun leads(text: String, only: Collection<Recipe> = all()): List<Lead> {
        val out = LinkedHashMap<String, Lead>()
        for (rc in only) for (mm in rc.match.findAll(text).take(8)) {
            for (lt in rc.leads) {
                var u = lt.to
                for (gi in 9 downTo 1) u = u.replace("$" + gi, mm.groupValues.getOrNull(gi).orEmpty())
                u = u.replace("$0", mm.value)
                u = u.replace(Regex("""[?&]h=(?=$|&)"""), "")   // grupo opcional vazio (Vimeo sem hash)
                if (lt.kind != LeadKind.BRIGHTCOVE && !u.startsWith("http")) continue
                if (lt.kind != LeadKind.BRIGHTCOVE && Regex("""//[^/]*\$|/\$\d""").containsMatchIn(u)) continue
                val k = lt.kind.name + "|" + u
                if (!out.containsKey(k)) out[k] = Lead(u, lt.kind, lt.ref, rc.id)
            }
        }
        return out.values.toList()
    }

    // ---------------- reconhecer players e plataformas pelo código da página ----------------

    private val MARKERS: List<Pair<String, Regex>> = listOf(
        "Panda Video" to Regex("""pandavideo""", RegexOption.IGNORE_CASE),
        "Vimeo" to Regex("""player\.vimeo\.com|vimeocdn""", RegexOption.IGNORE_CASE),
        "YouTube" to Regex("""youtube(?:-nocookie)?\.com/(?:embed|iframe_api)|ytimg\.com|youtu\.be/""", RegexOption.IGNORE_CASE),
        "Bunny Stream" to Regex("""mediadelivery\.net|b-cdn\.net""", RegexOption.IGNORE_CASE),
        "Wistia" to Regex("""wistia""", RegexOption.IGNORE_CASE),
        "JW Player" to Regex("""jwplayer|jwpcdn|jwplatform""", RegexOption.IGNORE_CASE),
        "Video.js" to Regex("""video-js|videojs""", RegexOption.IGNORE_CASE),
        "Plyr" to Regex("""\bplyr\b""", RegexOption.IGNORE_CASE),
        "Clappr" to Regex("""clappr""", RegexOption.IGNORE_CASE),
        "Flowplayer" to Regex("""flowplayer""", RegexOption.IGNORE_CASE),
        "MediaElement" to Regex("""mediaelement|mejs__""", RegexOption.IGNORE_CASE),
        "Shaka Player" to Regex("""shaka-player|shaka\.Player""", RegexOption.IGNORE_CASE),
        "dash.js" to Regex("""dash\.all\.min|dashjs""", RegexOption.IGNORE_CASE),
        "hls.js" to Regex("""hls\.min\.js|hls\.js|Hls\.isSupported""", RegexOption.IGNORE_CASE),
        "Bitmovin" to Regex("""bitmovin""", RegexOption.IGNORE_CASE),
        "THEOplayer" to Regex("""theoplayer""", RegexOption.IGNORE_CASE),
        "Vturb / ConverteAI" to Regex("""converteai|vturb|smartplayer""", RegexOption.IGNORE_CASE),
        "Hotmart" to Regex("""hotmart""", RegexOption.IGNORE_CASE),
        "Kiwify" to Regex("""kiwify""", RegexOption.IGNORE_CASE),
        "Memberkit" to Regex("""memberkit""", RegexOption.IGNORE_CASE),
        "Cademí" to Regex("""cademi\.com""", RegexOption.IGNORE_CASE),
        "Nutror / Eduzz" to Regex("""nutror|eduzz""", RegexOption.IGNORE_CASE),
        "Greenn" to Regex("""greenn""", RegexOption.IGNORE_CASE),
        "Curseduca" to Regex("""curseduca""", RegexOption.IGNORE_CASE),
        "HeroSpark" to Regex("""herospark""", RegexOption.IGNORE_CASE),
        "Astron Members" to Regex("""astronmembers""", RegexOption.IGNORE_CASE),
        "Kajabi" to Regex("""kajabi""", RegexOption.IGNORE_CASE),
        "Teachable" to Regex("""teachable""", RegexOption.IGNORE_CASE),
        "Thinkific" to Regex("""thinkific""", RegexOption.IGNORE_CASE),
        "LearnWorlds" to Regex("""learnworlds""", RegexOption.IGNORE_CASE),
        "Podia" to Regex("""podia\.com""", RegexOption.IGNORE_CASE),
        "Skool" to Regex("""skool\.com""", RegexOption.IGNORE_CASE),
        "Moodle" to Regex("""moodle""", RegexOption.IGNORE_CASE),
        "Udemy" to Regex("""udemy""", RegexOption.IGNORE_CASE),
        "Brightcove" to Regex("""brightcove""", RegexOption.IGNORE_CASE),
        "Kaltura" to Regex("""kaltura""", RegexOption.IGNORE_CASE),
        "Cloudflare Stream" to Regex("""cloudflarestream|videodelivery\.net""", RegexOption.IGNORE_CASE),
        "Mux" to Regex("""mux\.com|mux-player""", RegexOption.IGNORE_CASE),
        "Vidyard" to Regex("""vidyard""", RegexOption.IGNORE_CASE),
        "SproutVideo" to Regex("""sproutvideo""", RegexOption.IGNORE_CASE),
        "Spotlightr" to Regex("""spotlightr""", RegexOption.IGNORE_CASE),
        "Loom" to Regex("""loom\.com/(?:share|embed)""", RegexOption.IGNORE_CASE),
        "Dailymotion" to Regex("""dailymotion""", RegexOption.IGNORE_CASE),
        "Google Drive" to Regex("""drive\.google\.com/file""", RegexOption.IGNORE_CASE),
    )

    fun identify(text: String): List<String> = MARKERS.filter { it.second.containsMatchIn(text) }.map { it.first }.distinct()

    // ---------------- hosts de mídia (endereços sem extensão que podem ser vídeo) ----------------

    private val MEDIA_HOSTS = Regex(
        """(?i)//[^/]*(?:vimeocdn\.com|pandavideo\.com|b-cdn\.net|mediadelivery\.net|akamaihd\.net|akamaized\.net|cloudfront\.net|cloudflarestream\.com|videodelivery\.net|mux\.com|wistia\.(?:com|net)|jwpcdn\.com|jwplayer\.com/(?:manifests|videos)|brightcovecdn|boltdns\.net|kaltura\.com|googlevideo\.com|converteai\.net|vod[a-z0-9\-]*\.hotmart|spotlightr\.com|sproutvideo\.com|vidyard\.com|llnwd\.net|edgesuite\.net|fastly\.net|bunnycdn|streamable\.com|cdn\.jwplayer|vz-[a-z0-9\-]+\.b-cdn)""")
    fun isMediaHost(u: String): Boolean = MEDIA_HOSTS.containsMatchIn(u) || remoteHosts.any { it.containsMatchIn(u) }

    // ---------------- variáveis globais onde players guardam a config ----------------

    val globals: List<String> get() = (listOf(
        "playerConfig", "__NEXT_DATA__", "__NUXT__", "__NUXT_DATA__", "__INITIAL_STATE__", "__APOLLO_STATE__", "__PRELOADED_STATE__",
        "__remixContext", "__INITIAL_DATA__", "__DATA__", "__STATE__", "__APP_STATE__", "ytInitialPlayerResponse", "flashvars",
        "videoData", "vData", "playerData", "PLAYER_CONFIG", "player_config", "videoConfig", "VIDEO_CONFIG", "lessonData", "lesson",
        "aula", "currentLesson", "course", "_wq", "dataLayer", "smartplayer", "vturb", "pandaPlayer", "PandaPlayer", "jwConfig",
        "sources", "videoSources", "mediaData", "contentData", "pageData", "__sveltekit_data", "__staticRouterHydrationData",
    ) + remoteGlobals).distinct()

    // ---------------- login, aulas, DRM ----------------

    private val LOGIN_URL = Regex("""(?i)/(?:login|log-in|signin|sign-in|sign_in|entrar|acessar|acesso|auth(?:enticate)?|session/new|users/sign_in|account/login|minha-conta/entrar|conta/entrar|oauth|sso)(?:$|[/?#.])|[?&](?:redirect|return(?:_to|Url)?|next)=""")
    fun isLoginUrl(u: String): Boolean = LOGIN_URL.containsMatchIn(u)

    private val LESSON_HREF = Regex("""(?i)/(?:aula|aulas|lesson|lessons|lecture|lectures|licao|modulo|modulos|module|modules|capitulo|chapter|episodio|episode|class|classes|conteudo|content|player|watch|video|videos|curso/[^/]+/[^/]+|course/[^/]+/[^/]+)(?:$|[/?#\-_])""")
    private val LESSON_TEXT = Regex("""(?i)\b(?:aula|lesson|lecture|li[cç][aã]o|m[oó]dulo|module|cap[ií]tulo|chapter|epis[oó]dio|episode|assistir|watch|play|v[ií]deo)\b""")
    fun lessonScore(href: String, text: String): Int =
        (if (LESSON_HREF.containsMatchIn(href)) 2 else 0) + (if (LESSON_TEXT.containsMatchIn(text)) 1 else 0) +
            (if (Regex("""(?i)\b\d{1,3}\b""").containsMatchIn(text)) 1 else 0) - (if (isLoginUrl(href)) 5 else 0)

    private val DRM_TEXT = Regex("""(?i)widevine|playready|fairplay|drmtoday|ezdrm|axinom|pallycon|vualto|expressplay|licen[cs]e\.(?:vdocipher|pallycon)|vdocipher""")
    fun drmHint(text: String): Boolean = DRM_TEXT.containsMatchIn(text)

    /** Referer para cada estratégia. */
    fun refFor(ref: Ref, page: String, frame: String?): String? = when (ref) {
        Ref.PAGE -> page
        Ref.FRAME -> frame ?: page
        Ref.ORIGIN -> originOf(frame ?: page)
        Ref.NONE -> null
    }

    fun originOf(u: String): String? = try { java.net.URI(u).let { if (it.scheme == null || it.authority == null) null else "${it.scheme}://${it.authority}/" } } catch (_: Exception) { null }

    /** Ordem das tentativas de Referer para um endereço de mídia: o que a receita pede primeiro, depois os outros. */
    fun refererOrder(first: Ref?, page: String, frame: String?): List<String?> {
        val order = listOfNotNull(first) + listOf(Ref.FRAME, Ref.PAGE, Ref.ORIGIN, Ref.NONE)
        val out = ArrayList<String?>()
        for (r in order) { val v = refFor(r, page, frame); if (v !in out) out += v }
        originOf(page)?.let { if (it !in out) out.add(out.size - 1, it) }
        return out
    }
}
