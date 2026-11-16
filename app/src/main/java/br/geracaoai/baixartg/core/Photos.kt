package br.geracaoai.baixartg.core

import org.json.JSONArray
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Fotos de verdade postadas na página (ex.: diagramas de escala) — opcional ("Fotos também").
 * Nunca miniatura/capa de vídeo: fica de fora toda imagem dentro de link (a não ser link direto para a imagem grande),
 * dentro de player/card de vídeo, poster, og:image, avatar/logo/ícone, anúncio, e menor que 500 px no lado menor.
 * Totalmente separado do fluxo de vídeos.
 */
object Photos {
    data class Photo(val url: String, val text: String, val w: Int, val h: Int)

    const val MIN_SIDE = 500

    val BAD_URL = Regex("""(?i)(?:^|[/_.=\-])(?:thumbs?|thumbnails?|posters?|previews?|covers?|sprites?|storyboards?|avatars?|gravatar|logos?|icons?|favicon|emojis?|badges?|banners?|placeholders?|blank|spacer|pixel)(?=$|[/_.=\-\d])|/vi/|/vi_webp/|hqdefault|mqdefault|sddefault|maxresdefault|/ads?/|doubleclick|adserver""")
    private val SIZE_SUFFIX = Regex("""(?i)[-_@](\d{2,4}x\d{2,4}|\d{2,4}w|\d)x?(?=\.(jpe?g|png|webp|gif|avif)$)""")
    private val SIZE_PARAMS = setOf("w", "h", "width", "height", "resize", "fit", "quality", "q", "crop", "size", "s", "dpr", "auto", "format", "fm", "ver", "v")

    /** Chave da foto: URL sem sufixo de tamanho (-300x200, @2x) e sem parâmetros de redimensionamento. */
    fun key(u: String): String {
        val noFrag = u.substringBefore('#')
        val base = noFrag.substringBefore('?').lowercase().replace("://www.", "://").let { SIZE_SUFFIX.replace(it, "") }
        val q = noFrag.substringAfter('?', "").split('&').filter { it.isNotBlank() && it.substringBefore('=').lowercase() !in SIZE_PARAMS }.sorted()
        return "ph:" + base + (if (q.isEmpty()) "" else "?" + q.joinToString("&"))
    }

    /** Pedaços de id nos links/arquivos dos vídeos detectados (para descartar a miniatura do mesmo vídeo). */
    fun idTokens(u: String): Set<String> {
        val path = u.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/')
        return path.split('/', '-', '_', '.', '=').filter { it.length >= 6 && it.any(Char::isDigit) || it.length >= 10 }.map { it.lowercase() }.toSet()
    }
    fun sharesId(photoUrl: String, videoTokens: Set<String>): Boolean {
        if (videoTokens.isEmpty()) return false
        val p = photoUrl.substringBefore('?').lowercase()
        return idTokens(p).any { it in videoTokens }
    }

    fun badUrl(u: String) = BAD_URL.containsMatchIn(u.substringBefore('?')) || u.startsWith("data:") || u.startsWith("blob:") ||
        Extract.isVideoThumb(u) || u.contains(".svg", true) || u.contains(".gif", true)

    /**
     * Coleta no navegador (DOM renderizado). Leve e incremental: cada imagem/card é decidido uma vez (marcado no
     * próprio elemento); imagens ainda carregando são olhadas de novo; nada roda se a página não mudou.
     * Tamanho real: com srcset o navegador informa a largura da TELA (naturalWidth corrigido pela densidade, ~400 no
     * celular) — por isso toda foto boa caía em "pequena". Agora usa a largura declarada do maior candidato do srcset,
     * ou deixa sem tamanho (o app mede o arquivo de verdade).
     */
    const val JS = """(function(){
      var out=[],cards=[],rej={};
      var W=window,D=document,imgs=D.images,NI=imgs.length,NA=D.links.length;
      var ST=W.__bptPh;
      if(!ST||ST.u!==location.href){ST={u:location.href,dirty:1,pend:0};W.__bptPh=ST}
      if(!W.__bptPhMO){W.__bptPhMO=1;
        try{new MutationObserver(function(){if(W.__bptPh)W.__bptPh.dirty=1}).observe(D.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','srcset','data-src','data-srcset','href','class']})}catch(x){W.__bptPhNoMO=1}
        try{D.addEventListener('load',function(e){var t=e.target;if(t&&t.tagName==='IMG'&&W.__bptPh)W.__bptPh.dirty=1},true)}catch(x){}
      }
      if(!ST.dirty&&!ST.pend&&ST.ni===NI&&ST.na===NA&&!W.__bptPhNoMO)return JSON.stringify({p:[],c:[],r:{},n:NI,same:1});
      ST.dirty=0;ST.ni=NI;ST.na=NA;
      var T0=Date.now();function over(){return Date.now()-T0>40}
      function small(e){return !e.childElementCount||e.childElementCount<=12}
      function R(k){rej[k]=(rej[k]||0)+1}
      function cls(e){try{var c=e.className;if(c&&c.baseVal!==undefined)c=c.baseVal;return (typeof c==='string'?c:'')+' '+(e.id||'')}catch(x){return ''}}
      function tc(e,n){try{return ((e&&e.textContent)||'').replace(/\s+/g,' ').trim().slice(0,n)}catch(x){return ''}}
      var VID=/(duration|(^|[\s_-])play|video|vjs|watch|player)/i, DUR=/(^|\s)(\d{1,2}:)?\d{1,2}:\d{2}(\s|$)/;
      var VSEL='video,iframe,[class*=duration],[class*=play-icon],[class*=playicon],[class*=btn-play],[class*=play-button],svg[class*=play]';
      function videoCard(el){
        var e=el,d=0;
        while(e&&d<2){
          if(VID.test(cls(e)))return true;
          if(d===0||small(e)){
            try{if(e.querySelector&&e.querySelector(VSEL))return true}catch(x){}
            if(DUR.test(tc(e,200)))return true;
          }
          e=e.parentElement;d++}
        return false;
      }
      var bad={},h1t='';
      try{
        D.querySelectorAll('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"],link[rel=image_src]').forEach(function(m){var u=m.content||m.href;if(u)bad[u.split('?')[0]]=1});
        D.querySelectorAll('video[poster]').forEach(function(v){var u=v.poster;if(u)bad[u.split('?')[0]]=1});
        var h1=D.querySelector('h1');h1t=tc(h1,200);
      }catch(x){}
      // só o elemento e o pai (antes: 5 níveis, e "icon"/"post-thumbnail" derrubavam galerias e a foto do post)
      var PLAYER=/(player|(^|[\s_-])video|vjs|jwplayer|plyr|flowplayer|mejs|thumb|poster|preview|avatar|logo|emoji|badge|sponsor|advert|(^|[\s_-])ads?([\s_-]|$)|banner|related|duration|(^|[\s_-])play)/i;
      var NOTTHUMB=/post-thumbnail|wp-post-image|has-post-thumbnail/gi;
      var IMGRE=/\.(jpe?g|png|webp|avif)(\?|#|$)/i;
      function pick(ss,cur){var u=cur.u,bw=cur.w;(ss||'').split(',').forEach(function(p){var a=p.trim().split(/\s+/);if(!a[0])return;var d=a[1]||'';var w=/w$/i.test(d)?(parseInt(d)||1):1;if(w>=bw){bw=w;u=a[0]}});return {u:u,w:bw}}
      function best(img){
        var c={u:'',w:0};
        c=pick(img.getAttribute('srcset')||img.getAttribute('data-srcset')||'',c);
        if(!c.u){try{var pic=img.closest('picture');if(pic)pic.querySelectorAll('source').forEach(function(s){c=pick(s.getAttribute('srcset')||s.getAttribute('data-srcset')||'',c)})}catch(x){}}
        var ds=img.getAttribute('data-full')||img.getAttribute('data-large')||img.getAttribute('data-orig-file')||img.getAttribute('data-large-file')||img.getAttribute('data-original')||img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-lazy')||img.getAttribute('data-url');
        var big=img.getAttribute('data-full')||img.getAttribute('data-large')||img.getAttribute('data-orig-file')||img.getAttribute('data-large-file');
        var u=big||c.u||ds||img.currentSrc||img.src||'';
        try{return {u:new URL(u,location.href).href,w:(big||!c.u)?0:c.w,ss:!!c.u}}catch(e){return {u:'',w:0,ss:false}}
      }
      function ctxText(img){
        var t=[img.alt||'',img.title||''];
        try{var fig=img.closest('figure');if(fig){var fc=fig.querySelector('figcaption');if(fc)t.push(tc(fc,300))}}catch(x){}
        var p=img.parentElement,n=0;
        while(p&&n<4){try{var h=p.querySelector('h1,h2,h3,h4');if(h){t.push(tc(h,200));break}}catch(x){}p=p.parentElement;n++}
        t.push(h1t);
        if(!h1t&&D.title)t.push(D.title);
        return t.join(' ').replace(/\s+/g,' ').trim().slice(0,400);
      }
      /** null = ainda carregando (olha de novo depois); string = motivo de descarte; objeto = foto. */
      function decide(img){
        if(img.closest('video,iframe,nav,footer,aside,[role=banner],[role=navigation]'))return 'menu/rodapé';
        var hd=img.closest('header');if(hd&&!hd.closest('article,main,[role=main]'))return 'menu/rodapé';
        var a=img.closest('a[href]');var bigLink=!!(a&&IMGRE.test(a.href));
        if(!bigLink){var e=img,d=0;while(e&&d<2){if(PLAYER.test(cls(e).replace(NOTTHUMB,' ')))return 'player/miniatura';e=e.parentElement;d++}}
        var e2=img.parentElement,d2=1;while(e2&&d2<=2){if(small(e2)&&e2.querySelector&&e2.querySelector(VSEL))return 'perto de vídeo';e2=e2.parentElement;d2++}
        if(a&&!bigLink)return 'dentro de link (card)';
        var b=best(img);var u=bigLink?a.href:b.u;
        if(!u)return 'sem endereço';
        if(bad[u.split('?')[0]])return 'capa/og:image';
        var al=(img.alt||'')+' '+cls(img).replace(NOTTHUMB,' ');
        if(/(avatar|logo|emoji|profile|perfil)/i.test(al))return 'avatar/logo';
        if(!bigLink&&/(thumbnail|miniatura|capa|cover|poster)/i.test(al))return 'capa/miniatura';
        var cur=img.currentSrc||img.src||'';
        var lazy=!!(img.getAttribute('data-src')||img.getAttribute('data-srcset')||img.getAttribute('data-original')||img.getAttribute('data-lazy')||img.getAttribute('data-lazy-src'));
        var placeholder=!cur||/^data:/.test(cur);
        if(!img.complete&&!placeholder){var t0=img.__bptW||(img.__bptW=Date.now());if(Date.now()-t0<5000)return null}
        var nw=img.naturalWidth||0,nh=img.naturalHeight||0,w=0,h=0;
        if(!placeholder&&nw>0){
          if(b.w>10&&!bigLink){w=b.w;h=Math.round(b.w*nh/nw)}
          else if(!b.ss&&cur.split('?')[0]===u.split('?')[0]){w=nw;h=nh}
        }
        if(w>0&&Math.min(w,h)<500)return 'pequena (<500px)';
        return {u:u,t:ctxText(img),w:w,h:h};
      }
      var pend=0,more=false;
      for(var i=0;i<imgs.length&&out.length<300;i++){
        var img=imgs[i];
        var sig=(img.currentSrc||img.src||'')+'|'+(img.getAttribute('srcset')||'')+'|'+(img.getAttribute('data-src')||'');
        if(img.__bptD===sig)continue;
        if(over()){more=true;break}
        try{
          var r=decide(img);
          if(r===null){pend++;continue}
          img.__bptD=sig;
          if(typeof r==='string')R(r);else out.push(r);
        }catch(x){img.__bptD=sig;R('erro')}
      }
      var as=D.links;
      for(var j=0;j<as.length&&cards.length<120;j++){
        var A=as[j];var hr0=(A.href||'').split('#')[0];
        if(A.__bptC===hr0)continue;
        if(over()){more=true;break}
        try{
          var im=A.querySelector('img');if(!im){A.__bptC=hr0;continue}
          A.__bptC=hr0;
          if(A.closest('header,nav,footer,aside,[role=banner],[role=navigation]'))continue;
          var hr=hr0; if(!/^https?:/.test(hr)||IMGRE.test(hr)||hr===location.href.split('#')[0])continue;
          var U=new URL(hr);
          if(U.host!==location.host)continue;
          if(/\/(video|videos|watch|embed|v|tag|tags|category|categories|search|login|user|users|profile|channel)s?(\/|\?|$)/i.test(U.pathname)){R('card: link de vídeo/categoria');continue}
          if(videoCard(A)){R('card: tem vídeo');continue}
          var al2=(im.alt||'')+' '+cls(im);
          if(/(avatar|logo|emoji|profile|perfil)/i.test(al2))continue;
          var tt=[A.getAttribute('title')||'',A.getAttribute('aria-label')||'',tc(A,200),im.alt||''];
          var p=A.parentElement,n=0; while(p&&n<3){var hh=p.querySelector('h1,h2,h3,h4,figcaption');if(hh){tt.push(tc(hh,200));break}p=p.parentElement;n++}
          cards.push({h:hr,t:tt.join(' ').replace(/\s+/g,' ').trim().slice(0,300)});
        }catch(x){}
      }
      ST.pend=pend;if(more)ST.dirty=1;
      return JSON.stringify({p:out,c:cards,r:rej,n:imgs.length});
    })()"""

    fun parse(raw: String?): List<Photo> = try {
        var v: Any? = JSONTokener(raw ?: return emptyList()).nextValue()
        if (v is String) v = JSONTokener(v).nextValue()
        val a = (v as? JSONArray) ?: (v as? org.json.JSONObject)?.optJSONArray("p") ?: return emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Photo(o.optString("u"), o.optString("t"), o.optInt("w"), o.optInt("h")) } }
            .filter { it.url.startsWith("http") && !badUrl(it.url) }
    } catch (_: Exception) { emptyList() }

    /** Motivos de descarte do script do navegador (para o diagnóstico) e total de <img> na página. */
    fun parseRejects(raw: String?): Pair<Map<String, Int>, Int> = try {
        var v: Any? = JSONTokener(raw ?: "").nextValue()
        if (v is String) v = JSONTokener(v).nextValue()
        val o = v as org.json.JSONObject
        val r = o.optJSONObject("r"); val m = LinkedHashMap<String, Int>()
        r?.keys()?.forEach { k -> m[k] = r.optInt(k) }
        m to o.optInt("n")
    } catch (_: Exception) { emptyMap<String, Int>() to -1 }

    data class Card(val href: String, val text: String)
    fun parseCards(raw: String?): List<Card> = try {
        var v: Any? = JSONTokener(raw ?: return emptyList()).nextValue()
        if (v is String) v = JSONTokener(v).nextValue()
        val a = (v as? org.json.JSONObject)?.optJSONArray("c") ?: return emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Card(o.optString("h"), o.optString("t")) } }.filter { it.href.startsWith("http") }
    } catch (_: Exception) { emptyList() }

    private val VIDEO_PATH = Regex("""(?i)/(video|videos|watch|embed|v|tag|tags|category|categories|search|login|user|users|profile|channel)s?(/|\?|$)""")
    private val DUR = Regex("""(?:^|\s)(?:\d{1,2}:)?\d{1,2}:\d{2}(?:\s|$)""")
    private val VIDCLS = Regex("""(?i)(duration|play|video|vjs|watch|player)""")

    /** Cards de post de foto no HTML estático (mesmas regras do navegador). */
    fun cardsFromHtml(pageUrl: String, html: String): List<Card> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val host = Extract.host(pageUrl)
        val out = LinkedHashMap<String, Card>()
        for (a in doc.select("a[href]:has(img)")) {
            if (a.parents().any { it.tagName() in setOf("header", "nav", "footer", "aside") }) continue
            val h = a.absUrl("href").substringBefore('#'); if (!h.startsWith("http") || Extract.host(h) != host || Extract.isPhotoUrl(h) || Extract.isVideoUrl(h)) continue
            val path = try { java.net.URI(h).path ?: "" } catch (_: Exception) { continue }
            if (h == pageUrl.substringBefore('#') || VIDEO_PATH.containsMatchIn(path)) continue
            var vid = false; var e: Element? = a; var d = 0
            while (e != null && d < 3) {
                if (VIDCLS.containsMatchIn(e.className() + " " + e.id()) || e.select("video,iframe,[class*=duration],[class*=play],[class*=video]").isNotEmpty() || DUR.containsMatchIn(e.text().take(300))) { vid = true; break }
                e = e.parent(); d++
            }
            if (vid) continue
            val im = a.selectFirst("img") ?: continue
            if (AVATAR.containsMatchIn(im.attr("alt") + " " + im.className())) continue
            var heading = ""; var p = a.parent(); var n = 0
            while (p != null && n < 3) { val hh = p.selectFirst("h1,h2,h3,h4,figcaption"); if (hh != null) { heading = hh.text(); break }; p = p.parent(); n++ }
            out.putIfAbsent(h, Card(h, listOf(a.attr("title"), a.text().take(200), im.attr("alt"), heading).filter { it.isNotBlank() }.distinct().joinToString(" ").take(300)))
            if (out.size >= 120) break
        }
        return out.values.toList()
    }

    /** Página tem vídeo? (aí não é post de foto: nada de foto dela). */
    fun isVideoPage(url: String, html: String): Boolean = strongVideo(html) || weakVideo(url, html)
    /** Sinal forte: a página declara que É um vídeo (og:video / VideoObject / og:type video). */
    fun strongVideo(html: String) = Regex("""(?i)og:video|"@type"\s*:\s*"VideoObject"|property=["']og:type["'][^>]*video""").containsMatchIn(html)
    /** Sinal fraco: tem <video>/stream em algum lugar (pode ser só vídeo relacionado na lateral). */
    fun weakVideo(url: String, html: String): Boolean {
        if (Regex("""(?i)<video[\s>]""").containsMatchIn(html)) return true
        return try { Extract.parse(url, html).videos.any { it.kind != Kind.SERVER } } catch (_: Exception) { false }
    }

    /** Tamanhos já conferidos (não pede o mesmo arquivo de novo a cada coleta). 0x0 = falhou. */
    private val sizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    @Volatile var lastProbeFail = ""
    /** Tamanho da imagem lendo só o começo do arquivo de verdade (não o placeholder). Lê no máximo 256 KB, fora da tela. */
    suspend fun probeSize(url: String, referer: String?): Pair<Int, Int>? {
        sizeCache[url]?.let { return it.takeIf { it.first > 0 } }
        val r0 = probeSize0(url, referer)
        sizeCache[url] = r0 ?: (0 to 0)
        return r0
    }
    private suspend fun probeSize0(url: String, referer: String?): Pair<Int, Int>? = try {
        val rq = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-262143").apply { if (!referer.isNullOrBlank()) header("Referer", referer) }.build()
        Net.browser.newCall(rq).await().use { r ->
            if (!r.isSuccessful) { lastProbeFail = "HTTP ${r.code}"; null } else {
                val b = r.body?.source()?.let { src -> val buf = okio.Buffer(); var n = 0L
                    while (n < 262_144) { val k = src.read(buf, 262_144 - n); if (k < 0) break; n += k }
                    buf.readByteArray() } ?: ByteArray(0)
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                if (o.outWidth > 0) o.outWidth to o.outHeight else null
            }
        }
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }

    /**
     * Abre a página de um post de foto e pega as fotos grandes do corpo/galeria. og:image só aqui (post sem vídeo) e só se não achar outra.
     * Tamanho conferido no arquivo de verdade (≥ 500 px no lado menor).
     */
    suspend fun fromPost(post: String, cardText: String, render: (suspend (String) -> String?)?, deadlineMs: Long = 30_000): List<Photo> = kotlinx.coroutines.coroutineScope {
        val end = System.currentTimeMillis() + deadlineMs
        fun left() = (end - System.currentTimeMillis()).coerceAtLeast(1)
        var html = kotlinx.coroutines.withTimeoutOrNull(left()) { PageResolver.fetch(post, null) }
        if (html != null && strongVideo(html)) return@coroutineScope emptyList()
        var ps = html?.let { fromHtml(post, it) }.orEmpty()
        if (ps.isEmpty() && html != null && weakVideo(post, html)) return@coroutineScope emptyList()
        if (ps.isEmpty() && render != null) {
            html = kotlinx.coroutines.withTimeoutOrNull(left()) { render(post) } ?: html
            if (html != null && strongVideo(html)) return@coroutineScope emptyList()
            ps = html?.let { fromHtml(post, it) }.orEmpty()
            if (ps.isEmpty() && html != null && weakVideo(post, html)) return@coroutineScope emptyList()
        }
        val doc = html?.let { try { Jsoup.parse(it, post) } catch (_: Exception) { null } }
        val title = listOf(doc?.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), doc?.selectFirst("h1")?.text().orEmpty(), doc?.title().orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty()
        if (ps.isEmpty() && doc != null) {
            val og = doc.selectFirst("meta[property=og:image],meta[name=og:image]")?.absUrl("content").orEmpty()
            if (og.startsWith("http") && !badUrl(og)) ps = listOf(Photo(og, "", 0, 0))
        }
        // mede em paralelo (6 por vez) e, se o prazo acabar, fica com as já medidas (antes: perdia todas)
        val got = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Photo>>())
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val jobs = ps.take(30).mapIndexed { i, p -> launch {
            gate.acquire()
            try {
                val wh = if (p.w > 0 && p.h > 0 && minOf(p.w, p.h) >= MIN_SIDE) p.w to p.h else probeSize(p.url, post)
                if (wh != null && minOf(wh.first, wh.second) >= MIN_SIDE)
                    got += i to Photo(p.url, listOf(p.text, title, cardText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(500), wh.first, wh.second)
            } finally { gate.release() }
        } }
        kotlinx.coroutines.withTimeoutOrNull(left()) { jobs.joinAll() }
        jobs.forEach { it.cancel() }
        synchronized(got) { got.sortedBy { it.first }.map { it.second } }.distinctBy { key(it.url) }
    }

    /** Coleta no HTML estático (busca/varredura): mesmas regras; tamanho conferido pelos atributos e depois ao baixar. */
    fun fromHtml(pageUrl: String, html: String): List<Photo> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val bad = HashSet<String>()
        doc.select("meta[property=og:image],meta[name=og:image],meta[name=twitter:image],meta[property=twitter:image],link[rel=image_src]")
            .forEach { bad += (it.absUrl("content").ifBlank { it.absUrl("href") }).substringBefore('?') }
        doc.select("video[poster]").forEach { bad += it.absUrl("poster").substringBefore('?') }
        val player = PLAYER_CLS
        val out = ArrayList<Photo>()
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        for (img in doc.select("img")) {
            val ps = img.parents()
            if (ps.any { it.tagName() in MENU_TAGS }) continue
            if (ps.any { it.tagName() == "header" } && ps.none { it.tagName() == "article" || it.tagName() == "main" }) continue
            val a = img.closest("a[href]")
            val href = a?.absUrl("href").orEmpty()
            val bigLink = a != null && Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href)
            var skip = false; var e: Element? = img; var d = 0
            // só o elemento e o pai (antes 5 níveis: "gallery-icon", "post-thumbnail" etc. derrubavam fotos de verdade)
            while (e != null && d <= 2) {
                if (!bigLink && d < 2 && player.containsMatchIn((e.className() + " " + e.id()).replace(NOT_THUMB, " "))) { skip = true; break }
                if (d in 1..2 && e.select("video,[class*=duration],[class*=play-icon],[class*=playicon],[class*=btn-play],[class*=play-button]").isNotEmpty()) { skip = true; break }
                e = e.parent(); d++
            }
            if (skip) continue
            if (a != null && !bigLink) continue
            val u = if (bigLink) href else bestSrc(img)
            if (u.isBlank() || u.substringBefore('?') in bad || badUrl(u)) continue
            val w = img.attr("width").toIntOrNull() ?: 0; val h = img.attr("height").toIntOrNull() ?: 0
            if (!bigLink && img.attr("srcset").isBlank() && w > 0 && h > 0 && minOf(w, h) < MIN_SIDE) continue
            val al = img.attr("alt") + " " + img.className().replace(NOT_THUMB, " ")
            if (AVATAR.containsMatchIn(al) || (!bigLink && THUMB_WORD.containsMatchIn(al))) continue
            val cap = img.closest("figure")?.selectFirst("figcaption")?.text().orEmpty()
            var heading = ""; var p = img.parent(); var n = 0
            while (p != null && n < 4) { val hh = p.selectFirst("h1,h2,h3,h4"); if (hh != null) { heading = hh.text(); break }; p = p.parent(); n++ }
            out += Photo(u, listOf(img.attr("alt"), img.attr("title"), cap, heading, h1, doc.title()).filter { it.isNotBlank() }.distinct().joinToString(" ").take(400), if (bigLink || minOf(w, h) < MIN_SIDE) 0 else w, if (bigLink || minOf(w, h) < MIN_SIDE) 0 else h)
            if (out.size >= 300) break
        }
        return out.distinctBy { key(it.url) }
    }

    private val PLAYER_CLS = Regex("""(?i)(player|(^|[\s_-])video|vjs|jwplayer|plyr|flowplayer|mejs|thumb|poster|preview|avatar|logo|emoji|badge|sponsor|advert|(^|[\s_-])ads?([\s_-]|$)|banner|related|duration|(^|[\s_-])play)""")
    private val NOT_THUMB = Regex("""(?i)post-thumbnail|wp-post-image|has-post-thumbnail""")
    private val AVATAR = Regex("(?i)(avatar|logo|emoji|profile|perfil)")
    private val THUMB_WORD = Regex("(?i)(thumbnail|miniatura|capa|cover|poster)")
    private val MENU_TAGS = setOf("video", "nav", "footer", "aside", "iframe")
    private val WS = Regex("\\s+")

    private fun bestSrc(img: Element): String {
        val ss = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        var best = ""; var bw = -1
        if (ss.isNotBlank()) for (part in ss.split(',')) {
            val a = part.trim().split(WS); if (a.isEmpty() || a[0].isBlank()) continue
            val w = a.getOrNull(1)?.filter(Char::isDigit)?.toIntOrNull() ?: 1
            if (w >= bw) { bw = w; best = a[0] }
        }
        if (best.isBlank()) best = listOf("data-full", "data-large", "data-orig-file", "data-large-file", "data-original", "data-src", "data-lazy-src", "src").map { img.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return try { java.net.URL(java.net.URL(img.baseUri()), best).toString() } catch (_: Exception) { "" }
    }

    /** Título para a legenda/lista. */
    fun title(p: Photo): String = TitleFix.clean(p.text.substringBefore("  ").take(120)).ifBlank { Extract.fileName(p.url) }
}
