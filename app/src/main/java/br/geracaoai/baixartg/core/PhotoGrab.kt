package br.geracaoai.baixartg.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * v1.9.5: coletor de fotos SIMPLES e isolado (substitui o pipeline antigo do navegador).
 * O script devolve as imagens visíveis da página (src/srcset/lazy/picture/link para a original/fundo CSS/código de
 * busca de imagens/página que É uma imagem); aqui só tira lixo óbvio e repetidos. Nada de medir pela rede,
 * nada de "tem vídeo na página", nada de chaves de vídeo.
 */
object PhotoGrab {
    const val VERSION = "g2"
    const val JS = """(function(){
var VER='g2';
try{
var D=document,W=window,L=location.href,out=[],seen={},rej={},raw=0,T0=Date.now();
function R(k){rej[k]=(rej[k]||0)+1}
function abs(u){if(!u)return '';u=String(u).trim();if(!u||/^(data|blob|javascript|about):/i.test(u))return '';try{var x=new URL(u,L).href;return /^https?:/i.test(x)?x:''}catch(e){return ''}}
var EXT=/\.(jpe?g|jfif|png|webp|avif|gif|bmp|heic)$/i;
function upgrade(u){if(!u)return u;
  var m=/^(https?:\/\/[^?#]+)\/thumb\/([^?#]+\/([^/?#]+\.(?:jpe?g|png|webp)))\/[^/?#]*/i.exec(u);
  if(m)return m[1].replace('://thumb.','://upload.')+'/'+m[2];
  if(/pbs\.twimg\.com/i.test(u)){var v=u.replace(/([?&]name=)(?:small|medium|thumb|tiny|240x240|360x360|900x900|large)/ig,'$1orig');v=v.replace(/:(?:small|medium|thumb|large|orig)$/i,':orig');if(!/[?&]name=/i.test(v)&&!/:(?:small|medium|thumb|large|orig)/i.test(u))v+=(v.indexOf('?')>=0?'&':'?')+'name=orig';return v}
  var v=u.replace(/-\d{2,5}x\d{2,5}(?=\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#]|$))/ig,'');
  v=v.replace(/[_-](?:thumb(?:nail)?|small|medium|mini|preview|scaled)(?=\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#]|$))/ig,'');
  v=v.replace(/\/thumbs?\//ig,'/');
  try{var U=new URL(v),ch=false;['w','h','width','height','resize','size','maxwidth','maxheight','iw','ih'].forEach(function(p){var val=U.searchParams.get(p);if(val&&/^\d+$/.test(val)&&parseInt(val,10)<800){U.searchParams.delete(p);ch=true}});if(ch)return U.href}catch(e){}
  return v}
function isImgFile(u){if(!u)return false;try{var U=new URL(u);var last=U.pathname.split('/').pop()||'';try{last=decodeURIComponent(last)}catch(e){}if(last.indexOf(':')>=0)return false;return EXT.test(last)||/[?&](format|fm|mime)=(jpe?g|png|webp|avif)/i.test(U.search)}catch(e){return false}}
var JUNKURL=/(^|[\/_.\-=])(sprites?|favicons?|emojis?|spacer|blank|pixel|1x1|beacon|tracking|loading|loader|spinner|placeholder)([\/_.\-=\d]|$)|doubleclick|googlesyndication|adservice|\/ads?\/|\.svg([?#]|$)|^https?:\/\/[^\/]*(gravatar\.com)/i;
var JUNKNAME=/(^|[\s_\-])(avatar|logo|icon|ico|emoji|badge|sprite|favicon|flag|profile-pic|userpic)([\s_\-]|$)/i;
var VIDHREF=/(\/watch([\/?#]|$)|\/videos?\/|\/embed\/|\/shorts\/|\/reels?\/|youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com|\.mp4([?#]|$)|\.m3u8([?#]|$))/i;
var DUR=/(^|\s)(\d{1,2}:)?\d{1,2}:\d{2}(\s|$)/;
var posters={};try{D.querySelectorAll('video[poster]').forEach(function(v){var p=abs(v.getAttribute('poster'));if(p)posters[p.split('?')[0]]=1})}catch(e){}
var isImgDoc=/^image\//i.test(D.contentType||'');
function add(u,w,h,th,t,s,o,l){u=upgrade(u);if(!u||seen[u])return;seen[u]=1;out.push({u:u,w:w||0,h:h||0,th:th||'',t:(t||'').replace(/\s+/g,' ').trim().slice(0,300),s:s,o:o?1:0,l:l||''})}
if(isImgDoc){var im0=D.images[0];add(L,im0?im0.naturalWidth:0,im0?im0.naturalHeight:0,L,D.title||'','page',1);return JSON.stringify({v:VER,n:D.images.length,raw:1,p:out,r:rej,doc:1})}
// assinatura: se nada mudou desde a última passada (< 6 s), não refaz
var sig=D.images.length+'|'+D.links.length+'|'+(D.documentElement.scrollHeight||0);
for(var q=0;q<D.images.length;q++){var cq=D.images[q];sig+='|'+((cq.currentSrc||cq.src||'').length)+(cq.complete?'c':'')}
if(W.__bptG&&W.__bptG.sig===sig&&W.__bptG.u===L&&Date.now()-W.__bptG.at<6000)return JSON.stringify({v:VER,same:1,n:D.images.length});
W.__bptG={sig:sig,u:L,at:Date.now()};
function pickSet(ss){var best={u:'',w:0,x:0};if(!ss)return best;ss.split(/,\s+|,(?=https?:|\/)/).forEach(function(p){var a=p.trim().split(/\s+/);if(!a[0])return;var d=a[1]||'1x';if(/w$/i.test(d)){var w=parseInt(d)||0;if(w>=best.w){best.w=w;best.u=a[0]}}else if(!best.w){var x=parseFloat(d)||1;if(x>=best.x){best.x=x;best.u=a[0]}}});best.u=abs(best.u);return best}
function linkOrig(a){if(!a)return '';var h=a.href||'';if(!/^https?:/i.test(h))return '';if(h.split('#')[0]===L.split('#')[0])return '';if(isImgFile(h))return h;
  try{var U=new URL(h);var ks=['imgurl','mediaurl','img_url','image_url','imageurl','image','img','url','u','src','iu','media'];for(var i=0;i<ks.length;i++){var v=U.searchParams.get(ks[i]);if(v&&/^https?:\/\//i.test(v)&&isImgFile(v))return v}}catch(e){}
  var m=a.getAttribute('m');if(m){try{var mj=JSON.parse(m);if(mj&&mj.murl&&/^https?:/i.test(mj.murl))return mj.murl}catch(e){}}
  return ''}
function txt(img,a){var t=[img.getAttribute('alt')||'',img.getAttribute('title')||''];if(a)t.push(a.getAttribute('title')||'',a.getAttribute('aria-label')||'');
  try{var f=img.closest('figure');if(f){var fc=f.querySelector('figcaption');if(fc)t.push(fc.textContent||'')}}catch(e){}
  return t.filter(function(x){return x&&x.trim()}).join(' ')}
function videoCard(a){if(!a)return false;var h=a.href||'';if(VIDHREF.test(h))return true;try{if(a.querySelector('video'))return true;var tx=(a.innerText||'');if(tx.length<400&&DUR.test(tx))return true;var p=a.parentElement;if(p){var pt=p.innerText||'';if(pt.length<300&&DUR.test(pt))return true}}catch(e){}return false}
var imgs=D.images;
for(var i=0;i<imgs.length&&out.length<600;i++){var img=imgs[i];raw++;
  try{
    var cur=img.currentSrc||img.src||'';if(/^(data|blob):/i.test(cur))cur='';cur=abs(cur);
    var a=img.closest('a[href]');var lo=linkOrig(a);
    var set=pickSet(img.getAttribute('srcset')||img.getAttribute('data-srcset')||'');
    try{var pic=img.closest('picture');if(pic)pic.querySelectorAll('source').forEach(function(s){var b=pickSet(s.getAttribute('srcset')||s.getAttribute('data-srcset')||'');if(b.u&&(b.w>set.w||(!set.w&&b.x>set.x)))set=b})}catch(e){}
    var big=abs(img.getAttribute('data-full')||img.getAttribute('data-full-url')||img.getAttribute('data-large')||img.getAttribute('data-large-file')||img.getAttribute('data-orig-file')||img.getAttribute('data-zoom-src')||img.getAttribute('data-zoom-image')||img.getAttribute('data-hi-res-src')||img.getAttribute('data-highres')||'');
    var lazy=abs(img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||img.getAttribute('data-lazy')||img.getAttribute('data-url')||'');
    var nw=img.naturalWidth||0,nh=img.naturalHeight||0;if(!cur){nw=0;nh=0}
    var r=img.getBoundingClientRect(),rw=r.width||0,rh=r.height||0;
    var aw=parseInt(img.getAttribute('width'))||0,ah=parseInt(img.getAttribute('height'))||0;
    var u='',w=0,h=0,s='img';
    if(lo){u=lo;s='link'}
    else if(big){u=big;s='big'}
    else if(set.u&&(set.w>nw||(!set.w&&set.x>1)||!cur)){u=set.u;s='srcset';if(set.w){w=set.w;h=nw?Math.round(set.w*nh/nw):(rw?Math.round(set.w*rh/rw):0)}}
    else if(lazy&&(!cur||JUNKURL.test(cur)||(img.complete&&nw>0&&nw<=10))){u=lazy;s='lazy'}
    else if(cur){u=cur;w=nw;h=nh}
    else if(lazy){u=lazy;s='lazy'}
    if(!u){R('sem endereço');continue}
    var base=u.split('?')[0];
    if(posters[base]||(cur&&posters[cur.split('?')[0]])){R('capa de vídeo');continue}
    if(!lo&&videoCard(a)){R('miniatura de vídeo');continue}
    if(JUNKURL.test(u)&&!lo){R('ícone/sprite/anúncio');continue}
    var known=Math.max(w,h,nw,nh);
    var shown=Math.max(rw,rh);
    var nm=(img.className&&typeof img.className==='string'?img.className:'')+' '+(img.id||'')+' '+(img.getAttribute('alt')||'');
    if(JUNKNAME.test(nm)&&Math.max(known,shown*2)<400){R('ícone/logo');continue}
    if(!lo&&!big){
      if(w>0&&h>0&&Math.max(w,h)<300){R('pequena');continue}
      if(!w&&known>0&&Math.max(nw,nh)<300&&!set.u){R('pequena');continue}
      if(!known){ if((aw&&ah&&Math.max(aw,ah)<150)||(shown>0&&shown<60)){R('pequena');continue} }
      var mn=Math.min(w||nw,h||nh),mx=Math.max(w||nw,h||nh);if(mn>0&&mx/mn>5){R('faixa/banner');continue}
    }
    var cl=(a&&a.href&&/^https?:/i.test(a.href)&&a.href.split('#')[0]!==L.split('#')[0]&&!videoCard(a))?a.href:'';
    add(u,w,h,cur||u,txt(img,a),s,!!(lo||big),cl);
  }catch(e){R('erro')}
}
// links diretos para arquivos de imagem (anexos de fórum, galerias) sem <img> dentro
try{var ls=D.links;for(var j=0;j<ls.length&&out.length<600;j++){var A=ls[j];if(A.querySelector&&A.querySelector('img'))continue;var hh=A.href||'';if(!isImgFile(hh)||hh.split('#')[0]===L.split('#')[0])continue;if(JUNKURL.test(hh))continue;raw++;add(hh,0,0,'',(A.getAttribute('title')||A.textContent||''),'alink',1)}}catch(e){}
// imagens de fundo (CSS) em blocos grandes
try{var els=D.querySelectorAll('[style*="background"],[data-bg],[data-background],[data-bg-src],[data-background-image],[class*=photo],[class*=image],[class*=img],[class*=gallery],[class*=hero],[class*=cover],[class*=slide],[class*=banner],[class*=bg]');
  for(var k=0;k<els.length&&k<1500&&out.length<600&&Date.now()-T0<250;k++){var el=els[k];if(el.tagName==='IMG')continue;
    var bu=el.getAttribute('data-bg')||el.getAttribute('data-background')||el.getAttribute('data-bg-src')||el.getAttribute('data-background-image')||'';
    if(!bu){var bg=(el.style&&el.style.backgroundImage)||'';if(!bg||bg==='none'){bg=W.getComputedStyle(el).backgroundImage||''}var mm=/url\(["']?([^"')]+)["']?\)/.exec(bg);if(mm)bu=mm[1]}
    bu=abs(bu.replace(/^url\(["']?|["']?\)$/g,''));if(!bu)continue;raw++;
    var br=el.getBoundingClientRect();if(Math.max(br.width,br.height)<150||Math.min(br.width,br.height)<80){R('fundo pequeno');continue}
    if(JUNKURL.test(bu)){R('ícone/sprite/anúncio');continue}
    if(posters[bu.split('?')[0]]){R('capa de vídeo');continue}
    var ba=el.closest('a[href]');if(videoCard(ba)){R('miniatura de vídeo');continue}
    add(bu,0,0,bu,(el.getAttribute('aria-label')||el.getAttribute('title')||''),'bg',0)}
}catch(e){}
// resultados de busca de imagens (Google etc.): endereço ORIGINAL + tamanho no código da página ["https://…",altura,largura]
try{var tot=0,re=/\["((?:https?:)?\/\/(?:[^"\\]|\\.){8,2000}?)",(\d{2,5}),(\d{2,5})\]/g,TS=Date.now();
  for(var sI=0;sI<D.scripts.length&&tot<4000000&&Date.now()-TS<150;sI++){var sc=D.scripts[sI];if(sc.src)continue;var tx=sc.textContent||'';if(tx.length>2000000||tx.indexOf('",')<0)continue;tot+=tx.length;var m2;re.lastIndex=0;
    while((m2=re.exec(tx))&&out.length<600){var su;try{su=JSON.parse('"'+m2[1]+'"')}catch(e){continue}if(su.indexOf('//')===0)su='https:'+su;
      var hh2=parseInt(m2[2]),ww2=parseInt(m2[3]);raw++;
      if(/gstatic\.com\/images\?q=tbn|\/favicon|\.svg|\.js([?#]|$)|\.css([?#]|$)/i.test(su)){continue}
      if(Math.max(hh2,ww2)<300||Math.min(hh2,ww2)<100){R('pequena');continue}
      add(su,ww2,hh2,'',(D.title||''),'script',1)}}
}catch(e){}
// capa do post (og:image) só se nada mais foi achado e a página não é de vídeo
try{if(!out.length&&!D.querySelector('meta[property="og:video"],meta[property="og:video:url"],video')){var og=D.querySelector('meta[property="og:image"],meta[name="og:image"],meta[name="twitter:image"],meta[property="twitter:image"]');var ou=og?abs(og.getAttribute('content')):'';if(ou&&!JUNKURL.test(ou)){raw++;add(ou,0,0,ou,(D.title||''),'og',0)}}}catch(e){}
return JSON.stringify({v:VER,n:imgs.length,raw:raw,p:out,r:rej,ms:Date.now()-T0});
}catch(e){try{return JSON.stringify({v:VER,p:[],r:{},n:document.images.length,err:String((e&&e.message)||e).slice(0,200)})}catch(x){return '{"v":"g2","p":[],"err":"?"}'}}
})()"""

    data class Cand(val url: String, val w: Int, val h: Int, val thumb: String, val text: String, val src: String, val orig: Boolean, val link: String = "")
    data class Res(val ok: Boolean, val same: Boolean = false, val ver: String = "", val imgs: Int = -1, val raw: Int = 0,
                   val cands: List<Cand> = emptyList(), val rejects: Map<String, Int> = emptyMap(), val err: String = "")

    /** Resposta do evaluateJavascript (string JSON dentro de string JSON) ou JSON puro. */
    fun parse(raw: String?): Res {
        if (raw.isNullOrBlank() || raw == "null") return Res(false, err = "sem resposta do script")
        return try {
            var v: Any? = JSONTokener(raw.trim()).nextValue()
            if (v is String) v = JSONTokener(v).nextValue()
            val o = v as? JSONObject ?: return Res(false, err = "resposta ilegível")
            val rj = LinkedHashMap<String, Int>(); o.optJSONObject("r")?.let { r -> r.keys().forEach { k -> rj[k] = r.optInt(k) } }
            if (o.optInt("same") == 1) return Res(true, same = true, ver = o.optString("v"), imgs = o.optInt("n", -1))
            val a = o.optJSONArray("p") ?: JSONArray()
            val cs = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { p ->
                Cand(p.optString("u"), p.optInt("w"), p.optInt("h"), p.optString("th"), p.optString("t"), p.optString("s"), p.optInt("o") == 1, p.optString("l")) } }
            Res(true, false, o.optString("v"), o.optInt("n", -1), o.optInt("raw"), cs, rj, o.optString("err"))
        } catch (e: Exception) { Res(false, err = "resposta ilegível (" + raw.length + " letras)") }
    }

    /** Chave da foto = endereço completo normalizado (sem colisão; separada das chaves de vídeo). */
    fun key(u: String): String {
        val s = u.trim().substringBefore('#')
        val scheme = s.substringBefore("://", "").lowercase()
        val rest = s.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?').lowercase().removePrefix("www.")
        val tail = rest.substring(rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it })
        return "pu:" + (if (scheme == "http") "https" else scheme) + "://" + host + tail
    }

    private val SMALL_PARAM = Regex("""(?i)[?&](?:w|h|width|height|s|size)=(\d{1,3})(?:&|$)""")
    private val JUNK = Regex("""(?i)/rp/[^/]+\.(?:png|gif)|/static/images/|donate|/profile-[^/]*image|/avatars?/|[_./-](?:badge|logo)[_./-]|_badge_|\.svg(?:[?#/]|$)|data:|blob:""")

    /** Lixo óbvio que o script não pega: avatar/ícone pedido pequeno no próprio endereço (?w=32), arquivos do site. */
    fun junk(c: Cand): String? {
        if (!c.url.startsWith("http")) return "sem endereço"
        if (JUNK.containsMatchIn(c.url)) return "ícone/arquivo do site"
        if (!c.orig && c.w == 0) SMALL_PARAM.findAll(c.url).map { it.groupValues[1].toInt() }.maxOrNull()?.let { if (it < 150) return "pequena (no endereço)" }
        return null
    }

    private val MW_THUMB = Regex("""^(https?://[^?#]+)/thumb/([^?#]+/([^/?#]+\.(?:jpe?g|png|webp)))/[^/?#]*(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val WP_SIZE = Regex("""-\d{2,5}x\d{2,5}(?=\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#]|$))""", RegexOption.IGNORE_CASE)
    private val THUMB_SUF = Regex("""[_-](?:thumb(?:nail)?|small|medium|mini|preview|scaled)(?=\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#]|$))""", RegexOption.IGNORE_CASE)
    private val THUMBS_DIR = Regex("""/thumbs?/""", RegexOption.IGNORE_CASE)
    private val TW_NAME = Regex("""([?&]name=)(?:small|medium|thumb|tiny|240x240|360x360|900x900|large)""", RegexOption.IGNORE_CASE)
    private val TW_COLON = Regex(""":(?:small|medium|thumb|large|orig)$""", RegexOption.IGNORE_CASE)
    private val SIZE_Q = setOf("w", "h", "width", "height", "resize", "size", "maxwidth", "maxheight", "iw", "ih")
    private val TBN = Regex("""(?i)encrypted-tbn|gstatic\.com/images\?q=tbn""")

    /** Versão maior conhecida: Wikimedia, sufixo -300x200/_thumb, /thumbs/, query pequena, pbs.twimg.com name=orig. */
    fun upgrade(u: String, strong: Boolean = false): String {
        if (u.isBlank()) return u
        MW_THUMB.find(u)?.let { m -> return m.groupValues[1].replace("://thumb.", "://upload.") + "/" + m.groupValues[2] }
        var v = u
        if (v.contains("pbs.twimg.com", true)) {
            v = TW_NAME.replace(v, "$1orig")
            v = TW_COLON.replace(v, ":orig")
            if (!v.contains("name=", true) && !TW_COLON.containsMatchIn(u)) v += (if ('?' in v) "&" else "?") + "name=orig"
            return v
        }
        v = WP_SIZE.replace(v, "")
        v = THUMB_SUF.replace(v, "")
        v = THUMBS_DIR.replace(v, "/")
        v = stripSmallSizeQuery(v, all = strong)
        return v
    }

    /** Depois do download, se a foto veio pequena: tira qualquer limitador de tamanho no endereço. */
    fun upgradeStrong(u: String): String = upgrade(u, strong = true)

    private val CROP_SUF = Regex("""[_-](?:s|t|m|xs|sq|square|crop|cropped|mini|thumb(?:nail)?)(?=\.(?:jpe?g|png|webp|gif|bmp|avif)(?:[?#]|$))""", RegexOption.IGNORE_CASE)
    private val CROP_DIR = Regex("""/(?:crop|cropped|square|squares|thumbs?|small)/""", RegexOption.IGNORE_CASE)
    private val CROP_Q = setOf("crop", "fit", "aspect", "sq", "s", "resize", "quality", "q")

    /** Gera variações prováveis da URL original sem recorte/miniatura (para comparar por bytes). */
    fun upgradeVariants(u: String): List<String> {
        val out = LinkedHashSet<String>()
        val up = upgradeStrong(u)
        if (up != u) out += up
        for (baseUrl in listOf(up, u)) {
            val v1 = CROP_SUF.replace(baseUrl, "")
            if (v1 != baseUrl) out += v1
            val v2 = CROP_DIR.replace(v1, "/")
            if (v2 != v1) out += v2
            val v3 = stripCropQuery(v2)
            if (v3 != v2) out += v3
            val v4 = stripCropQuery(baseUrl)
            if (v4 != baseUrl) out += v4
        }
        out.remove(u)
        return out.toList()
    }

    private fun stripCropQuery(u: String): String = try {
        val qIdx = u.indexOf('?')
        if (qIdx < 0) u else {
            val base = u.substring(0, qIdx)
            val frag = u.substringAfter('#', "").let { if (it.isEmpty() && '#' !in u) "" else "#$it" }
            val kept = u.substring(qIdx + 1).substringBefore('#').split('&').filter { part ->
                part.isNotEmpty() && part.substringBefore('=').lowercase() !in (CROP_Q + SIZE_Q)
            }
            base + (if (kept.isEmpty()) "" else kept.joinToString("&", prefix = "?")) + frag
        }
    } catch (_: Exception) { u }

    private fun stripSmallSizeQuery(u: String, all: Boolean): String = try {
        val qIdx = u.indexOf('?')
        if (qIdx < 0) u
        else {
            val base = u.substring(0, qIdx)
            val frag = u.substringAfter('#', missingDelimiterValue = "").let { if (it.isEmpty() && '#' !in u) "" else "#$it" }
            val raw = u.substring(qIdx + 1).substringBefore('#')
            val kept = ArrayList<String>()
            var changed = false
            for (part in raw.split('&')) {
                if (part.isEmpty()) continue
                val n = part.substringBefore('=').lowercase()
                val v = part.substringAfter('=', "")
                val num = v.toIntOrNull()
                if (n in SIZE_Q && num != null && (all || num < 800)) { changed = true; continue }
                kept += part
            }
            if (!changed) u else base + (if (kept.isEmpty()) "" else kept.joinToString("&", prefix = "?")) + frag
        }
    } catch (_: Exception) { u }

    /** Chave da “mesma foto” (miniatura e original caem no mesmo grupo). */
    fun family(u: String): String {
        val up = upgradeStrong(u)
        val bare = WP_SIZE.replace(up.substringBefore('#').substringBefore('?'), "")
        return key(bare)
    }

    /** Se a original falhar ao baixar: tamanho padrão grande do Wikimedia. */
    fun fallback(u: String): String? {
        val m = Regex("""^(https?://upload\.wikimedia\.org/[^?#]*?/(?:commons|[a-z]{2,3})/)([0-9a-f]/[0-9a-f]{2}/([^/?#]+))$""", RegexOption.IGNORE_CASE).find(u) ?: return null
        return m.groupValues[1] + "thumb/" + m.groupValues[2] + "/1280px-" + m.groupValues[3]
    }

    /** Escolhe o que entra na lista: sem lixo, sem repetidos; a maior versão da mesma foto ganha da miniatura. */
    fun select(cands: List<Cand>, reject: (String) -> Unit = {}): List<Cand> {
        val out = LinkedHashMap<String, Cand>()
        val hasOrig = cands.any { it.orig && !TBN.containsMatchIn(it.url) }
        for (c0 in cands) {
            val why = junk(c0); if (why != null) { reject(why); continue }
            if (hasOrig && TBN.containsMatchIn(c0.url) && !c0.orig) { reject("miniatura de busca"); continue }
            val c = c0.copy(url = upgrade(c0.url), thumb = c0.thumb.ifBlank { c0.url })
            val k = family(c.url)
            val prev = out[k]
            val better = prev == null || (c.orig && !prev.orig) || maxOf(c.w, c.h) > maxOf(prev.w, prev.h) ||
                (maxOf(c.w, c.h) == maxOf(prev.w, prev.h) && c.url.length > prev.url.length && !TBN.containsMatchIn(c.url))
            if (better) out[k] = c
        }
        return out.values.toList()
    }

    /** Extensão pelo conteúdo do arquivo (bytes do começo). null = não é imagem. */
    fun imageExt(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(o: Int, n: Int) = if (o + n <= b.size) String(CharArray(n) { (b[o + it].toInt() and 0xFF).toChar() }) else ""
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x89 && ascii(1, 3) == "PNG" -> "png"
            ascii(0, 4) == "GIF8" -> "gif"
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "webp"
            ascii(4, 4) == "ftyp" && ascii(8, 4).lowercase().let { it.startsWith("avi") } -> "avif"
            ascii(4, 4) == "ftyp" && ascii(8, 4).lowercase().let { it.startsWith("hei") || it.startsWith("mif") } -> "heic"
            ascii(0, 2) == "BM" -> "bmp"
            else -> null
        }
    }

    /** Telegram aceita como FOTO: jpg/png/webp até 10 MB, lados somados até 10.000 px, proporção até 20. Senão: arquivo. */
    fun sendAsPhoto(ext: String, bytes: Long, w: Int, h: Int): Boolean =
        ext in setOf("jpg", "jpeg", "png", "webp") && bytes in 1..(10L * 1024 * 1024) &&
            (w <= 0 || h <= 0 || (w + h <= 10_000 && maxOf(w, h).toDouble() / minOf(w, h) <= 20.0))
}
