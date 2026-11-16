package br.geracaoai.baixartg.core

import org.json.JSONObject
import org.json.JSONTokener

/** Scripts do WebView oculto (também usados pelo harness de teste no Chrome headless). Sem dependência de Android. */
object JsScripts {
    /** Um passo de rolagem: desce até o fim, clica em "carregar mais", devolve "altura:links". */
    const val SCROLL_STEP = """(function(){
      try{
        var h=Math.max(document.body?document.body.scrollHeight:0,document.documentElement.scrollHeight);
        window.scrollTo(0,h);
        var se=document.scrollingElement; if(se) se.scrollTop=se.scrollHeight;
        var re=/^(load more|show more|more videos|ver mais|carregar mais|mostrar mais|mais v[ií]deos|mais resultados|see more|view more|plus de vid[ée]os|afficher plus)$/i;
        var n=0;
        document.querySelectorAll('button,a[role=button],a.load-more,.load-more,[class*=load-more],[class*=loadmore],[class*=show-more]').forEach(function(b){
          if(n>2) return; var t=(b.innerText||b.textContent||'').trim();
          if(re.test(t)||/load-?more|show-?more/i.test(b.className||'')){ try{b.click();n++}catch(e){} }
        });
        return h+':'+document.getElementsByTagName('a').length;
      }catch(e){return '0:0'}
    })()"""

    /**
     * Coleta anchors/vídeos do DOM renderizado, inclusive dentro de shadow DOM (web components).
     * Mesmo resultado de antes, mas linear: o texto de cada "caixa" (card/lista) é lido UMA vez por passada
     * (antes, para cada link relia a lista inteira: 2.500 links = 5 s travando a página), e os hosts de shadow DOM
     * só são procurados de novo quando a quantidade de elementos muda.
     */
    private const val COLLECT_BODY = """
      var W=window;
      var out={t:document.title||'',ch:false,a:[],v:[],u:location.href,pt:'',pd:'',nx:''};
      var S=null;
      if(INC){
        if(!W.__bptMO){W.__bptMO=1;try{new MutationObserver(function(){if(W.__bptCo)W.__bptCo.dirty=1}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,characterData:true})}catch(e){W.__bptNoMO=1}}
        if(!W.__bptCo||W.__bptCo.u!==location.href){W.__bptCo={u:location.href,dirty:1,sig:{},vs:{},hosts:null,nEl:-1}}
        S=W.__bptCo;
        if(!S.dirty&&!W.__bptNoMO)return JSON.stringify({same:1,u:location.href});
        S.dirty=0;
      }
      try{
        var nl=document.querySelector('link[rel=next],a[rel=next],a[rel~=next]');
        if(nl)out.nx=nl.href||'';
        if(!out.nx){var re=/^(pr[oó]xim[ao]|next|seguinte|mais|›|»|>|>>|next page|pr[oó]xima p[aá]gina)$/i;
          var as=document.querySelectorAll('a[href]');for(var i=0;i<as.length;i++){var t=(as[i].innerText||as[i].getAttribute('aria-label')||'').trim();if(t.length<20&&re.test(t)){out.nx=as[i].href;break}}}
      }catch(e){}
      try{
        function mc(q){var m=document.querySelector(q);return m?(m.getAttribute('content')||m.textContent||'').trim():''}
        var h1=document.querySelector('h1');
        out.pt=[mc('meta[property="og:title"]'),mc('meta[name="twitter:title"]'),h1?(h1.innerText||'').trim():'',document.title||''].filter(function(x){return x&&x.length>1}).slice(0,4).join('\n');
        var ch=[];document.querySelectorAll('[class*=channel],[class*=author],[class*=uploader],[itemprop=author],a[rel=author],[class*=teacher],[class*=professor],[class*=instructor]').forEach(function(e,i){if(i<3)ch.push((e.innerText||'').trim().slice(0,120))});
        out.pd=[mc('meta[property="og:description"]'),mc('meta[name="description"]'),mc('meta[name="author"]')].concat(ch).join(' ').replace(/\s+/g,' ').slice(0,600);
      }catch(e){}
      try{
        out.ch=/just a moment|attention required|checking your browser|verify you are human|um momento|verificando/i.test(document.title||'')
          ||!!document.querySelector('#challenge-form,#cf-challenge-running,.cf-browser-verification,[name=cf-turnstile-response],#challenge-stage,iframe[src*="challenges.cloudflare.com"]');
        var seen={},vseen={},cnt=0;
        var CARD='article,li,figure,[class*=card],[class*=item],[class*=video],[class*=post],[class*=thumb]';
        var cardMemo=(typeof Map!=='undefined')?new Map():null;
        function txt(e){var c=[e.getAttribute('title')||'',e.getAttribute('aria-label')||'',(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim()];var i=e.querySelector('img[alt]');if(i)c.push(i.getAttribute('alt')||'');var hd=e.querySelector('h1,h2,h3,h4,[class*=title]');if(hd)c.unshift((hd.innerText||hd.textContent||'').trim());return c.filter(function(x){return x&&x.trim().length>1}).join('\n').slice(0,400)}
        function card(e){var p=e.closest?e.closest(CARD):null;if(!p)return '';
          if(cardMemo){var m=cardMemo.get(p);if(m!==undefined)return m}
          var r=(p.innerText||'').replace(/\s+/g,' ').trim().slice(0,300);if(cardMemo)cardMemo.set(p,r);return r}
        function inNav(e){var p=e,k=0;while(p&&k<12){var tn=(p.tagName||'').toLowerCase();if(tn=='nav'||tn=='header'||tn=='footer')return true;p=p.parentNode||p.host;k++}return false}
        var lvl=9;
        function thumb(e){var p=e,k=0;lvl=9;while(p&&k<4){if(p.querySelector){var i=p.querySelector('img,picture,video,[style*="background-image"]');if(i){lvl=k;var s=i.currentSrc||i.src||i.getAttribute('data-src')||'';return s||'1'}}p=p.parentElement;k++}return ''}
        function one(root){
          var as=root.querySelectorAll('a[href]');
          for(var i=0;i<as.length&&cnt<4000;i++){var a=as[i];var h=a.href;if(!h||h.indexOf('http')!==0||seen[h])continue;seen[h]=1;cnt++;
            var th=thumb(a);var row=[h,txt(a),th,inNav(a)?1:0,card(a),lvl];
            if(S){var sg=row[1]+'|'+row[2]+'|'+row[3]+'|'+row[4];if(S.sig[h]===sg)continue;S.sig[h]=sg}
            out.a.push(row);}
          root.querySelectorAll('video,video source,source[src]').forEach(function(v){if(v.closest&&v.closest('track'))return;var s=v.currentSrc||v.src||v.getAttribute('src')||'';if(s&&s.indexOf('http')===0&&!vseen[s]){vseen[s]=1;if(S){if(S.vs[s])return;S.vs[s]=1}out.v.push(s)}});
        }
        function hostsOf(root){var r=[];var all=root.querySelectorAll('*');for(var j=0;j<all.length;j++){if(all[j].shadowRoot)r.push(all[j])}return r}
        function walk(root,d,hosts){
          if(!root||d>8||cnt>4000)return;
          one(root);
          var hs=hosts||hostsOf(root);
          for(var j=0;j<hs.length;j++){if(hs[j].shadowRoot)walk(hs[j].shadowRoot,d+1,null)}
        }
        var hosts=null;
        if(S){var nEl=document.getElementsByTagName('*').length;if(S.hosts&&S.nEl===nEl)hosts=S.hosts;else{hosts=hostsOf(document);S.hosts=hosts;S.nEl=nEl}}
        walk(document,0,hosts);
      }catch(e){}
      return JSON.stringify(out);
    """
    /** Coleta completa (páginas ocultas: uma chamada só por página). */
    const val COLLECT = "(function(){var INC=false;" + COLLECT_BODY + "})()"
    /**
     * Navegador visível: só o que é novo/mudou desde a última passada (antes: a página inteira a cada 3 s, até 1,2 MB),
     * e nada se a página não mudou (devolve {same:1}).
     */
    const val COLLECT_INC = "(function(){var INC=true;" + COLLECT_BODY + "})()"

    data class Collected(
        val title: String = "",
        val challenge: Boolean = false,
        val anchors: List<Anchor> = emptyList(),
        val videos: List<String> = emptyList(),
        val finalUrl: String = "",
        val pageTitles: List<String> = emptyList(),
        val pageText: String = "",
        val next: String = "",
        /** Navegador visível: a página não mudou desde a última coleta (nada a fazer). */
        val same: Boolean = false,
    ) {
        /** og:title / twitter:title / h1 / document.title — primeiro que não é rótulo de player. */
        val bestPageTitle: String get() = TitleFix.pick(*pageTitles.map { TitleFix.stripSite(it) }.toTypedArray(), TitleFix.stripSite(title))
    }
    /** thumbLevel: 0 = imagem dentro do link, 1 = no pai do link, 2-3 = mais longe (9 = sem imagem). */
    data class Anchor(val href: String, val text: String, val thumb: String, val nav: Boolean, val ctx: String = "", val thumbLevel: Int = 9) {
        /** Título do card: primeiro trecho (title/heading/aria/texto/alt) que não é lixo. */
        val bestTitle: String get() = TitleFix.pick(*text.split('\n').toTypedArray(), ctx.take(150))
    }

    /** Resultado do evaluateJavascript (string JSON dentro de string JSON) ou JSON puro. */
    fun parseCollected(raw: String?): Collected? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            var v: Any? = JSONTokener(raw.trim()).nextValue()
            if (v is String) v = JSONTokener(v).nextValue()
            val o = v as? JSONObject ?: return null
            if (o.optInt("same") == 1) return Collected(finalUrl = o.optString("u"), same = true)
            val arr = o.optJSONArray("a")
            val anchors = ArrayList<Anchor>()
            if (arr != null) for (i in 0 until arr.length()) {
                val x = arr.optJSONArray(i) ?: continue
                anchors += Anchor(x.optString(0), x.optString(1), x.optString(2), x.optInt(3) == 1, x.optString(4), x.optInt(5, 9))
            }
            val vs = o.optJSONArray("v")
            val videos = ArrayList<String>()
            if (vs != null) for (i in 0 until vs.length()) vs.optString(i).takeIf { it.isNotBlank() }?.let { videos += it }
            Collected(o.optString("t"), o.optBoolean("ch"), anchors, videos, o.optString("u"),
                o.optString("pt").split('\n').filter { it.isNotBlank() }, o.optString("pd"), o.optString("nx"))
        } catch (_: Exception) { null }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** Cards sintéticos (com miniatura ou cara de página de vídeo, fora de menus) anexados ao HTML renderizado. */
    fun cardsHtml(c: Collected): String {
        val sb = StringBuilder("<div id=\"bpt-cards\" class=\"video-list\">")
        var n = 0
        for (a in c.anchors) {
            if (a.nav) continue
            val hasThumb = a.thumb.isNotBlank()
            if (!hasThumb && !Extract.looksLikeVideoPage(a.href)) continue
            if (Extract.isPhotoUrl(a.href) && !Extract.isVideoUrl(a.href)) continue
            val img = if (hasThumb && a.thumb.startsWith("http")) "<img src=\"${esc(a.thumb)}\" alt=\"${esc(a.bestTitle)}\">" else "<img src=\"data:,\" alt=\"${esc(a.bestTitle)}\">"
            sb.append("<div class=\"item\"><a href=\"").append(esc(a.href)).append("\">").append(img)
                .append("<span class=\"title\">").append(esc(a.bestTitle)).append("</span></a></div>")
            n++
        }
        for (v in c.videos) sb.append("<video src=\"").append(esc(v)).append("\"></video>")
        return sb.append("</div>").toString()
    }

    fun synthCount(c: Collected): Int = c.anchors.count { !it.nav && (it.thumb.isNotBlank() || Extract.looksLikeVideoPage(it.href)) }

    /** Marcadores de desafio anti-bot (Cloudflare etc.) no HTML estático. */
    fun isChallengeHtml(html: String): Boolean {
        val head = html.take(20_000)
        return head.contains("challenge-platform", true) || head.contains("cf-chl", true) || head.contains("cf_chl_opt", true) ||
            Regex("""<title>\s*(just a moment|attention required|um momento)""", RegexOption.IGNORE_CASE).containsMatchIn(head) ||
            head.contains("ddos-guard", true) || head.contains("/cdn-cgi/challenge", true)
    }
}
