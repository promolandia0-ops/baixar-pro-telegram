package br.geracaoai.baixartg.core

import org.json.JSONArray
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Fotos de verdade postadas na página (ex.: diagramas de escala) — opcional ("Fotos também").
 * Nunca miniatura/capa de vídeo: fica de fora toda imagem dentro de link (a não ser link direto para a imagem grande),
 * dentro de player/card de vídeo, poster, og:image, avatar/logo/ícone, anúncio, e menor que 500 px no lado menor.
 * Totalmente separado do fluxo de vídeos.
 */
object Photos {
    data class Photo(val url: String, val text: String, val w: Int, val h: Int)

    const val MIN_SIDE = 500

    val BAD_URL = Regex("""(?i)(thumb|thumbnail|poster|preview|cover|sprite|storyboard|/vi/|/vi_webp/|hqdefault|mqdefault|sddefault|maxresdefault|avatar|gravatar|logo|icon|favicon|emoji|badge|banner|placeholder|blank\.|spacer|pixel|/ads?/|doubleclick|adserver)""")
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

    /** Coleta no navegador (DOM renderizado: usa naturalWidth/Height). */
    const val JS = """(function(){
      var out=[],cards=[],seenC={};
      var VID=/(duration|play|video|vjs|watch|player)/i, DUR=/(^|\s)(\d{1,2}:)?\d{1,2}:\d{2}(\s|$)/;
      function videoCard(el){
        var e=el,d=0;
        while(e&&d<3){var c=((e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className)||'')+' '+(e.id||'');
          if(VID.test(c))return true;
          if(e.querySelector&&e.querySelector('video,iframe,[class*=duration],[class*=play],[class*=video],svg[class*=play]'))return true;
          if(DUR.test((e.innerText||'').slice(0,300)))return true;
          e=e.parentElement;d++}
        return false;
      }
      try{
        var bad={};
        document.querySelectorAll('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"],link[rel=image_src]').forEach(function(m){var u=m.content||m.href;if(u)bad[u.split('?')[0]]=1});
        document.querySelectorAll('video[poster]').forEach(function(v){var u=v.poster;if(u)bad[u.split('?')[0]]=1});
        var PLAYER=/(player|video|vjs|jwplayer|plyr|flowplayer|mejs|thumb|poster|preview|avatar|logo|icon|emoji|badge|sponsor|advert|\bads?\b|\bad-|banner|related|carousel|slider-video|duration|play)/i;
        var IMGRE=/\.(jpe?g|png|webp|avif)(\?|#|$)/i;
        function best(img){
          var u='',bw=0;
          var ss=img.getAttribute('srcset')||img.getAttribute('data-srcset')||'';
          if(ss){ss.split(',').forEach(function(p){var a=p.trim().split(/\s+/);if(!a[0])return;var w=parseInt(a[1]||'0')||1;if(w>=bw){bw=w;u=a[0]}})}
          if(!u){var pic=img.closest('picture');if(pic){pic.querySelectorAll('source').forEach(function(s){var x=s.getAttribute('srcset')||s.getAttribute('data-srcset')||'';x.split(',').forEach(function(p){var a=p.trim().split(/\s+/);if(!a[0])return;var w=parseInt(a[1]||'0')||1;if(w>=bw){bw=w;u=a[0]}})})}}
          var ds=img.getAttribute('data-full')||img.getAttribute('data-large')||img.getAttribute('data-orig-file')||img.getAttribute('data-large-file')||img.getAttribute('data-original')||img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-lazy')||img.getAttribute('data-url');
          if(!u)u=ds||img.currentSrc||img.src||'';
          try{return new URL(u,location.href).href}catch(e){return ''}
        }
        function ctxText(img){
          var t=[img.alt||'',img.title||''];
          var fig=img.closest('figure');if(fig){var fc=fig.querySelector('figcaption');if(fc)t.push(fc.innerText||'')}
          var p=img.parentElement,n=0;
          while(p&&n<4){var h=p.querySelector('h1,h2,h3,h4');if(h){t.push(h.innerText||'');break}p=p.parentElement;n++}
          var h1=document.querySelector('h1');if(h1)t.push(h1.innerText||'');
          return t.join(' ').replace(/\s+/g,' ').trim().slice(0,400);
        }
        var imgs=document.querySelectorAll('img');
        for(var i=0;i<imgs.length&&out.length<300;i++){
          var img=imgs[i];
          if(img.closest('video,picture video,iframe,header,nav,footer,aside,[role=banner],[role=navigation]'))continue;
          var skip=false,e=img,d=0;
          while(e&&d<6){var c=(e.className&&e.className.baseVal!==undefined?e.className.baseVal:e.className)||'';var id=e.id||'';
            if(PLAYER.test(c+' '+id)){skip=true;break}
            if(e.querySelector&&d>0&&e.querySelector('video,[class*=duration],[class*=play-icon],[class*=playicon],[class*=btn-play],[class*=play-button]')){skip=true;break}
            e=e.parentElement;d++}
          if(skip)continue;
          var a=img.closest('a[href]');
          var u=best(img);
          if(a){ if(IMGRE.test(a.href)) u=a.href; else continue; }
          var lazy=!!(img.getAttribute('data-src')||img.getAttribute('data-srcset')||img.getAttribute('data-original')||img.getAttribute('data-lazy')||img.getAttribute('data-lazy-src')||img.loading==='lazy');
          if(!u||bad[u.split('?')[0]])continue;
          var w=img.naturalWidth||0,h=img.naturalHeight||0;
          var loaded=img.complete&&w>0&&!/^data:/.test(img.currentSrc||'')&&(img.currentSrc||img.src||'').split('?')[0]===u.split('?')[0];
          if(!loaded||Math.min(w,h)<500){ if(lazy||!loaded){w=0;h=0} else continue; }
          var alt=(img.alt||'')+' '+(img.className||'');
          if(/(avatar|logo|icon|emoji|profile|perfil|thumbnail|miniatura|capa|cover|poster)/i.test(alt))continue;
          out.push({u:u,t:ctxText(img),w:w,h:h});
        }
        var as=document.querySelectorAll('a[href]');
        for(var j=0;j<as.length&&cards.length<120;j++){
          var A=as[j]; var im=A.querySelector('img'); if(!im)continue;
          if(A.closest('header,nav,footer,aside,[role=banner],[role=navigation]'))continue;
          var hr=(A.href||'').split('#')[0]; if(!/^https?:/.test(hr)||IMGRE.test(hr)||hr===location.href.split('#')[0])continue;
          var U; try{U=new URL(hr)}catch(e){continue}
          if(U.host!==location.host)continue;
          if(/\/(video|videos|watch|embed|v|tag|tags|category|categories|search|login|user|users|profile|channel)s?(\/|\?|$)/i.test(U.pathname))continue;
          if(videoCard(A))continue;
          var al=(im.alt||'')+' '+(im.className||'');
          if(/(avatar|logo|icon|emoji|profile|perfil)/i.test(al))continue;
          if(seenC[hr])continue; seenC[hr]=1;
          var tt=[A.getAttribute('title')||'',A.getAttribute('aria-label')||'',(A.innerText||'').slice(0,200),im.alt||''];
          var p=A.parentElement,n=0; while(p&&n<3){var hh=p.querySelector('h1,h2,h3,h4,figcaption');if(hh){tt.push(hh.innerText||'');break}p=p.parentElement;n++}
          cards.push({h:hr,t:tt.join(' ').replace(/\s+/g,' ').trim().slice(0,300)});
        }
      }catch(e){}
      return JSON.stringify({p:out,c:cards});
    })()"""

    fun parse(raw: String?): List<Photo> = try {
        var v: Any? = JSONTokener(raw ?: return emptyList()).nextValue()
        if (v is String) v = JSONTokener(v).nextValue()
        val a = (v as? JSONArray) ?: (v as? org.json.JSONObject)?.optJSONArray("p") ?: return emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Photo(o.optString("u"), o.optString("t"), o.optInt("w"), o.optInt("h")) } }
            .filter { it.url.startsWith("http") && !badUrl(it.url) }
    } catch (_: Exception) { emptyList() }

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
            if (Regex("(?i)(avatar|logo|icon|emoji|profile|perfil)").containsMatchIn(im.attr("alt") + " " + im.className())) continue
            var heading = ""; var p = a.parent(); var n = 0
            while (p != null && n < 3) { val hh = p.selectFirst("h1,h2,h3,h4,figcaption"); if (hh != null) { heading = hh.text(); break }; p = p.parent(); n++ }
            out.putIfAbsent(h, Card(h, listOf(a.attr("title"), a.text().take(200), im.attr("alt"), heading).filter { it.isNotBlank() }.distinct().joinToString(" ").take(300)))
            if (out.size >= 120) break
        }
        return out.values.toList()
    }

    /** Página tem vídeo? (aí não é post de foto: nada de foto dela). */
    fun isVideoPage(url: String, html: String): Boolean {
        if (Regex("""(?i)<video[\s>]|og:video|"@type"\s*:\s*"VideoObject"|property=["']og:type["'][^>]*video""").containsMatchIn(html)) return true
        return try { Extract.parse(url, html).videos.any { it.kind != Kind.SERVER } } catch (_: Exception) { false }
    }

    /** Tamanho da imagem lendo só o começo do arquivo de verdade (não o placeholder). */
    suspend fun probeSize(url: String, referer: String?): Pair<Int, Int>? = try {
        val rq = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-131071").apply { if (!referer.isNullOrBlank()) header("Referer", referer) }.build()
        Net.fast.newCall(rq).await().use { r ->
            if (!r.isSuccessful) null else {
                val b = r.body?.bytes() ?: ByteArray(0)
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
    suspend fun fromPost(post: String, cardText: String, render: (suspend (String) -> String?)?): List<Photo> {
        var html = PageResolver.fetch(post, null)
        if (html != null && isVideoPage(post, html)) return emptyList()
        var ps = html?.let { fromHtml(post, it) }.orEmpty()
        if (ps.isEmpty() && render != null) {
            html = render(post) ?: html
            if (html != null && isVideoPage(post, html)) return emptyList()
            ps = html?.let { fromHtml(post, it) }.orEmpty()
        }
        val doc = html?.let { try { Jsoup.parse(it, post) } catch (_: Exception) { null } }
        val title = listOf(doc?.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), doc?.selectFirst("h1")?.text().orEmpty(), doc?.title().orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty()
        if (ps.isEmpty() && doc != null) {
            val og = doc.selectFirst("meta[property=og:image],meta[name=og:image]")?.absUrl("content").orEmpty()
            if (og.startsWith("http") && !badUrl(og)) ps = listOf(Photo(og, "", 0, 0))
        }
        val out = ArrayList<Photo>()
        for (p in ps.take(30)) {
            val wh = if (p.w > 0 && p.h > 0 && minOf(p.w, p.h) >= MIN_SIDE) p.w to p.h else probeSize(p.url, post) ?: continue
            if (minOf(wh.first, wh.second) < MIN_SIDE) continue
            out += Photo(p.url, listOf(p.text, title, cardText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(500), wh.first, wh.second)
        }
        return out.distinctBy { key(it.url) }
    }

    /** Coleta no HTML estático (busca/varredura): mesmas regras; tamanho conferido pelos atributos e depois ao baixar. */
    fun fromHtml(pageUrl: String, html: String): List<Photo> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val bad = HashSet<String>()
        doc.select("meta[property=og:image],meta[name=og:image],meta[name=twitter:image],meta[property=twitter:image],link[rel=image_src]")
            .forEach { bad += (it.absUrl("content").ifBlank { it.absUrl("href") }).substringBefore('?') }
        doc.select("video[poster]").forEach { bad += it.absUrl("poster").substringBefore('?') }
        val player = Regex("""(?i)(player|video|vjs|jwplayer|plyr|flowplayer|mejs|thumb|poster|preview|avatar|logo|icon|emoji|badge|sponsor|advert|\bads?\b|\bad-|banner|related|duration|play)""")
        val out = ArrayList<Photo>()
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        for (img in doc.select("img")) {
            if (img.parents().any { it.tagName() in setOf("video", "header", "nav", "footer", "aside", "iframe") }) continue
            var skip = false; var e: Element? = img; var d = 0
            while (e != null && d < 6) {
                if (player.containsMatchIn(e.className() + " " + e.id())) { skip = true; break }
                if (d > 0 && e.select("video,[class*=duration],[class*=play-icon],[class*=playicon],[class*=btn-play],[class*=play-button]").isNotEmpty()) { skip = true; break }
                e = e.parent(); d++
            }
            if (skip) continue
            var u = bestSrc(img)
            val a = img.closest("a[href]")
            if (a != null) { val href = a.absUrl("href"); if (Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href)) u = href else continue }
            if (u.isBlank() || u.substringBefore('?') in bad || badUrl(u)) continue
            val w = img.attr("width").toIntOrNull() ?: 0; val h = img.attr("height").toIntOrNull() ?: 0
            if (w > 0 && h > 0 && minOf(w, h) < MIN_SIDE) continue
            if (Regex("(?i)(avatar|logo|icon|emoji|profile|perfil|thumbnail|miniatura|capa|cover|poster)").containsMatchIn(img.attr("alt") + " " + img.className())) continue
            val cap = img.closest("figure")?.selectFirst("figcaption")?.text().orEmpty()
            var heading = ""; var p = img.parent(); var n = 0
            while (p != null && n < 4) { val hh = p.selectFirst("h1,h2,h3,h4"); if (hh != null) { heading = hh.text(); break }; p = p.parent(); n++ }
            out += Photo(u, listOf(img.attr("alt"), img.attr("title"), cap, heading, h1).filter { it.isNotBlank() }.distinct().joinToString(" ").take(400), w, h)
            if (out.size >= 300) break
        }
        return out.distinctBy { key(it.url) }
    }

    private fun bestSrc(img: Element): String {
        val ss = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        var best = ""; var bw = -1
        if (ss.isNotBlank()) for (part in ss.split(',')) {
            val a = part.trim().split(Regex("\\s+")); if (a.isEmpty() || a[0].isBlank()) continue
            val w = a.getOrNull(1)?.filter(Char::isDigit)?.toIntOrNull() ?: 1
            if (w >= bw) { bw = w; best = a[0] }
        }
        if (best.isBlank()) best = listOf("data-full", "data-large", "data-orig-file", "data-large-file", "data-original", "data-src", "data-lazy-src", "src").map { img.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return try { java.net.URL(java.net.URL(img.baseUri()), best).toString() } catch (_: Exception) { "" }
    }

    /** Título para a legenda/lista. */
    fun title(p: Photo): String = TitleFix.clean(p.text.substringBefore("  ").take(120)).ifBlank { Extract.fileName(p.url) }
}
