package br.geracaoai.baixartg.core

import org.json.JSONArray
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Fotos de verdade postadas na página (ex.: diagramas de escala) — opcional ("Fotos também").
 * Decide pelo TAMANHO (≥ 500 px no lado menor, medido no arquivo ou, se não der, pelo srcset/atributos/tela).
 * Fica de fora: capa (poster) de <video>, avatar/logo/sprite/ícone/anúncio no próprio elemento, e o que é pequeno.
 * Totalmente separado do fluxo de vídeos.
 */
/** Por que a medição das fotos falhou (vai no diagnóstico). */
object BrowserProbeStats {
    val fails = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    fun fail(k: String) { fails.getOrPut(k) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
}

object Photos {
    /** w/h = tamanho real (0 = medir no arquivo); fw/fh = tamanho de reserva (srcset/atributos/tela) se não der pra medir;
     *  orig = endereço da imagem ORIGINAL vindo de link/parâmetro/código da página (não é miniatura). */
    data class Photo(val url: String, val text: String, val w: Int, val h: Int, val fw: Int = 0, val fh: Int = 0, val orig: Boolean = false, val alt: String = "")

    const val MIN_SIDE = 500

    // só o que nunca é foto de verdade (o resto decide pelo TAMANHO: antes "thumb" no caminho derrubava, por exemplo,
    // todas as fotos de wikis, que ficam em /thumb/)
    val BAD_URL = Regex("""(?i)(?:^|[/_.=\-])(?:sprites?|storyboards?|avatars?|gravatar|logos?|icons?|favicon|emojis?|badges?|placeholders?|blank|spacer|pixel)(?=$|[/_.=\-\d])|/vi/|/vi_webp/|hqdefault|mqdefault|sddefault|maxresdefault|/ads?/|doubleclick|adserver""")
    private val SIZE_SUFFIX = Regex("""(?i)[-_@](\d{2,4}x\d{2,4}|\d{2,4}w|\d)x?(?=\.(jpe?g|png|webp|gif|avif)$)""")
    private val SIZE_PARAMS = setOf("w", "h", "width", "height", "resize", "fit", "quality", "q", "crop", "size", "s", "dpr", "auto", "format", "fm", "ver", "v")
    /** Valor de parâmetro que é só tamanho/qualidade/formato (antes qualquer "q=" saía: "images?q=ID" de todas as fotos virava a MESMA chave). */
    private val SIZE_VAL = Regex("""(?i)^(?:[\d.,x:%]*|auto|crop|fit|max|min|clip|fill|cover|contain|scale|webp|jpe?g|png|avif|compress|format|enhance|true|false|smart|faces|center)$""")

    /** Chave da foto: URL sem sufixo de tamanho (-300x200, @2x) e sem parâmetros de redimensionamento. */
    fun key(u: String): String {
        val noFrag = u.substringBefore('#')
        val base = noFrag.substringBefore('?').lowercase().replace("://www.", "://").let { SIZE_SUFFIX.replace(it, "") }
        val q = noFrag.substringAfter('?', "").split('&').filter { it.isNotBlank() && !(it.substringBefore('=').lowercase() in SIZE_PARAMS && SIZE_VAL.matches(java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8").replace(',', ' ').split(' ').first())) }.sorted()
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
        u.substringBefore('?').let { it.endsWith(".svg", true) || it.endsWith(".gif", true) }

    private val MW_THUMB = Regex("""^(https?://[^?#]+)/thumb/([^?#]+/[^/?#]+\.(?:jpe?g|png|webp|tiff?))/[^/?#]*(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val WP_SIZE = Regex("""-\d{2,4}x\d{2,4}(?=\.(?:jpe?g|png|webp)(?:$|\?))""", RegexOption.IGNORE_CASE)
    /** Endereço da imagem ORIGINAL quando a página mostra uma versão reduzida conhecida (wiki .../thumb/.../800px-x.jpg; foto-300x200.jpg). */
    fun original(u: String): String? = originals(u).firstOrNull()
    /** Candidatos à original, na ordem (wiki: mesmo servidor, e o servidor "upload." quando a miniatura vem de "thumb."). */
    fun originals(u: String): List<String> {
        MW_THUMB.find(u)?.let { val b = it.groupValues[1]; val f = it.groupValues[2]
            return listOfNotNull("$b/$f", if (b.contains("://thumb.")) b.replace("://thumb.", "://upload.") + "/" + f else null) }
        if (WP_SIZE.containsMatchIn(u)) return listOf(WP_SIZE.replace(u, ""))
        return emptyList()
    }

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
      if(!ST||ST.u!==location.href){ST={u:location.href,dirty:1,pend:0,scr:0};W.__bptPh=ST}
      if(!W.__bptPhMO){W.__bptPhMO=1;
        try{new MutationObserver(function(){if(W.__bptPh)W.__bptPh.dirty=1}).observe(D.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src','srcset','data-src','data-srcset','href']})}catch(x){W.__bptPhNoMO=1}
        try{D.addEventListener('load',function(e){var t=e.target;if(t&&t.tagName==='IMG'&&W.__bptPh)W.__bptPh.dirty=1},true)}catch(x){}
      }
      if(!ST.dirty&&!ST.pend&&ST.ni===NI&&ST.na===NA&&!W.__bptPhNoMO)return JSON.stringify({p:[],c:[],r:{},n:NI,same:1});
      ST.dirty=0;ST.ni=NI;ST.na=NA;
      var T0=Date.now();function over(){return Date.now()-T0>40}
      function R(k){rej[k]=(rej[k]||0)+1}
      function cls(e){try{var c=e.className;if(c&&c.baseVal!==undefined)c=c.baseVal;return (typeof c==='string'?c:'')+' '+(e.id||'')}catch(x){return ''}}
      function tc(e,n){try{return ((e&&e.textContent)||'').replace(/\s+/g,' ').trim().slice(0,n)}catch(x){return ''}}
      var DPR=W.devicePixelRatio||1;
      var IMGRE=/\.(jpe?g|png|webp|avif|jfif)(\?|#|$)/i;
      // a foto decide pelo TAMANHO; só sai o que é claramente avatar/logo/sprite/ícone/anúncio no próprio elemento
      var JUNK=/(^|[\s_-])(avatar|logo|sprite|emoji|icon|favicon|badge|ad|ads|advert|sponsor)([\s_-]|$)/i;
      var bad={},h1t='';
      try{
        D.querySelectorAll('video[poster]').forEach(function(v){var u=v.poster;if(u)bad[u.split('?')[0]]=1});
        var h1=D.querySelector('h1');h1t=tc(h1,200);
      }catch(x){}
      function abs(u){if(!u||/^data:/.test(u))return '';try{return new URL(u,location.href).href}catch(e){return ''}}
      /** link que leva à imagem original: arquivo de imagem, ou parâmetro com o endereço dela (imgurl=, mediaurl=, url=…) */
      function origOf(a){
        if(!a)return '';var h=a.href||'';
        if(/^https?:/i.test(h)&&IMGRE.test(h.split('#')[0]))return h;
        try{var U=new URL(h,location.href);var it=U.searchParams.entries(),e;while(!(e=it.next()).done){var v=e.value[1];if(/^https?:\/\//i.test(v)&&IMGRE.test(v))return v}}catch(x){}
        return '';
      }
      function pick(ss,cur){var u=cur.u,bw=cur.w,bx=cur.x||0;(ss||'').split(/,\s+|,(?=https?:|\/)/).forEach(function(p){var a=p.trim().split(/\s+/);if(!a[0])return;var d=a[1]||'1x';
        if(/w$/i.test(d)){var w=parseInt(d)||0;if(w>=bw){bw=w;u=a[0]}}else if(!bw){var x=parseFloat(d)||1;if(x>=bx){bx=x;u=a[0]}}});return {u:u,w:bw,x:bx}}
      function best(img){
        var c={u:'',w:0,x:0};
        c=pick(img.getAttribute('srcset')||img.getAttribute('data-srcset')||'',c);
        try{var pic=img.closest('picture');if(pic)pic.querySelectorAll('source').forEach(function(s){c=pick(s.getAttribute('srcset')||s.getAttribute('data-srcset')||'',c)})}catch(x){}
        var big=img.getAttribute('data-full')||img.getAttribute('data-large')||img.getAttribute('data-orig-file')||img.getAttribute('data-large-file')||img.getAttribute('data-original')||img.getAttribute('data-zoom-src')||img.getAttribute('data-hi-res-src');
        var ds=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-lazy')||img.getAttribute('data-url');
        var cur=img.currentSrc||img.src||'';if(/^data:/.test(cur))cur='';
        var u=big||c.u||ds||cur;
        return {u:abs(u),w:(big||!c.u)?0:c.w,ss:!!c.u};
      }
      function ctxText(img,a){
        var t=[img.alt||'',img.title||''];
        if(a){t.push(a.getAttribute('title')||'');t.push(a.getAttribute('aria-label')||'')}
        try{var fig=img.closest('figure');if(fig){var fc=fig.querySelector('figcaption');if(fc)t.push(tc(fc,300))}}catch(x){}
        var p=img.parentElement,n=0;
        while(p&&n<4){try{var h=p.querySelector('h1,h2,h3,h4');if(h){t.push(tc(h,200));break}}catch(x){}p=p.parentElement;n++}
        t.push(h1t);
        if(!h1t&&D.title)t.push(D.title);
        return t.filter(function(x){return x}).join(' ').replace(/\s+/g,' ').trim().slice(0,400);
      }
      /** null = ainda carregando (olha de novo depois); string = motivo de descarte; objeto = foto. */
      function decide(img){
        if(img.closest('video'))return 'dentro de vídeo';
        var a=img.closest('a[href]');
        var o=origOf(a);
        var b=best(img);var u=o||b.u;
        if(!u)return /^data:/.test(img.currentSrc||img.src||'')?'só miniatura embutida (sem endereço)':'sem endereço';
        if(bad[u.split('?')[0]])return 'capa de vídeo (poster)';
        if(JUNK.test(cls(img))||/(^|\s)(avatar|logo|emoji|ícone|icon)(\s|$)/i.test(img.alt||''))return 'avatar/logo/ícone';
        var cur=img.currentSrc||img.src||'';
        var placeholder=!cur||/^data:/.test(cur);
        if(!img.complete&&!placeholder){var t0=img.__bptW||(img.__bptW=Date.now());if(Date.now()-t0<5000)return null}
        var nw=img.naturalWidth||0,nh=img.naturalHeight||0,w=0,h=0;
        // tamanho de reserva (se não der pra medir o arquivo): maior srcset > atributos > tamanho na tela × densidade
        var fw=0,fh=0;
        try{var r=img.getBoundingClientRect();fw=Math.round(r.width*DPR);fh=Math.round(r.height*DPR)}catch(x){}
        var aw=parseInt(img.getAttribute('width'))||0,ah=parseInt(img.getAttribute('height'))||0;
        if(aw>fw&&ah>0){fw=aw;fh=ah}
        if(!o){
          if(!placeholder&&nw>0){
            if(b.w>10){w=b.w;h=Math.round(b.w*nh/nw)}
            else if(!b.ss&&cur.split('?')[0]===u.split('?')[0]){w=nw;h=nh}
            if(w>fw){fw=w;fh=h}
          } else if(b.w>10&&aw>0&&ah>0){fw=b.w;fh=Math.round(b.w*ah/aw)}
          if(w>0&&Math.min(w,h)<500)return 'pequena (<500px)';
        }
        return {u:u,t:ctxText(img,a),w:w,h:h,fw:fw,fh:fh,o:o?1:0,s:o?b.u:''};
      }
      var pend=0,more=false;
      for(var i=0;i<imgs.length&&out.length<400;i++){
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
      // páginas de resultado (ex.: busca de imagens) guardam o endereço ORIGINAL com o tamanho no próprio código:
      // ["https://…/foto.jpg",altura,largura]. Lido uma vez por página (e de novo se a página crescer).
      if(!over()&&ST.scr!==D.scripts.length){ST.scr=D.scripts.length;
        try{var seenS={},tot=0,re=/\["((?:https?:)?\/\/(?:[^"\\]|\\.){8,1500}?)",(\d{3,5}),(\d{3,5})\]/g;
          for(var s=0;s<D.scripts.length&&tot<4000000;s++){var sc=D.scripts[s];if(sc.src)continue;var tx=sc.textContent||'';tot+=tx.length;if(tx.indexOf('",')<0)continue;var m;
            while((m=re.exec(tx))&&out.length<400){var su;try{su=JSON.parse('"'+m[1]+'"')}catch(x){continue}
              if(su.indexOf('//')===0)su=location.protocol+su;
              var hh=parseInt(m[2]),ww=parseInt(m[3]);if(Math.min(hh,ww)<500||seenS[su])continue;
              if(/\/favicon|\.svg|\.gif|\.js(\?|$)|\.css(\?|$)/i.test(su))continue;seenS[su]=1;
              out.push({u:su,t:(h1t||D.title||'').slice(0,300),w:ww,h:hh,fw:ww,fh:hh,o:1})}}
        }catch(x){}
      }
      var as=D.links;
      for(var j=0;j<as.length&&cards.length<120;j++){
        var A=as[j];var hr0=(A.href||'').split('#')[0];
        if(A.__bptC===hr0)continue;
        if(over()){more=true;break}
        try{
          var im=A.querySelector('img');if(!im){A.__bptC=hr0;continue}
          A.__bptC=hr0;
          var hr=hr0; if(!/^https?:/.test(hr)||IMGRE.test(hr)||origOf(A)||hr===location.href.split('#')[0])continue;
          var U=new URL(hr);
          if(U.host!==location.host)continue;
          if(/\/(tag|tags|category|categories|search|login|signin|signup|user|users|profile|account)s?(\/|\?|$)/i.test(U.pathname)){R('card: link de categoria/busca');continue}
          if(JUNK.test(cls(im)))continue;
          var tt=[A.getAttribute('title')||'',A.getAttribute('aria-label')||'',tc(A,200),im.alt||''];
          var p=A.parentElement,n=0; while(p&&n<3){var hh2=p.querySelector('h1,h2,h3,h4,figcaption');if(hh2){tt.push(tc(hh2,200));break}p=p.parentElement;n++}
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
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Photo(o.optString("u"), o.optString("t"), o.optInt("w"), o.optInt("h"), o.optInt("fw"), o.optInt("fh"), o.optInt("o") == 1, o.optString("s")) } }
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

    private val VIDEO_PATH = Regex("""(?i)/(tag|tags|category|categories|search|login|signin|signup|user|users|profile|account)s?(/|\?|$)""")

    /** Cards de post de foto no HTML estático (mesmas regras do navegador). */
    fun cardsFromHtml(pageUrl: String, html: String): List<Card> {
        val doc = try { Jsoup.parse(html, pageUrl) } catch (_: Exception) { return emptyList() }
        val host = Extract.host(pageUrl)
        val out = LinkedHashMap<String, Card>()
        for (a in doc.select("a[href]:has(img)")) {
            val h = a.absUrl("href").substringBefore('#'); if (!h.startsWith("http") || Extract.host(h) != host || Extract.isPhotoUrl(h) || Extract.isVideoUrl(h)) continue
            val path = try { java.net.URI(h).path ?: "" } catch (_: Exception) { continue }
            if (h == pageUrl.substringBefore('#') || VIDEO_PATH.containsMatchIn(path)) continue
            val im = a.selectFirst("img") ?: continue
            if (JUNK_CLS.containsMatchIn(im.className() + " " + im.id())) continue
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
    /** Endereços que responderam mas não são imagem (ex.: link para a PÁGINA da foto, "/wiki/Arquivo:x.jpg"). */
    private val notImage = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Tamanho da imagem lendo só o começo do arquivo de verdade (não o placeholder). Lê no máximo 256 KB, fora da tela. */
    suspend fun probeSize(url: String, referer: String?): Pair<Int, Int>? {
        sizeCache[url]?.let { return it.takeIf { it.first > 0 } }
        val r0 = probeSize0(url, referer) ?: (if (!referer.isNullOrBlank()) probeSize0(url, null) else null)
        sizeCache[url] = r0 ?: (0 to 0)
        return r0
    }
    private suspend fun probeSize0(url: String, referer: String?): Pair<Int, Int>? = try {
        val rq = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-262143").apply { if (!referer.isNullOrBlank()) header("Referer", referer) }.build()
        Net.browser.newCall(rq).await().use { r ->
            if (!r.isSuccessful) { lastProbeFail = "HTTP ${r.code}"; BrowserProbeStats.fail("HTTP ${r.code}"); null } else {
                val b = r.body?.source()?.let { src -> val buf = okio.Buffer(); var n = 0L
                    while (n < 262_144) { val k = src.read(buf, 262_144 - n); if (k < 0) break; n += k }
                    buf.readByteArray() } ?: ByteArray(0)
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
                if (o.outWidth > 0) o.outWidth to o.outHeight else { BrowserProbeStats.fail("não é imagem"); notImage += url; null }
            }
        }
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; BrowserProbeStats.fail(e.javaClass.simpleName); null }

    /**
     * Mede a foto e decide. Tenta antes a versão ORIGINAL (se a página mostrava uma reduzida). Se não der pra medir o arquivo
     * (servidor recusa, sem rede…), usa o tamanho de reserva (srcset/atributos/tela); link para a original passa mesmo assim.
     * Devolve a foto com tamanho, ou o motivo do descarte.
     */
    suspend fun measure(p0: Photo, referer: String?): Pair<Photo?, String> {
        var p = p0
        if (p.orig) {
            val wh = probeSize(p.url, referer)
            if (wh != null) return if (minOf(wh.first, wh.second) >= MIN_SIDE) p.copy(w = wh.first, h = wh.second) to "original" else null to "pequena (<500px)"
            // o "link da original" era uma página (não imagem): volta para a imagem mostrada (e a versão original dela)
            if (p.url in notImage) { if (p.alt.startsWith("http") && !badUrl(p.alt)) p = p.copy(url = p.alt, orig = false, alt = "") else return null to "link não é imagem" }
            else return p to "sem medir (link da original)"
        }
        for (o in originals(p.url)) probeSize(o, referer)?.let { wh -> if (minOf(wh.first, wh.second) >= MIN_SIDE) return p.copy(url = o, w = wh.first, h = wh.second) to "original" }
        val wh = if (p.w > 0 && p.h > 0) p.w to p.h else probeSize(p.url, referer)
        if (wh != null) return if (minOf(wh.first, wh.second) >= MIN_SIDE) p.copy(w = wh.first, h = wh.second) to "medida" else null to "pequena (<500px)"
        if (minOf(p.fw, p.fh) >= MIN_SIDE) return p.copy(w = p.fw, h = p.fh) to "sem medir (tamanho da página)"
        return null to (if (p.fw > 0) "pequena (<500px)" else "não deu pra medir")
    }

    /**
     * Abre a página de um post de foto e pega as fotos grandes do corpo/galeria. og:image só aqui (post sem vídeo) e só se não achar outra.
     * Tamanho conferido no arquivo de verdade (≥ 500 px no lado menor).
     */
    suspend fun fromPost(post: String, cardText: String, render: (suspend (String) -> String?)?, deadlineMs: Long = 30_000): List<Photo> = kotlinx.coroutines.coroutineScope {
        val end = System.currentTimeMillis() + deadlineMs
        fun left() = (end - System.currentTimeMillis()).coerceAtLeast(1)
        // post com vídeo também tem fotos: pega as fotos grandes dele de qualquer jeito (o vídeo segue pelo detector de vídeos)
        var html = kotlinx.coroutines.withTimeoutOrNull(left()) { PageResolver.fetch(post, null) }
        var ps = html?.let { fromHtml(post, it) }.orEmpty()
        if (ps.isEmpty() && render != null) {
            html = kotlinx.coroutines.withTimeoutOrNull(left()) { render(post) } ?: html
            ps = html?.let { fromHtml(post, it) }.orEmpty()
        }
        val doc = html?.let { try { Jsoup.parse(it, post) } catch (_: Exception) { null } }
        val title = listOf(doc?.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(), doc?.selectFirst("h1")?.text().orEmpty(), doc?.title().orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty()
        if (ps.isEmpty() && doc != null && html != null && !strongVideo(html)) {
            val og = doc.selectFirst("meta[property=og:image],meta[name=og:image]")?.absUrl("content").orEmpty()
            if (og.startsWith("http") && !badUrl(og)) ps = listOf(Photo(og, "", 0, 0))
        }
        // mede em paralelo (6 por vez) e, se o prazo acabar, fica com as já medidas (antes: perdia todas)
        val got = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Photo>>())
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val jobs = ps.take(40).mapIndexed { i, p -> launch {
            gate.acquire()
            try {
                val (m, _) = measure(p, post)
                if (m != null) got += i to m.copy(text = listOf(p.text, title, cardText).filter { it.isNotBlank() }.distinct().joinToString(" ").take(500))
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
        // capa do post só fica de fora quando a página É um vídeo (aí a capa é a do vídeo)
        if (strongVideo(html)) doc.select("meta[property=og:image],meta[name=og:image],meta[name=twitter:image],meta[property=twitter:image],link[rel=image_src]")
            .forEach { bad += (it.absUrl("content").ifBlank { it.absUrl("href") }).substringBefore('?') }
        doc.select("video[poster]").forEach { bad += it.absUrl("poster").substringBefore('?') }
        val out = ArrayList<Photo>()
        val h1 = doc.selectFirst("h1")?.text().orEmpty()
        for (img in doc.select("img")) {
            if (img.parents().any { it.tagName() == "video" }) continue
            val a = img.closest("a[href]")
            val href = a?.absUrl("href").orEmpty()
            // link para a original: arquivo de imagem ou parâmetro com o endereço dela (imgurl=, mediaurl=…)
            val orig = when {
                a == null -> ""
                Extract.isPhotoUrl(href) && !Extract.isVideoUrl(href) -> href
                else -> href.substringAfter('?', "").split('&').map { try { java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") } catch (_: Exception) { "" } }
                    .firstOrNull { it.startsWith("http") && Extract.isPhotoUrl(it) && !Extract.isVideoUrl(it) }.orEmpty()
            }
            val u = orig.ifBlank { bestSrc(img) }
            if (u.isBlank() || u.substringBefore('?') in bad || badUrl(u)) continue
            if (JUNK_CLS.containsMatchIn(img.className() + " " + img.id()) || AVATAR_ALT.containsMatchIn(img.attr("alt"))) continue
            val w = img.attr("width").toIntOrNull() ?: 0; val h = img.attr("height").toIntOrNull() ?: 0
            val ss = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            if (orig.isBlank() && ss.isBlank() && w > 0 && h > 0 && minOf(w, h) < 200) continue   // ícone/miniatura declarada
            val cap = img.closest("figure")?.selectFirst("figcaption")?.text().orEmpty()
            var heading = ""; var p = img.parent(); var n = 0
            while (p != null && n < 4) { val hh = p.selectFirst("h1,h2,h3,h4"); if (hh != null) { heading = hh.text(); break }; p = p.parent(); n++ }
            val sure = orig.isBlank() && ss.isBlank() && minOf(w, h) >= MIN_SIDE
            out += Photo(u, listOf(img.attr("alt"), img.attr("title"), cap, heading, h1, doc.title()).filter { it.isNotBlank() }.distinct().joinToString(" ").take(400),
                if (sure) w else 0, if (sure) h else 0, w, h, orig.isNotBlank())
            if (out.size >= 300) break
        }
        return out.distinctBy { key(it.url) }
    }

    private val JUNK_CLS = Regex("""(?i)(^|[\s_-])(avatar|logo|sprite|emoji|icon|favicon|badge|ad|ads|advert|sponsor)([\s_-]|$)""")
    private val AVATAR_ALT = Regex("""(?i)(^|\s)(avatar|logo|emoji|ícone|icon)(\s|$)""")
    private val WS = Regex("\\s+")

    private fun bestSrc(img: Element): String {
        val ss = img.attr("srcset").ifBlank { img.attr("data-srcset") }
        var best = ""; var bw = -1
        if (ss.isNotBlank()) for (part in ss.split(',')) {
            val a = part.trim().split(WS); if (a.isEmpty() || a[0].isBlank()) continue
            val d = a.getOrNull(1).orEmpty()
            val w = if (d.endsWith("w", true)) d.filter(Char::isDigit).toIntOrNull() ?: 1 else ((d.removeSuffix("x").toDoubleOrNull() ?: 1.0) * 10).toInt().coerceAtMost(99)
            if (w >= bw) { bw = w; best = a[0] }
        }
        if (best.isBlank()) best = listOf("data-full", "data-large", "data-orig-file", "data-large-file", "data-original", "data-src", "data-lazy-src", "src").map { img.attr(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        return try { java.net.URL(java.net.URL(img.baseUri()), best).toString() } catch (_: Exception) { "" }
    }

    /** Título para a legenda/lista. */
    fun title(p: Photo): String = TitleFix.clean(p.text.substringBefore("  ").take(120)).ifBlank { Extract.fileName(p.url) }
}
