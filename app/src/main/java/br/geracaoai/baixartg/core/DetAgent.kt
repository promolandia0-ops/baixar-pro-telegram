package br.geracaoai.baixartg.core

/**
 * Detetive — agente injetado em TODOS os quadros da página (inclusive iframes de players de outro site, como
 * Vimeo/Panda/Hotmart), antes dos scripts do site. Ele:
 *  - lê respostas de rede (fetch/XHR) que trazem endereços de vídeo dentro de JSON/JS;
 *  - percebe quando um <video> começa a carregar e quando o site usa proteção DRM;
 *  - sob comando do app: varre o quadro inteiro (vídeos, iframes, recursos carregados, scripts, atributos,
 *    variáveis globais de players, APIs do JW Player/Video.js/Wistia/Flowplayer), lista fotos na maior versão,
 *    dá play sem som, carrega players "preguiçosos" e fecha janelas que cobrem o player.
 * Conversa com o app pelo objeto `bptDet` (WebMessageListener) e também responde direto ao evaluateJavascript.
 */
object DetAgent {
    const val JS = """(function(){
if(window.__bptDet)return 'ok';
var D=window.__bptDet={v:3,drm:'',posts:0,media:{},blob:0};
var B=null;try{B=window.bptDet||null}catch(e){}
var TOP=false;try{TOP=(window===window.top)}catch(e){}
var TOK=/m3u8|\.mpd|\.mp4|\.webm|\.m4v|\.mov|\.mkv|mpegurl|dash\+xml|manifest|playlist|hls|vimeo|panda|wistia|jwplayer|jwpcdn|mux\.com|cloudflarestream|videodelivery|mediadelivery|b-cdn|brightcove|kaltura|converteai|vturb|hotmart|spotlightr|sprout|vidyard|loom|youtube|youtu\.be|dailymotion|drive\.google|akamai|cloudfront|playback|stream|video_url|videoUrl|file["']?\s*:|src["']?\s*:|sources|contentUrl|embedUrl/i;
var CAP=1500000;
function post(o){try{if(!B||!B.postMessage)return;o.f=String(location.href).slice(0,2000);o.top=TOP?1:0;B.postMessage(JSON.stringify(o))}catch(e){}}
D.post=post;
function abs(u){try{return new URL(String(u),location.href).href}catch(e){return String(u||'')}}
function vis(el){try{var r=el.getBoundingClientRect();return r.width>0&&r.height>0}catch(e){return false}}
function walk(root,fn,cap){var n=0;var st=[root];while(st.length&&n<cap){var node=st.pop();if(!node)continue;var kids=node.children||[];for(var i=kids.length-1;i>=0;i--){var el=kids[i];n++;try{fn(el)}catch(e){}if(el.shadowRoot)st.push(el.shadowRoot);st.push(el)}}return n}
function q(sel){var out=[];try{document.querySelectorAll(sel).forEach(function(e){out.push(e)})}catch(e){}walk(document,function(el){if(el.shadowRoot){try{el.shadowRoot.querySelectorAll(sel).forEach(function(e){out.push(e)})}catch(e){}}},6000);return out}
/* ---- respostas de rede com endereço de vídeo dentro (JSON/JS/texto) ---- */
function sniffText(u,ct,txt){try{if(!txt||typeof txt!=='string')return;if(D.posts>150)return;if(txt.length>CAP)txt=txt.slice(0,CAP);if(!TOK.test(txt)&&!/mpegurl|dash/i.test(ct||'')&&!/^\s*#EXTM3U|<MPD[\s>]/.test(txt.slice(0,2000)))return;D.posts++;post({t:'net',u:String(u||'').slice(0,2000),ct:String(ct||'').slice(0,100),b:txt.slice(0,400000)})}catch(e){}}
function textish(ct,u){return /json|javascript|text|xml|mpegurl|dash|ecmascript/i.test(ct||'')||/\.(json|m3u8|mpd)(\?|$)|config|manifest|playlist|player|graphql|api\//i.test(String(u||''))}
try{var of=window.fetch;if(of&&!of.__bptDet){var nf=function(input,init){var p=of.apply(this,arguments);try{p.then(function(resp){try{var ct=(resp.headers&&resp.headers.get('content-type'))||'';var len=+((resp.headers&&resp.headers.get('content-length'))||0);if(textish(ct,resp.url)&&(!len||len<3000000)){resp.clone().text().then(function(t){sniffText(resp.url,ct,t)},function(){})}}catch(e){}},function(){})}catch(e){}return p};nf.__bptDet=1;window.fetch=nf}}catch(e){}
try{var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){try{this.__bdu=abs(u)}catch(e){}return XO.apply(this,arguments)};XMLHttpRequest.prototype.send=function(){var x=this;try{x.addEventListener('load',function(){try{var ct=x.getResponseHeader('content-type')||'';if(!textish(ct,x.__bdu))return;var t='';if(x.responseType===''||x.responseType==='text')t=x.responseText;else if(x.responseType==='json')t=JSON.stringify(x.response);sniffText(x.__bdu,ct,t)}catch(e){}})}catch(e){}return XS.apply(this,arguments)}}catch(e){}
/* ---- proteção DRM (Widevine/PlayReady/FairPlay) ---- */
try{var rk=navigator.requestMediaKeySystemAccess;if(rk){navigator.requestMediaKeySystemAccess=function(ks){try{D.drm=String(ks);post({t:'drm',ks:String(ks)})}catch(e){}return rk.apply(this,arguments)}}}catch(e){}
/* ---- vídeo começou a carregar/tocar em qualquer quadro ---- */
function onMedia(ev){try{var v=ev.target;if(!v||!/^(VIDEO|AUDIO|SOURCE)$/.test(v.tagName))return;var s=v.currentSrc||v.src||'';if(/^blob:/i.test(s)){D.blob++;post({t:'media',blob:1});return}if(/^https?:/i.test(s)&&!D.media[s]){D.media[s]=1;post({t:'media',u:s})}}catch(e){}}
['loadstart','loadedmetadata','play','playing'].forEach(function(n){try{document.addEventListener(n,onMedia,true)}catch(e){}});
/* ---- players: pega a lista principal na hora em que o player carrega e permite trocar para a maior qualidade ---- */
D.hls=[];D.shk=[];
function hookHls(H){try{if(!H||!H.prototype||H.prototype.__bpt)return;H.prototype.__bpt=1;var ls=H.prototype.loadSource;if(ls)H.prototype.loadSource=function(u){try{if(D.hls.indexOf(this)<0)D.hls.push(this);var a=abs(u);if(/^https?:/i.test(a)&&!D.media[a]){D.media[a]=1;post({t:'media',u:a,via:'hls.js'})}}catch(e){}return ls.apply(this,arguments)};var am=H.prototype.attachMedia;if(am)H.prototype.attachMedia=function(){try{if(D.hls.indexOf(this)<0)D.hls.push(this)}catch(e){}return am.apply(this,arguments)}}catch(e){}}
function hookShaka(sk){try{var P=sk&&sk.Player;if(!P||!P.prototype||P.prototype.__bpt)return;P.prototype.__bpt=1;var ld=P.prototype.load;P.prototype.load=function(u){try{if(D.shk.indexOf(this)<0)D.shk.push(this);var a=typeof u==='string'?abs(u):'';if(/^https?:/i.test(a)&&!D.media[a]){D.media[a]=1;post({t:'media',u:a,via:'shaka'})}}catch(e){}return ld.apply(this,arguments)}}catch(e){}}
function trap(name,fn){try{var cur=window[name];if(cur){fn(cur);return}var val;Object.defineProperty(window,name,{configurable:true,enumerable:true,get:function(){return val},set:function(v){val=v;try{fn(v)}catch(e){}}})}catch(e){}}
trap('Hls',hookHls);trap('shaka',hookShaka);
function rehook(){try{if(window.Hls)hookHls(window.Hls);if(window.shaka)hookShaka(window.shaka)}catch(e){}}
try{document.addEventListener('DOMContentLoaded',rehook);window.addEventListener('load',rehook)}catch(e){}
D.maxq=function(){var n=0;rehook();
try{Object.keys(window).slice(0,4000).forEach(function(k){try{var o=window[k];if(o&&typeof o==='object'&&Array.isArray(o.levels)&&('currentLevel' in o)&&D.hls.indexOf(o)<0)D.hls.push(o);if(o&&typeof o==='object'&&o.hls&&Array.isArray(o.hls.levels)&&D.hls.indexOf(o.hls)<0)D.hls.push(o.hls)}catch(e){}})}catch(e){}
D.hls.forEach(function(h){try{var L=h.levels||[];if(!L.length)return;var bi=0,bh=-1;for(var i=0;i<L.length;i++){var hh=(L[i].height||0)*100000+(L[i].bitrate||0)/1000;if(hh>bh){bh=hh;bi=i}}try{h.autoLevelCapping=-1}catch(e){}try{h.capLevelToPlayerSize=false}catch(e){}h.currentLevel=bi;try{h.nextLevel=bi;h.loadLevel=bi}catch(e){}n++}catch(e){}});
D.shk.forEach(function(p){try{var tr=p.getVariantTracks();var b=null;tr.forEach(function(t){if(!b||(t.height||0)>(b.height||0)||((t.height||0)===(b.height||0)&&t.bandwidth>b.bandwidth))b=t});if(b){p.configure({abr:{enabled:false}});p.selectVariantTrack(b,true);n++}}catch(e){}});
try{if(window.jwplayer){var j=window.jwplayer();var ql=j.getQualityLevels&&j.getQualityLevels();if(ql&&ql.length){var bi2=0,bh2=-1;ql.forEach(function(l,i){var h=parseInt(l.label,10)||l.height||0;if(h>bh2){bh2=h;bi2=i}});j.setCurrentQuality(bi2);n++}}}catch(e){}
try{if(window.videojs&&window.videojs.getPlayers){var ps=window.videojs.getPlayers();for(var k in ps){try{var qs=ps[k]&&ps[k].qualityLevels&&ps[k].qualityLevels();if(qs&&qs.length){var mh=0,i3;for(i3=0;i3<qs.length;i3++)mh=Math.max(mh,qs[i3].height||0);for(i3=0;i3<qs.length;i3++)qs[i3].enabled=(qs[i3].height||0)===mh;n++}}catch(e){}}}}catch(e){}
try{q('.plyr').forEach(function(el){try{var p=el.plyr;var o=p&&p.options&&p.options.quality;if(o&&o.length){p.quality=o.slice().sort(function(a,b){return b-a})[0];n++}}catch(e){}})}catch(e){}
return n};
/* ---- varredura completa deste quadro ---- */
function str(o,cap){try{var seen=[];return JSON.stringify(o,function(k,v){if(typeof v==='function')return undefined;if(v&&typeof v==='object'){if(seen.indexOf(v)>=0)return undefined;if(seen.length>4000)return undefined;if(typeof Node!=='undefined'&&v instanceof Node)return undefined;if(v===window)return undefined;seen.push(v)}return v}).slice(0,cap||300000)}catch(e){return ''}}
function players(add){var P=[];try{if(window.jwplayer){P.push('jwplayer');try{var j=window.jwplayer();var pl=j&&j.getPlaylist&&j.getPlaylist();if(pl)add(str(pl,200000))}catch(e){}}}catch(e){}
try{if(window.videojs){P.push('videojs');var ps=window.videojs.getPlayers?window.videojs.getPlayers():{};for(var k in ps){try{var p=ps[k];if(!p)continue;add(String(p.currentSrc&&p.currentSrc()||''));add(str(p.currentSources&&p.currentSources(),50000));try{add(str(p.mediainfo,100000))}catch(e){}}catch(e){}}}}catch(e){}
['Plyr','Clappr','flowplayer','Hls','dashjs','shaka','bitmovin','THEOplayer','Vimeo','YT','Wistia','_wq','pandaPlayer','PandaPlayer','smartplayer','MediaElementPlayer','mejs','fluidPlayer','DPlayer','Artplayer','ArtPlayer','kWidget','bc','brightcove','muxjs'].forEach(function(n){try{if(window[n])P.push(n)}catch(e){}});
try{if(window.Wistia&&window.Wistia.api){var all=window.Wistia.api.all&&window.Wistia.api.all()||[];all.forEach(function(v){try{add(str(v.data&&v.data.media,200000))}catch(e){}})}}catch(e){}
try{if(window.flowplayer&&window.flowplayer.instances){window.flowplayer.instances.forEach(function(f){try{add(str(f.video,50000));add(str(f.conf,50000))}catch(e){}})}}catch(e){}
return P}
D.scan=function(g){rehook();var out={t:'scan',title:String(document.title||'').slice(0,300),ref:String(document.referrer||'').slice(0,1000),videos:[],iframes:[],res:[],txt:'',players:[],pw:0,a:[],blob:D.blob,mse:window.__bptMseActive?1:0,drm:D.drm,ready:document.readyState};
var parts=[],size=0;function add(s){try{if(!s)return;s=String(s);if(size>CAP)return;if(!TOK.test(s))return;parts.push(s);size+=s.length}catch(e){}}
q('video,audio').forEach(function(v){try{var o={s:v.currentSrc||v.src||'',p:v.poster||'',w:v.videoWidth||0,h:v.videoHeight||0,d:isFinite(v.duration)?Math.round(v.duration):0,vis:vis(v)?1:0,src:[]};if(/^blob:/i.test(o.s))o.blob=1;var ss=v.querySelectorAll('source');for(var i=0;i<ss.length;i++)o.src.push(abs(ss[i].getAttribute('src')||ss[i].src||''));for(var i2=0;i2<v.attributes.length;i2++){var at=v.attributes[i2];if(/^data-/i.test(at.name))add(at.name+'='+at.value)}out.videos.push(o)}catch(e){}});
q('iframe,frame,embed,object').forEach(function(f){try{if(out.iframes.length>=60)return;var r=f.getBoundingClientRect();var s=f.getAttribute('src')||f.getAttribute('data')||'';out.iframes.push({s:s?abs(s):'',ds:(function(x){return x?abs(x):''})(f.getAttribute('data-src')||f.getAttribute('data-lazy-src')||f.getAttribute('data-url')||''),w:Math.round(r.width),h:Math.round(r.height)})}catch(e){}});
try{var es=performance.getEntriesByType('resource')||[];for(var i=0;i<es.length&&out.res.length<400;i++){var n=es[i].name||'';if(/^https?:/i.test(n)&&(TOK.test(n)||/video|audio|media|xmlhttprequest|fetch/i.test(es[i].initiatorType||'')&&!/\.(css|js|woff2?|png|jpe?g|gif|svg|ico|webp)(\?|$)/i.test(n)))out.res.push(n)}}catch(e){}
try{document.querySelectorAll('script').forEach(function(s){try{if(s.src)add('src='+s.src);var t=s.textContent||'';if(t.length)add(t.slice(0,600000))}catch(e){}})}catch(e){}
try{document.querySelectorAll('meta[content],link[href]').forEach(function(m){var c=m.getAttribute('content')||m.getAttribute('href')||'';if(/^https?:|^\/\//i.test(c))add((m.getAttribute('property')||m.getAttribute('name')||m.getAttribute('rel')||'')+'='+c)})}catch(e){}
walk(document,function(el){var at=el.attributes;if(!at)return;for(var i=0;i<at.length;i++){var v=at[i].value;if(v&&v.length>6&&v.length<200000&&(TOK.test(v)||/^data-|^:|^v-bind/i.test(at[i].name)&&/https?:|\/\//.test(v)))add(at[i].name+'='+v)}},12000);
(g||[]).forEach(function(n){try{var o=window[n];if(o!==undefined&&o!==null)add(n+'='+(typeof o==='string'?o:str(o,400000)))}catch(e){}});
try{out.players=players(add)}catch(e){}
try{document.querySelectorAll('input[type=password]').forEach(function(p){if(vis(p))out.pw++})}catch(e){}
if(TOP){try{var seen={};document.querySelectorAll('a[href]').forEach(function(a){if(out.a.length>=400)return;var h=a.href||'';if(!/^https?:/i.test(h)||seen[h])return;seen[h]=1;var t=(a.innerText||a.textContent||a.getAttribute('title')||a.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,120);out.a.push({h:h,t:t})})}catch(e){}
try{out.body=String((document.body&&(document.body.innerText||document.body.textContent))||'').slice(0,3000)}catch(e){}}
out.txt=parts.join('\n').slice(0,CAP);return out};
/* ---- fotos deste quadro (maior versão de cada uma) ---- */
D.photos=function(){var out=[],seen={};function add(u,w,h,a){try{if(!u)return;u=abs(u);if(!/^https?:/i.test(u)||seen[u])return;seen[u]=1;if(out.length<800)out.push({u:u,w:w||0,h:h||0,a:String(a||'').replace(/\s+/g,' ').trim().slice(0,200)})}catch(e){}}
function best(ss){var b='',bw=0;String(ss||'').split(/,\s+(?=\S)/).forEach(function(p){var x=p.trim().split(/\s+/);var w=parseFloat(x[1])||1;if(x[0]&&w>=bw){bw=w;b=x[0]}});return b}
var LZ=['data-src','data-original','data-lazy-src','data-lazy','data-full','data-full-src','data-large','data-large-src','data-zoom','data-zoom-image','data-zoom-src','data-hi-res','data-hires','data-highres','data-big','data-orig','data-original-src','data-image','data-img','data-url','data-file','data-photo','data-retina','data-src-retina','data-fullsize','data-max','data-pswp-src','data-lightbox','data-fancybox','data-srcset','data-lazy-srcset'];
q('img').forEach(function(im){try{var alt=im.alt||im.title||'';var nw=im.naturalWidth||0,nh=im.naturalHeight||0;add(im.currentSrc||im.src,nw,nh,alt);var ss=im.getAttribute('srcset');if(ss)add(best(ss),0,0,alt);LZ.forEach(function(n){var v=im.getAttribute(n);if(v){if(/srcset/i.test(n))v=best(v);add(v,0,0,alt)}});var a=im.closest&&im.closest('a[href]');if(a&&/\.(jpe?g|png|webp|avif|gif)(\?|#|$)/i.test(a.href))add(a.href,0,0,alt)}catch(e){}});
q('picture source[srcset],source[data-srcset]').forEach(function(s){try{add(best(s.getAttribute('srcset')||s.getAttribute('data-srcset')),0,0,'')}catch(e){}});
q('a[href]').forEach(function(a){try{if(/\.(jpe?g|png|webp|avif|gif)(\?|#|$)/i.test(a.href))add(a.href,0,0,a.title||a.innerText||'')}catch(e){}});
var n=0;walk(document,function(el){if(n>1500)return;n++;try{var r=el.getBoundingClientRect();if(r.width<150||r.height<150)return;var bg=getComputedStyle(el).backgroundImage||'';var m=bg.match(/url\(["']?([^"')]+)["']?\)/);if(m&&!/^data:/i.test(m[1]))add(m[1],0,0,el.getAttribute('aria-label')||el.title||'');LZ.forEach(function(k){var v=el.getAttribute&&el.getAttribute(k);if(v&&el.tagName!=='IMG')add(/srcset/i.test(k)?best(v):v,0,0,'')})}catch(e){}},20000);
try{document.querySelectorAll('meta[property="og:image"],meta[name="twitter:image"],meta[property="og:image:url"]').forEach(function(m){add(m.content,0,0,document.title)})}catch(e){}
return {t:'photos',items:out}};
/* ---- ações ---- */
D.play=function(){var n=0;q('video').forEach(function(v){try{v.muted=true;v.playsInline=true;var p=v.play();if(p&&p.catch)p.catch(function(){});n++}catch(e){}});
var sels=['.vjs-big-play-button','.jw-display-icon-display','.jw-icon-display','.plyr__control--overlaid','.ytp-large-play-button','.mejs__overlay-play','.fp-play','.vp-preview','.vp-controls .play','[data-play-button]','button.play','[data-plyr=play]','.shaka-play-button','.bmpui-ui-hugeplaybacktogglebutton','.media-control-button[data-play]','.vds-play-button','media-play-button','.smartplayer-play','.panda-play','.w-big-play-button','.w-vulcan-v2-button','.click-for-sound-btn','.player-poster','.vjs-poster','.jw-preview','.video-poster','[aria-label="Play"]','[aria-label="Reproduzir"]','[aria-label*="Play" i]','[aria-label*="Reproduzir" i]','[aria-label*="Assistir" i]','[title="Play"]','[title="Reproduzir"]','button[class*="play-" i]','button[class*="-play" i]','button[class*="_play" i]','button[class*="playbutton" i]','[role=button][class*="play-" i]','[role=button][class*="-play" i]'];
sels.forEach(function(s){q(s).forEach(function(el){try{if(el.closest&&el.closest('a[href]'))return;var r=el.getBoundingClientRect();if(r.width<8||r.height<8)return;if(/pause|pausar/i.test((el.getAttribute('aria-label')||'')+' '+(el.className||'')))return;el.click();n++}catch(e){}})});
try{if(window.jwplayer){var j=window.jwplayer();if(j&&j.play){j.setMute&&j.setMute(true);j.play();n++}}}catch(e){}
try{if(window.videojs&&window.videojs.getPlayers){var ps=window.videojs.getPlayers();for(var k in ps){try{ps[k].muted(true);ps[k].play();n++}catch(e){}}}}catch(e){}
return n};
D.lazy=function(){var n=0;q('iframe,video,source,embed').forEach(function(el){try{var cur=el.getAttribute('src');var ds=el.getAttribute('data-src')||el.getAttribute('data-lazy-src')||el.getAttribute('data-url')||el.getAttribute('data-original');if((!cur||cur==='about:blank'||/^data:/i.test(cur))&&ds){el.setAttribute('src',ds);n++}if(el.loading==='lazy'){el.loading='eager';n++}}catch(e){}});
try{var t=q('video')[0]||q('iframe[src]')[0]||document.querySelector('[class*=player i]');if(t){t.scrollIntoView({block:'center'});n++}}catch(e){}return n};
D.unblock=function(){var n=0;var re=/^(×|✕|✖|x|fechar|close|agora não|não, obrigado|nao, obrigado|no thanks|pular|skip|dispensar|dismiss|continuar assistindo|continue watching|entendi|ok)$/i;
q('button,[role=button],a[class*=close i],span[class*=close i],div[class*=close i],[aria-label*=close i],[aria-label*=fechar i]').forEach(function(el){try{if(!vis(el))return;var t=((el.innerText||el.getAttribute('aria-label')||el.title||'')+'').replace(/\s+/g,' ').trim();var cls=String(el.className||'');var inModal=el.closest&&el.closest('[role=dialog],[aria-modal=true],.modal,.popup,.overlay,[class*=modal i],[class*=popup i],[class*=overlay i]');if(!inModal)return;if(re.test(t)||(!t&&/close|fechar/i.test(cls+' '+(el.getAttribute('aria-label')||'')))){el.click();n++}}catch(e){}});return n};
D.cmd=function(c){try{if(typeof c==='string')c=JSON.parse(c);var r=null;
if(c.c==='scan')r=D.scan(c.g||[]);else if(c.c==='photos')r=D.photos();else if(c.c==='play')r={t:'did',n:D.play()};else if(c.c==='lazy')r={t:'did',n:D.lazy()};else if(c.c==='unblock')r={t:'did',n:D.unblock()};else if(c.c==='maxq')r={t:'did',n:D.maxq()};
if(r){r.id=c.id||0;if(c.ret)return JSON.stringify(r);post(r)}}catch(e){if(c&&c.ret)return '{}'}return ''};
try{if(B){B.onmessage=function(ev){D.cmd(ev.data)}}}catch(e){}
post({t:'hello'});
try{document.addEventListener('DOMContentLoaded',function(){post({t:'hello'})})}catch(e){}
return 'ok';
})()"""
}
