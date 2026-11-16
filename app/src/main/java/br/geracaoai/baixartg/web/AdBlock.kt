package br.geracaoai.baixartg.web

import android.content.Context
import android.webkit.WebResourceResponse
import br.geracaoai.baixartg.core.Extract
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Bloqueador forte do Navegador: lista de domínios (StevenBlack/hosts, MIT) por sufixo + padrões de URL de anúncios,
 * pop-ups/popunders e filtro cosmético. Nunca bloqueia o próprio site, streams de vídeo nem o servidor yt-dlp.
 */
object AdBlock {
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var enabled = true

    private val EXTRA = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com", "trafficjunky.net",
        "hilltopads.net", "clickadu.com", "adcash.com", "mgid.com", "revcontent.com", "onclickads.net", "a-ads.com",
        "admaven.com", "monetag.com", "richpartners.com", "galaksion.com", "trafficstars.com", "tsyndicate.com", "zeropark.com",
    )
    private val URL_PAT = Regex(
        """(?i)(?:/ads?/|/adserver|/adsbygoogle|/advert|/banners?/|/popunder|/popup[s_-]|/pop\.js|prebid|/pagead/|googlesyndication|doubleclick|/ad[sx]?\.js|/ad_?banner|/adframe|/adview|/vast[./?]|/vpaid|/sponsor|/tracking/|/beacon[./?]|/pixel[./?]|/interstitial|clickunder|onclick(?:ads|mega)|/afr\.php|/ajs\.php|/pu\.js|/tag\.min\.js\?|/sw-ads)""")
    private val NEVER = Regex("""(?i)(onrender\.com|telegram\.org|mega\.nz|mega\.co\.nz)""")

    fun init(c: Context) {
        Thread {
            try {
                val set = HashSet<String>(90_000)
                // o empacotador do Android pode guardar o .gz já descomprimido como "adhosts.txt"
                val ins = try { c.assets.open("adhosts.txt") } catch (_: Exception) { GZIPInputStream(c.assets.open("adhosts.txt.gz")) }
                ins.bufferedReader().useLines { ls -> ls.forEach { if (it.isNotBlank()) set += it.trim() } }
                set += EXTRA
                hosts = set
            } catch (_: Exception) { hosts = EXTRA }
        }.start()
    }

    fun base(h: String): String = WebCookies.baseDomain(h.lowercase().removePrefix("www."))

    fun isAdHost(host0: String): Boolean {
        val h = host0.lowercase().trimEnd('.')
        val set = hosts.ifEmpty { EXTRA }
        var i = 0; var cur = h
        while (true) {
            if (cur in set) return true
            i = cur.indexOf('.'); if (i < 0) return false
            cur = cur.substring(i + 1)
            if (!cur.contains('.')) return false
        }
    }

    /** true = bloquear este subrecurso. */
    fun shouldBlock(url: String, pageUrl: String?, mainFrame: Boolean): Boolean {
        if (!enabled || mainFrame) return false
        if (!url.startsWith("http", true)) return false
        if (NEVER.containsMatchIn(url)) return false
        if (Extract.mediaKindOf(url) != null || Extract.isVideoUrl(url)) return false   // streams do próprio vídeo
        val host = try { android.net.Uri.parse(url).host ?: return false } catch (_: Exception) { return false }
        val pageHost = pageUrl?.let { try { android.net.Uri.parse(it).host } catch (_: Exception) { null } }
        val firstParty = pageHost != null && base(host) == base(pageHost)
        if (!firstParty && isAdHost(host)) return true
        val path = url.substringAfter(host)
        return URL_PAT.containsMatchIn(path) && !firstParty || (firstParty && Regex("""(?i)/(popunder|pu\.js|pop\.js|adserver|prebid)""").containsMatchIn(path))
    }

    fun empty(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * Navegação da página principal. Toque do usuário em http(s): sempre libera (mesma aba). Mesmo site: sempre libera.
     * Bloqueia só esquemas de app/loja (intent://, market://...) e redirecionamento automático para OUTRO site sem toque.
     */
    fun blockNavigation(target: String, currentPage: String?, hasGesture: Boolean, isRedirect: Boolean): Boolean {
        val sch = target.substringBefore(':').lowercase()
        if (sch != "http" && sch != "https") return sch == "intent" || sch == "market" || enabled && sch !in setOf("about", "data", "blob", "javascript")
        if (!enabled || hasGesture) return false
        val host = try { android.net.Uri.parse(target).host ?: return false } catch (_: Exception) { return false }
        val cur = currentPage?.let { try { android.net.Uri.parse(it).host } catch (_: Exception) { null } } ?: return false
        if (base(host) == base(cur)) return false
        if (isRedirect) return isAdHost(host)
        return true
    }

    /** Pop-ups bloqueados (window.open devolve janela falsa) + CSS cosmético + remoção de overlays reinseridos. */
    const val COSMETIC_JS = """(function(){
      if(window.__bptAB)return 'ok';window.__bptAB=1;
      var lastTap=0;
      ['touchstart','touchend','mousedown','click','pointerdown'].forEach(function(ev){try{document.addEventListener(ev,function(){lastTap=Date.now()},true)}catch(e){}});
      try{
        var fake={closed:false,close:function(){},focus:function(){},blur:function(){},postMessage:function(){},location:{href:''},document:{write:function(){},close:function(){}}};
        var nOpen=function(u){
          // aberto por toque: segue na mesma aba; sem toque (pop-up/popunder): ignora
          if(u&&Date.now()-lastTap<1200){try{var a=new URL(u,location.href);if(/^https?:$/.test(a.protocol)){location.href=a.href;return window}}catch(e){}}
          return fake};
        try{Object.defineProperty(window,'open',{value:nOpen,writable:false,configurable:false})}catch(e){window.open=nOpen}
      }catch(e){}
      var sel='[id^="google_ads"],[id^="div-gpt-ad"],ins.adsbygoogle,[class*="adsbygoogle"],[id*="taboola"],[class*="taboola"],[id*="outbrain"],[class*="outbrain"],'+
        '[class^="ad-"],[class*=" ad-"],[id^="ad-"],[id^="ad_"],[class^="ads-"],[class*=" ads-"],[class*="advert"],[id*="advert"],'+
        '[class*="banner-ad"],[class*="ad-banner"],[class*="ad-container"],[class*="ad-wrapper"],[class*="adbox"],[class*="ad-slot"],'+
        '[class*="popunder"],[class*="interstitial"],[id*="interstitial"],[class*="native-ad"],[data-ad-slot],[data-adzone],'+
        'iframe[src*="doubleclick"],iframe[src*="googlesyndication"],iframe[src*="exoclick"],iframe[src*="juicyads"],iframe[src*="adsterra"],iframe[src*="/ads/"]';
      function keep(e){
        try{
          var t=(e.tagName||'').toLowerCase();
          if(t==='a'||t==='video'||t==='source'||t==='html'||t==='body'||t==='main')return true;
          if(e.querySelector('video,a img,a picture,a[href] [style*="background"],[class*="player"],.jwplayer,.video-js,.plyr'))return true;
          if(e.closest('video,[class*="player"],.jwplayer,.video-js,.plyr'))return true;
          if(e.querySelectorAll('a[href]').length>3)return true;
        }catch(x){}
        return false;
      }
      function hideAds(){
        try{var els=document.querySelectorAll(sel);for(var i=0;i<els.length;i++){var e=els[i];if(e.__bptK)continue;e.__bptK=1;if(keep(e))continue;e.style.setProperty('display','none','important')}}catch(x){}
      }
      function sweep(){
        hideAds();
        try{
          var vw=window.innerWidth,vh=window.innerHeight,host=location.hostname.split('.').slice(-2).join('.');
          var els=document.body?document.body.querySelectorAll('a,div'):[];
          for(var i=0;i<els.length;i++){var e=els[i];var cs=getComputedStyle(e);
            if(cs.position!=='fixed')continue;
            if((parseInt(cs.zIndex)||0)<999)continue;
            var r=e.getBoundingClientRect();if(r.width*r.height<vw*vh*0.5)continue;
            if(e.querySelector('video,iframe,input,form,img')||e.closest('video'))continue;
            var links=e.tagName==='A'?[e]:e.querySelectorAll('a[href]');
            if(links.length!==1)continue;
            var h='';try{h=new URL(links[0].href).hostname}catch(x){}
            if(!h||h.split('.').slice(-2).join('.')===host)continue;
            if((e.innerText||'').trim().length>40)continue;
            e.remove();
          }
        }catch(x){}
      }
      sweep();
      try{var t=0;new MutationObserver(function(){clearTimeout(t);t=setTimeout(sweep,300)}).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
      setTimeout(sweep,1500);setTimeout(sweep,4000);
      return 'ok';
    })()"""
}
