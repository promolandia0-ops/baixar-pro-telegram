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
      var out=[];
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
          var ds=img.getAttribute('data-full')||img.getAttribute('data-large')||img.getAttribute('data-orig-file')||img.getAttribute('data-large-file')||img.getAttribute('data-original')||img.getAttribute('data-src')||img.getAttribute('data-lazy-src');
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
          if(!u||bad[u.split('?')[0]])continue;
          var w=img.naturalWidth||0,h=img.naturalHeight||0;
          if(Math.min(w,h)<500)continue;
          var alt=(img.alt||'')+' '+(img.className||'');
          if(/(avatar|logo|icon|emoji|profile|perfil|thumbnail|miniatura|capa|cover|poster)/i.test(alt))continue;
          out.push({u:u,t:ctxText(img),w:w,h:h});
        }
      }catch(e){}
      return JSON.stringify(out);
    })()"""

    fun parse(raw: String?): List<Photo> = try {
        var v: Any? = JSONTokener(raw ?: return emptyList()).nextValue()
        if (v is String) v = JSONTokener(v).nextValue()
        val a = v as? JSONArray ?: return emptyList()
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Photo(o.optString("u"), o.optString("t"), o.optInt("w"), o.optInt("h")) } }
            .filter { it.url.startsWith("http") && !badUrl(it.url) }
    } catch (_: Exception) { emptyList() }

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
