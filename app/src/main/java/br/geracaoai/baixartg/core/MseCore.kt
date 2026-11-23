package br.geracaoai.baixartg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.coroutineContext

object MseHookScript {
    /**
     * Injetado antes dos scripts da página (addDocumentStartJavaScript + onPageStarted).
     * Intercepta MediaSource.addSourceBuffer, SourceBuffer.appendBuffer, fetch e XMLHttpRequest
     * capturando URLs, mimeType/codecs e TODOS os cabeçalhos de requisição (inclusive X-*, Authorization, token).
     * A captura pesada de bytes do appendBuffer só ativa quando window.__bptCaptureBytes = true
     * (porém o segmento init inicial <= 512 KB de cada SourceBuffer é guardado para não se perder).
     */
    const val SCRIPT = """(function(){
if(window.__bptMseHook)return 'ok';
window.__bptMseHook=1;
window.__bptNet=window.__bptNet||[];
window.__bptReqLog=window.__bptReqLog||[];
window.__bptMseTracks=window.__bptMseTracks||[];
window.__bptMseActive=window.__bptMseActive||0;
if(typeof window.__bptCaptureBytes==='undefined')window.__bptCaptureBytes=false;
var sbSeq=0;
function abs(u){try{return new URL(String(u),location.href).href}catch(e){return String(u||'')}}
function isStatic(u){return /\.(css|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf|eot|map|avif)(\?|$)/i.test(String(u||'').split('#')[0])}
function hdrObj(src){
  var out={};
  if(!src)return out;
  try{
    if(typeof Headers!=='undefined'&&src instanceof Headers){src.forEach(function(v,k){out[k]=v});return out;}
    if(Array.isArray(src)){src.forEach(function(p){if(p&&p.length>=2)out[String(p[0])]=String(p[1])});return out;}
    if(typeof src==='object'){Object.keys(src).forEach(function(k){var v=src[k];if(v!==undefined&&v!==null)out[k]=String(v)});}
  }catch(e){}
  return out;
}
function logReq(u,m,h,ct,cr,src){
  try{
    u=abs(u);
    if(!u||!/^https?:/i.test(u)||isStatic(u))return;
    var rec={u:u,m:m||'GET',h:h||{},ct:ct||'',cr:cr||'',src:src||''};
    if(window.__bptReqLog.length<250)window.__bptReqLog.push(rec);
    if(/\.(m3u8|mpd|mp4|m4v|webm)(\?|$)/i.test(u)||/mpegurl|dash\+xml/i.test(ct||'')){
      if(window.__bptNet.length<120)window.__bptNet.push(u);
    }
    if(window.__bptMse&&window.__bptMse.onReq){
      try{window.__bptMse.onReq(JSON.stringify(rec))}catch(e){}
    }
  }catch(e){}
}
function toU8(data){
  if(!data)return null;
  if(data instanceof ArrayBuffer)return new Uint8Array(data);
  if(ArrayBuffer.isView(data))return new Uint8Array(data.buffer,data.byteOffset,data.byteLength);
  return null;
}
function abToB64(u8,off,len){
  var sub=u8.subarray(off,off+len);
  var bin='';
  var step=0x8000;
  for(var i=0;i<sub.length;i+=step){bin+=String.fromCharCode.apply(null,sub.subarray(i,i+step))}
  return btoa(bin);
}
function sendU8(sb,u8){
  if(!u8||!u8.byteLength||!window.__bptMse||!window.__bptMse.onChunk)return false;
  var CHUNK=196608;
  var tid=sb.__bptId||'sb_0';
  var mime=sb.__bptMime||'video/mp4';
  for(var off=0;off<u8.byteLength;off+=CHUNK){
    var end=Math.min(u8.byteLength,off+CHUNK);
    var seq=++sb.__bptChunkSeq;
    var b64=abToB64(u8,off,end-off);
    window.__bptMse.onChunk(tid,mime,seq,b64,end>=u8.byteLength);
  }
  return true;
}
function looksInit(u8){
  if(!u8||u8.byteLength<12)return false;
  for(var i=4;i<=Math.min(20,u8.byteLength-4);i+=4){
    var t=String.fromCharCode(u8[i],u8[i+1],u8[i+2],u8[i+3]);
    if(t==='ftyp'||t==='moov')return true;
  }
  return false;
}
window.__bptFlushInit=function(){
  try{
    (window.__bptSbRefs||[]).forEach(function(sb){
      if(sb&&sb.__bptInitBuf&&!sb.__bptInitSent){
        if(sendU8(sb,sb.__bptInitBuf)){sb.__bptInitSent=true;sb.__bptInitBuf=null;}
      }
    });
  }catch(e){}
};
window.__bptSbRefs=window.__bptSbRefs||[];
try{
  var of=window.fetch;
  if(of){
    window.fetch=function(input,init){
      var u='',m='GET',h={};
      try{
        if(typeof Request!=='undefined'&&input instanceof Request){
          u=input.url; m=input.method||'GET';
          h=hdrObj(input.headers);
        }else{
          u=typeof input==='string'?input:(input&&input.url)||'';
        }
        if(init){
          if(init.method)m=init.method;
          var ih=hdrObj(init.headers);
          Object.keys(ih).forEach(function(k){h[k]=ih[k]});
        }
        logReq(u,m,h,'','','fetch');
      }catch(e){}
      var p=of.apply(this,arguments);
      try{
        if(p&&p.then){
          p.then(function(resp){
            try{
              if(resp&&resp.headers){
                logReq(resp.url||u,m,h,resp.headers.get('content-type')||'',resp.headers.get('content-range')||'','fetch-resp');
              }
            }catch(e){}
          },function(){});
        }
      }catch(e){}
      return p;
    };
  }
}catch(e){}
try{
  var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.setRequestHeader, xsend=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){
    try{this.__bptMethod=m||'GET';this.__bptUrl=abs(u);this.__bptHdr={};}catch(e){}
    return xo.apply(this,arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader=function(k,v){
    try{
      if(!this.__bptHdr)this.__bptHdr={};
      var key=String(k);
      this.__bptHdr[key]=this.__bptHdr[key]?(this.__bptHdr[key]+', '+String(v)):String(v);
    }catch(e){}
    return xs.apply(this,arguments);
  };
  XMLHttpRequest.prototype.send=function(){
    var self=this;
    try{
      if(self.__bptUrl)logReq(self.__bptUrl,self.__bptMethod||'GET',self.__bptHdr||{},'','','xhr');
      self.addEventListener('loadend',function(){
        try{
          var ct=self.getResponseHeader('content-type')||'';
          var cr=self.getResponseHeader('content-range')||'';
          logReq(self.responseURL||self.__bptUrl,self.__bptMethod||'GET',self.__bptHdr||{},ct,cr,'xhr-resp');
        }catch(e){}
      });
    }catch(e){}
    return xsend.apply(this,arguments);
  };
}catch(e){}
try{
  var MSE=window.MediaSource||window.WebKitMediaSource;
  if(MSE&&MSE.prototype&&MSE.prototype.addSourceBuffer){
    var asb=MSE.prototype.addSourceBuffer;
    MSE.prototype.addSourceBuffer=function(mime){
      var sb=asb.apply(this,arguments);
      try{
        window.__bptMseActive=1;
        sb.__bptId='sb_'+(++sbSeq);
        sb.__bptMime=String(mime||'');
        sb.__bptChunkSeq=0;
        sb.__bptInitSent=false;
        sb.__bptInitBuf=null;
        if(window.__bptSbRefs.length<16)window.__bptSbRefs.push(sb);
        if(window.__bptMseTracks.length<16)window.__bptMseTracks.push({id:sb.__bptId,mime:sb.__bptMime});
        if(window.__bptNet.length<120)window.__bptNet.push('mse:'+sb.__bptMime);
        if(window.__bptMse&&window.__bptMse.onTrack){try{window.__bptMse.onTrack(sb.__bptId,sb.__bptMime)}catch(e){}}
      }catch(e){}
      return sb;
    };
  }
  var SB=window.SourceBuffer;
  if(SB&&SB.prototype&&SB.prototype.appendBuffer){
    var apb=SB.prototype.appendBuffer;
    SB.prototype.appendBuffer=function(data){
      try{
        window.__bptMseActive=1;
        if(!this.__bptId){this.__bptId='sb_'+(++sbSeq);this.__bptMime=this.__bptMime||'video/mp4';this.__bptChunkSeq=0;}
        var u8=toU8(data);
        if(u8&&u8.byteLength>0){
          if(!this.__bptInitSent&&!this.__bptInitBuf&&(this.__bptChunkSeq===0||looksInit(u8))&&u8.byteLength<=524288){
            this.__bptInitBuf=u8.slice();
          }
          if(window.__bptCaptureBytes){
            if(this.__bptInitBuf&&!this.__bptInitSent){
              if(sendU8(this,this.__bptInitBuf)){
                this.__bptInitSent=true;
                var wasOnlyInit=(this.__bptInitBuf.byteLength===u8.byteLength&&looksInit(u8)&&this.__bptChunkSeq>0);
                this.__bptInitBuf=null;
                if(wasOnlyInit)return apb.apply(this,arguments);
              }
            }
            if(sendU8(this,u8))this.__bptInitSent=true;
          }
        }
      }catch(e){}
      return apb.apply(this,arguments);
    };
  }
}catch(e){}
return 'ok';
})()"""

    const val ADVANCE_PLAY_JS = """(function(){
try{
  var vs=Array.prototype.slice.call(document.querySelectorAll('video'));
  if(!vs.length)return JSON.stringify({has:0});
  vs.sort(function(a,b){
    var sa=(a.readyState||0)+(a.duration>0?10:0)+((a.src||a.currentSrc||'').indexOf('blob:')===0?5:0);
    var sb=(b.readyState||0)+(b.duration>0?10:0)+((b.src||b.currentSrc||'').indexOf('blob:')===0?5:0);
    return sb-sa;
  });
  var v=vs[0];
  try{v.muted=true;v.defaultMuted=true;v.playsInline=true;v.setAttribute('playsinline','');}catch(e){}
  if(v.paused){try{var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}}
  var rates=[8,4,2];
  for(var i=0;i<rates.length;i++){try{if(v.playbackRate!==rates[i])v.playbackRate=rates[i];break}catch(e){}}
  var bEnd=0;
  try{if(v.buffered&&v.buffered.length>0)bEnd=v.buffered.end(v.buffered.length-1);}catch(e){}
  var dur=(v.duration&&isFinite(v.duration))?v.duration:0;
  if(bEnd>0&&!v.seeking&&bEnd-v.currentTime>1.0){
    try{v.currentTime=Math.max(v.currentTime,bEnd-0.35);}catch(e){}
  }
  return JSON.stringify({has:1,paused:!!v.paused,ended:!!v.ended,ct:v.currentTime||0,dur:dur,bEnd:bEnd,rs:v.readyState||0,mse:window.__bptMseActive||0});
}catch(e){return JSON.stringify({has:0,err:1})}
})()"""
}

data class CapturedReq(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val contentType: String = "",
    val contentRange: String = "",
    val source: String = "",
    val at: Long = System.currentTimeMillis(),
)

enum class TrackKind { VIDEO, AUDIO, MUXED }

data class CapturedTrack(val id: String, val mime: String, val kind: TrackKind)

data class MseHint(
    val hasMse: Boolean = false,
    val hasJsChallenge: Boolean = false,
    val hasSpecialHeader: Boolean = false,
    val manifests: List<CapturedReq> = emptyList(),
    val rangesOrDirect: List<CapturedReq> = emptyList(),
    val segments: List<CapturedReq> = emptyList(),
    val tracks: List<CapturedTrack> = emptyList(),
)

object MseCapture {
    private val STANDARD_HEADERS = setOf(
        "user-agent", "accept", "accept-language", "accept-encoding",
        "referer", "origin", "range", "cache-control", "pragma",
        "connection", "host", "cookie", "if-modified-since", "if-none-match",
        "upgrade-insecure-requests", "dnt", "content-length", "content-type",
    )

    private class PageState {
        val reqs = ArrayList<CapturedReq>()
        val tracks = LinkedHashMap<String, CapturedTrack>()
        var mseSeen = false
        var jsChallenge = false
        var at = System.currentTimeMillis()
    }

    private val byPage = ConcurrentHashMap<String, PageState>()
    private const val MAX_PAGES = 80
    private const val MAX_REQS = 250

    private fun key(page: String) = PageKey.norm(page)

    fun sanitizeHeaders(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            val lk = k.trim().lowercase()
            if (lk.isEmpty() || v.isBlank()) continue
            if (lk in setOf("host", "content-length", "connection", "accept-encoding", "range", "cookie")) continue
            out[k.trim()] = v.trim()
        }
        return out
    }

    fun hasSpecialHeaders(headers: Map<String, String>): Boolean = headers.keys.any { k ->
        val lk = k.trim().lowercase()
        lk !in STANDARD_HEADERS && !lk.startsWith("sec-")
    }

    fun trackKindOf(mime: String): TrackKind {
        val m = mime.lowercase()
        val hasV = m.startsWith("video/") || m.contains("avc1") || m.contains("hev1") || m.contains("hvc1") || m.contains("vp9") || m.contains("vp8") || m.contains("av01")
        val hasA = m.startsWith("audio/") || m.contains("mp4a") || m.contains("opus") || m.contains("vorbis") || m.contains("ec-3") || m.contains("ac-3")
        return when {
            hasV && hasA -> TrackKind.MUXED
            m.startsWith("audio/") || (hasA && !hasV) -> TrackKind.AUDIO
            else -> TrackKind.VIDEO
        }
    }

    fun isDash(req: CapturedReq): Boolean =
        Extract.isDash(req.url) || req.url.contains(".mpd", true) || req.contentType.contains("dash+xml", true)

    fun isHls(req: CapturedReq): Boolean =
        Extract.isHls(req.url) || req.url.contains(".m3u8", true) || req.contentType.contains("mpegurl", true)

    fun isManifest(req: CapturedReq): Boolean = isDash(req) || isHls(req)

    fun isSegment(req: CapturedReq): Boolean = Extract.isSegmentUrl(req.url)

    fun isRangeOrVideo(req: CapturedReq): Boolean {
        if (MediaPick.isAdUrl(req.url)) return false
        val ct = req.contentType.lowercase()
        if (ct.startsWith("video/") || ct.startsWith("audio/")) return true
        if (req.contentRange.contains("bytes", true) && !ct.contains("text/html") && !ct.contains("image/")) return true
        if (!isSegment(req) && Extract.mediaKindOf(req.url) == Kind.DIRECT) return true
        return false
    }

    fun recordRequest(
        pageUrl: String,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        contentType: String = "",
        contentRange: String = "",
        source: String = "",
    ) {
        if (pageUrl.isBlank() || !url.startsWith("http", true)) return
        if (MediaPick.isAdUrl(url)) return
        val k = key(pageUrl)
        val st = byPage.getOrPut(k) { PageState() }
        val clean = sanitizeHeaders(headers)
        synchronized(st) {
            st.at = System.currentTimeMillis()
            val u0 = url.substringBefore('#')
            val idx = st.reqs.indexOfFirst { it.url.substringBefore('#') == u0 }
            if (idx >= 0) {
                val old = st.reqs[idx]
                val merged = LinkedHashMap<String, String>(old.headers.size + clean.size)
                merged.putAll(old.headers)
                for ((hk, hv) in clean) {
                    if (!merged.keys.any { it.equals(hk, ignoreCase = true) }) merged[hk] = hv
                    else if (source.startsWith("fetch") || source.startsWith("xhr")) {
                        val existing = merged.keys.firstOrNull { it.equals(hk, ignoreCase = true) }
                        if (existing != null && existing != hk) merged.remove(existing)
                        merged[hk] = hv
                    }
                }
                st.reqs[idx] = old.copy(
                    method = if (method.isNotBlank()) method else old.method,
                    headers = merged,
                    contentType = contentType.ifBlank { old.contentType },
                    contentRange = contentRange.ifBlank { old.contentRange },
                    at = System.currentTimeMillis(),
                )
            } else {
                if (st.reqs.size >= MAX_REQS) st.reqs.removeAt(0)
                st.reqs += CapturedReq(url, method.ifBlank { "GET" }, clean, contentType, contentRange, source)
            }
        }
        trimOld()
    }

    fun recordMse(pageUrl: String, id: String, mime: String) {
        if (pageUrl.isBlank()) return
        val st = byPage.getOrPut(key(pageUrl)) { PageState() }
        synchronized(st) {
            st.mseSeen = true
            st.at = System.currentTimeMillis()
            val tid = id.ifBlank { "sb_${st.tracks.size + 1}" }
            st.tracks[tid] = CapturedTrack(tid, mime, trackKindOf(mime))
        }
        trimOld()
    }

    fun recordChallenge(pageUrl: String) {
        if (pageUrl.isBlank()) return
        val st = byPage.getOrPut(key(pageUrl)) { PageState() }
        synchronized(st) { st.jsChallenge = true; st.at = System.currentTimeMillis() }
    }

    fun ingestJsReqLog(pageUrl: String, jsonArrayStr: String?) {
        if (jsonArrayStr.isNullOrBlank() || jsonArrayStr == "null") return
        try {
            var v: Any? = org.json.JSONTokener(jsonArrayStr.trim()).nextValue()
            if (v is String) v = org.json.JSONTokener(v).nextValue()
            val arr = v as? org.json.JSONArray ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                ingestJsReqObj(pageUrl, o)
            }
        } catch (_: Exception) {}
    }

    fun ingestJsReqJson(pageUrl: String, jsonStr: String?) {
        if (jsonStr.isNullOrBlank()) return
        try { ingestJsReqObj(pageUrl, org.json.JSONObject(jsonStr)) } catch (_: Exception) {}
    }

    private fun ingestJsReqObj(pageUrl: String, o: org.json.JSONObject) {
        val u = o.optString("u")
        if (!u.startsWith("http", true)) return
        val m = o.optString("m", "GET")
        val ct = o.optString("ct")
        val cr = o.optString("cr")
        val src = o.optString("src", "js")
        val h = LinkedHashMap<String, String>()
        o.optJSONObject("h")?.let { ho ->
            val ks = ho.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                val v = ho.optString(k)
                if (v.isNotBlank()) h[k] = v
            }
        }
        recordRequest(pageUrl, u, m, h, ct, cr, src)
    }

    fun snapshot(pageUrl: String): MseHint {
        if (pageUrl.isBlank()) return MseHint()
        val st = byPage[key(pageUrl)] ?: return MseHint()
        synchronized(st) {
            val manifests = st.reqs.filter { isManifest(it) }
            val segments = st.reqs.filter { isSegment(it) }
            val ranges = st.reqs.filter { !isManifest(it) && !isSegment(it) && isRangeOrVideo(it) }
            val special = st.reqs.any { (isManifest(it) || isSegment(it) || isRangeOrVideo(it) || st.mseSeen) && hasSpecialHeaders(it.headers) }
            return MseHint(
                hasMse = st.mseSeen,
                hasJsChallenge = st.jsChallenge,
                hasSpecialHeader = special,
                manifests = manifests,
                rangesOrDirect = ranges,
                segments = segments,
                tracks = st.tracks.values.toList(),
            )
        }
    }

    fun shouldUseBrowserFirst(f: Found): Boolean {
        if (f.kind == Kind.MSE) return true
        if (Extract.isDash(f.url)) return true
        val h1 = snapshot(f.page)
        if (h1.hasMse || h1.hasJsChallenge || h1.hasSpecialHeader) return true
        if (f.url != f.page && f.url.startsWith("http")) {
            val h2 = snapshot(f.url)
            if (h2.hasMse || h2.hasJsChallenge || h2.hasSpecialHeader) return true
        }
        return false
    }

    fun clearPage(pageUrl: String) { byPage.remove(key(pageUrl)) }
    fun clearForTest() { byPage.clear() }

    private fun trimOld() {
        if (byPage.size <= MAX_PAGES) return
        val oldest = byPage.entries.minByOrNull { e -> synchronized(e.value) { e.value.at } }
        oldest?.let { byPage.remove(it.key) }
    }
}

data class DashSeg(val url: String, val byteRange: String? = null)

data class DashPlan(
    val videoInit: DashSeg? = null,
    val videoSegments: List<DashSeg> = emptyList(),
    val audioInit: DashSeg? = null,
    val audioSegments: List<DashSeg> = emptyList(),
)

object DashParser {
    private data class Rep(
        val id: String,
        val mime: String,
        val codecs: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val baseUrl: String,
        val init: DashSeg?,
        val segments: List<DashSeg>,
        val isVideo: Boolean,
        val isAudio: Boolean,
    )

    fun parseIsoDuration(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        val m = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?""", RegexOption.IGNORE_CASE).matchEntire(s.trim()) ?: return 0.0
        val d = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val h = m.groupValues[2].toDoubleOrNull() ?: 0.0
        val min = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[4].toDoubleOrNull() ?: 0.0
        return d * 86400 + h * 3600 + min * 60 + sec
    }

    private fun directChildren(el: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val cn = el.childNodes
        for (i in 0 until cn.length) {
            val n = cn.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName.substringAfter(':') == tag) out += n as Element
        }
        return out
    }

    private fun resolveUrl(base: String, rel: String): String {
        val r = rel.trim()
        if (r.isBlank()) return base
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        return Extract.abs(base, r) ?: r
    }

    private fun mergeBase(parentBase: String, el: Element): String {
        val bu = directChildren(el, "BaseURL").firstOrNull()?.textContent?.trim()
        return if (!bu.isNullOrBlank()) resolveUrl(parentBase, bu) else parentBase
    }

    private fun substTemplate(tmpl: String, repId: String, bw: Long, number: Long, time: Long): String {
        var s = tmpl.replace("\$RepresentationID\$", repId).replace("\$Bandwidth\$", bw.toString())
        s = Regex("\\\$Number(?:%0(\\d+)d)?\\\$").replace(s) { m ->
            val w = m.groupValues[1].toIntOrNull()
            if (w != null && w > 0) number.toString().padStart(w, '0') else number.toString()
        }
        s = Regex("\\\$Time(?:%0(\\d+)d)?\\\$").replace(s) { m ->
            val w = m.groupValues[1].toIntOrNull()
            if (w != null && w > 0) time.toString().padStart(w, '0') else time.toString()
        }
        return s
    }

    fun parse(mpdUrl: String, xml: String, maxHeight: Int = 0): DashPlan {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
        }
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val mpd = doc.documentElement
        val mpdDurSec = parseIsoDuration(mpd.getAttribute("mediaPresentationDuration"))
        val mpdBase = mergeBase(mpdUrl, mpd)
        val reps = ArrayList<Rep>()

        val periods = directChildren(mpd, "Period")
        val firstPeriod = periods.firstOrNull() ?: return DashPlan()
        val periodDurSec = parseIsoDuration(firstPeriod.getAttribute("duration")).takeIf { it > 0 } ?: mpdDurSec
        val pBase = mergeBase(mpdBase, firstPeriod)

        for (aset in directChildren(firstPeriod, "AdaptationSet")) {
            val aBase = mergeBase(pBase, aset)
            val aMime = aset.getAttribute("mimeType")
            val aCodecs = aset.getAttribute("codecs")
            val aCt = aset.getAttribute("contentType")
            val aTmpl = directChildren(aset, "SegmentTemplate").firstOrNull()
            val aList = directChildren(aset, "SegmentList").firstOrNull()

            for (rep in directChildren(aset, "Representation")) {
                val rBase = mergeBase(aBase, rep)
                val id = rep.getAttribute("id").ifBlank { "1" }
                val mime = rep.getAttribute("mimeType").ifBlank { aMime }
                val codecs = rep.getAttribute("codecs").ifBlank { aCodecs }
                val bw = rep.getAttribute("bandwidth").toLongOrNull() ?: 0L
                val w = rep.getAttribute("width").toIntOrNull() ?: aset.getAttribute("width").toIntOrNull() ?: 0
                val h = rep.getAttribute("height").toIntOrNull() ?: aset.getAttribute("height").toIntOrNull() ?: 0

                val isVideo = mime.startsWith("video/", true) || aCt.equals("video", true) || (w > 0 && h > 0)
                val isAudio = !isVideo && (mime.startsWith("audio/", true) || aCt.equals("audio", true) || codecs.startsWith("mp4a", true) || codecs.contains("opus", true))
                if (!isVideo && !isAudio) continue

                val tmpl = directChildren(rep, "SegmentTemplate").firstOrNull() ?: aTmpl
                val slist = directChildren(rep, "SegmentList").firstOrNull() ?: aList

                var initSeg: DashSeg? = null
                val segs = ArrayList<DashSeg>()

                if (tmpl != null) {
                    val initAttr = tmpl.getAttribute("initialization")
                    if (initAttr.isNotBlank()) {
                        initSeg = DashSeg(resolveUrl(rBase, substTemplate(initAttr, id, bw, 0, 0)))
                    } else {
                        directChildren(tmpl, "Initialization").firstOrNull()?.let { ini ->
                            val su = ini.getAttribute("sourceURL").ifBlank { rBase }
                            val rng = ini.getAttribute("range").ifBlank { null }
                            initSeg = DashSeg(resolveUrl(rBase, substTemplate(su, id, bw, 0, 0)), rng)
                        }
                    }
                    val mediaAttr = tmpl.getAttribute("media")
                    val timescale = tmpl.getAttribute("timescale").toLongOrNull()?.takeIf { it > 0 } ?: 1L
                    val startNum = tmpl.getAttribute("startNumber").toLongOrNull() ?: 1L
                    val timeline = directChildren(tmpl, "SegmentTimeline").firstOrNull()
                    if (timeline != null && mediaAttr.isNotBlank()) {
                        var t = 0L
                        var num = startNum
                        for (sEl in directChildren(timeline, "S")) {
                            val st = sEl.getAttribute("t").toLongOrNull()
                            if (st != null) t = st
                            val d = sEl.getAttribute("d").toLongOrNull() ?: 0L
                            var r = sEl.getAttribute("r").toLongOrNull() ?: 0L
                            if (r < 0 && d > 0 && periodDurSec > 0) {
                                val endTicks = (periodDurSec * timescale).toLong()
                                r = ((endTicks - t) / d - 1).coerceIn(0, 10_000)
                            } else if (r < 0) {
                                r = 0
                            }
                            for (k in 0..r) {
                                segs += DashSeg(resolveUrl(rBase, substTemplate(mediaAttr, id, bw, num, t)))
                                t += d; num++
                                if (segs.size >= 20_000) break
                            }
                            if (segs.size >= 20_000) break
                        }
                    } else if (mediaAttr.isNotBlank()) {
                        val durTicks = tmpl.getAttribute("duration").toLongOrNull() ?: 0L
                        val count = if (durTicks > 0 && periodDurSec > 0) {
                            kotlin.math.ceil(periodDurSec * timescale / durTicks.toDouble()).toInt().coerceIn(1, 20_000)
                        } else 1
                        var num = startNum
                        var t = 0L
                        repeat(count) {
                            segs += DashSeg(resolveUrl(rBase, substTemplate(mediaAttr, id, bw, num, t)))
                            num++; t += durTicks
                        }
                    }
                } else if (slist != null) {
                    directChildren(slist, "Initialization").firstOrNull()?.let { ini ->
                        val su = ini.getAttribute("sourceURL").ifBlank { rBase }
                        val rng = ini.getAttribute("range").ifBlank { null }
                        initSeg = DashSeg(resolveUrl(rBase, su), rng)
                    }
                    for (su in directChildren(slist, "SegmentURL")) {
                        val mu = su.getAttribute("media").ifBlank { rBase }
                        val rng = su.getAttribute("mediaRange").ifBlank { null }
                        segs += DashSeg(resolveUrl(rBase, mu), rng)
                    }
                } else {
                    val sbase = directChildren(rep, "SegmentBase").firstOrNull()
                    if (sbase != null) {
                        val ini = directChildren(sbase, "Initialization").firstOrNull()
                        val initRange = ini?.getAttribute("range")?.ifBlank { null }
                        if (initRange != null) {
                            val endInit = initRange.substringAfter('-').toLongOrNull()
                            initSeg = DashSeg(rBase, initRange)
                            val mediaRange = if (endInit != null) "${endInit + 1}-" else null
                            segs += DashSeg(rBase, mediaRange)
                        } else {
                            segs += DashSeg(rBase)
                        }
                    } else {
                        segs += DashSeg(rBase)
                    }
                }

                if (segs.isNotEmpty() || initSeg != null) {
                    reps += Rep(id, mime, codecs, bw, w, h, rBase, initSeg, segs, isVideo, isAudio)
                }
            }
        }

        val vReps = reps.filter { it.isVideo }
        val filteredV = if (maxHeight > 0) vReps.filter { it.height in 1..maxHeight }.ifEmpty { vReps } else vReps
        val bestV = filteredV.maxWithOrNull(compareBy<Rep>({ it.width.toLong() * it.height.toLong() }, { it.bandwidth }))

        val aReps = reps.filter { it.isAudio }
        val aac = aReps.filter { it.codecs.contains("mp4a", true) || it.mime.contains("mp4", true) }
        val bestA = (aac.ifEmpty { aReps }).maxByOrNull { it.bandwidth }

        return DashPlan(
            videoInit = bestV?.init,
            videoSegments = bestV?.segments.orEmpty(),
            audioInit = bestA?.init,
            audioSegments = bestA?.segments.orEmpty(),
        )
    }
}

object Mp4Boxes {
    data class BoxInfo(val type: String, val start: Long, val size: Long)

    fun parseBoxes(bytes: ByteArray, offset: Int = 0, limit: Int = bytes.size): List<BoxInfo> {
        val out = ArrayList<BoxInfo>()
        var pos = offset
        while (pos + 8 <= limit) {
            var size = ByteBuffer.wrap(bytes, pos, 4).int.toLong() and 0xffffffffL
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L && pos + 16 <= limit) {
                size = ByteBuffer.wrap(bytes, pos + 8, 8).long
            } else if (size == 0L) {
                size = (limit - pos).toLong()
            }
            if (size < 8 || pos + size > limit) break
            out += BoxInfo(type, pos.toLong(), size)
            pos += size.toInt()
        }
        return out
    }

    fun isValidMp4(file: File): Boolean {
        if (!file.exists() || file.length() < 24) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                var pos = 0L
                val h = ByteArray(16)
                var hasFtyp = false; var hasMeta = false; var hasMdat = false; var mdatBytes = 0L
                while (pos + 8 <= len) {
                    raf.seek(pos); raf.readFully(h, 0, 8)
                    var size = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
                    val type = String(h, 4, 4, Charsets.ISO_8859_1)
                    if (size == 1L) { if (pos + 16 > len) break; raf.readFully(h, 8, 8); size = ByteBuffer.wrap(h, 8, 8).long }
                    else if (size == 0L) size = len - pos
                    if (size < 8 || pos + size > len) return false
                    when (type) {
                        "ftyp" -> hasFtyp = true
                        "moov", "moof" -> hasMeta = true
                        "mdat" -> { hasMdat = true; mdatBytes += (size - 8) }
                    }
                    pos += size
                }
                pos == len && hasFtyp && hasMeta && hasMdat && mdatBytes > 0
            }
        } catch (_: Exception) { false }
    }

    /** Grava segmento fMP4 evitando duplicar ftyp/moov se o init já foi gravado. Retorna se já tem init. */
    fun appendFmp4Clean(alreadyHasInit: Boolean, chunk: ByteArray, out: OutputStream): Boolean {
        if (chunk.isEmpty()) return alreadyHasInit
        val boxes = parseBoxes(chunk)
        if (boxes.isEmpty()) {
            out.write(chunk)
            return alreadyHasInit
        }
        var hasInit = alreadyHasInit
        for (b in boxes) {
            if ((b.type == "ftyp" || b.type == "moov") && alreadyHasInit) continue
            if (b.type == "ftyp" || b.type == "moov") hasInit = true
            out.write(chunk, b.start.toInt(), b.size.toInt())
        }
        return hasInit
    }

    fun detectTrackKind(bytes: ByteArray): TrackKind? {
        for (i in 0..(bytes.size - 16).coerceAtLeast(0)) {
            if (bytes[i] == 'h'.code.toByte() && bytes[i + 1] == 'd'.code.toByte() &&
                bytes[i + 2] == 'l'.code.toByte() && bytes[i + 3] == 'r'.code.toByte() && i + 16 <= bytes.size
            ) {
                val sub = String(bytes, i + 12, 4, Charsets.ISO_8859_1)
                if (sub == "vide") return TrackKind.VIDEO
                if (sub == "soun") return TrackKind.AUDIO
            }
        }
        return null
    }
}

object MseDownloader {
    fun buildRequest(
        url: String,
        headers: Map<String, String>,
        referer: String?,
        range: String? = null,
    ): Request {
        val b = Request.Builder().url(url)
        val clean = MseCapture.sanitizeHeaders(headers)
        for ((k, v) in clean) b.header(k, v)
        if (!clean.keys.any { it.equals("Accept", true) }) b.header("Accept", "*/*")
        if (!clean.keys.any { it.equals("Referer", true) }) {
            val ref = if (!referer.isNullOrBlank() && referer != url) referer else Net.originOf(url)
            if (!ref.isNullOrBlank()) {
                b.header("Referer", ref)
                if (!clean.keys.any { it.equals("Origin", true) }) {
                    Net.originOf(ref)?.trimEnd('/')?.let { b.header("Origin", it) }
                }
            }
        }
        if (!range.isNullOrBlank()) b.header("Range", if (range.startsWith("bytes=", true)) range else "bytes=$range")
        return b.build()
    }

    private suspend fun <T> retry(n: Int = 3, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(n) {
            try { return block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e; delay(800L * (it + 1))
            }
        }
        throw last!!
    }

    private suspend fun fetchBytes(url: String, headers: Map<String, String>, referer: String?, range: String? = null): ByteArray =
        Net.http.newCall(buildRequest(url, headers, referer, range)).await().use { r ->
            if (!r.isSuccessful) throw DlException("HTTP ${r.code} ao baixar segmento")
            r.body?.bytes() ?: ByteArray(0)
        }

    private suspend fun fetchText(url: String, headers: Map<String, String>, referer: String?): String =
        Net.http.newCall(buildRequest(url, headers, referer)).await().use { r ->
            if (!r.isSuccessful) throw DlException("HTTP ${r.code} no manifest")
            r.body?.string().orEmpty()
        }

    suspend fun downloadDash(
        mpdReq: CapturedReq,
        referer: String,
        dir: File,
        title: String,
        maxHeight: Int = 0,
        parallel: Int = 6,
        progress: (Long, Long) -> Unit,
        muxFn: (File, File?, File) -> File = { v, a, o -> Remux.muxTracks(v, a, o) },
    ): File = coroutineScope {
        val xml = retry { fetchText(mpdReq.url, mpdReq.headers, referer) }
        val plan = DashParser.parse(mpdReq.url, xml, maxHeight)
        if (plan.videoSegments.isEmpty() && plan.videoInit == null && plan.audioSegments.isEmpty()) {
            throw DlException("Manifest DASH sem faixas de vídeo")
        }
        val base = Downloader.safe(title) + "_" + System.nanoTime()
        val vFile = File(dir, "$base.dash.v.mp4")
        val aFile = if (plan.audioSegments.isNotEmpty() || plan.audioInit != null) File(dir, "$base.dash.a.mp4") else null
        val outFile = File(dir, "$base.mp4")

        val totalCount = (if (plan.videoInit != null) 1 else 0) + plan.videoSegments.size +
            (if (plan.audioInit != null) 1 else 0) + plan.audioSegments.size
        var doneCount = 0
        var totalBytes = 0L

        suspend fun fetchTrack(init: DashSeg?, segs: List<DashSeg>, dst: File) {
            val all = ArrayList<DashSeg>(segs.size + 1)
            if (init != null) all += init
            all += segs
            dst.outputStream().buffered(1 shl 20).use { os ->
                var hasInit = false
                val window = ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
                val it = all.iterator()
                fun launchNext() {
                    if (it.hasNext()) {
                        val s = it.next()
                        window += async(Dispatchers.IO) { retry { fetchBytes(s.url, mpdReq.headers, referer, s.byteRange) } }
                    }
                }
                repeat(parallel.coerceIn(1, 8)) { launchNext() }
                while (window.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val data = window.removeFirst().await()
                    launchNext()
                    hasInit = Mp4Boxes.appendFmp4Clean(hasInit, data, os)
                    doneCount++
                    totalBytes += data.size
                    val est = if (doneCount > 0) totalBytes / doneCount * totalCount.coerceAtLeast(doneCount) else -1L
                    progress(totalBytes, est)
                }
            }
            if (dst.length() < 64) throw DlException("Faixa DASH vazia")
        }

        try {
            val mainSrc = if (plan.videoSegments.isNotEmpty() || plan.videoInit != null) {
                fetchTrack(plan.videoInit, plan.videoSegments, vFile)
                if (aFile != null) fetchTrack(plan.audioInit, plan.audioSegments, aFile)
                muxFn(vFile, aFile, outFile)
            } else {
                fetchTrack(plan.audioInit, plan.audioSegments, vFile)
                muxFn(vFile, null, outFile)
            }
            mainSrc
        } catch (e: Throwable) {
            vFile.delete(); aFile?.delete(); outFile.delete()
            throw e
        }
    }

    /**
     * Baixa um stream por faixas (Range: bytes=start-...) em sequência usando os mesmos cabeçalhos
     * até completar o tamanho informado por Content-Range (ou até 416 / EOF).
     */
    suspend fun downloadRanges(
        req: CapturedReq,
        referer: String,
        dir: File,
        title: String,
        chunkSize: Long = 1024L * 1024L,
        progress: (Long, Long) -> Unit,
    ): File {
        val out = File(dir, Downloader.safe(title) + "_" + System.nanoTime() + ".mp4")
        var offset = 0L
        var total = -1L
        var openEnded = true
        try {
            out.outputStream().buffered(1 shl 20).use { os ->
                while (total < 0 || offset < total) {
                    coroutineContext.ensureActive()
                    val rangeStr = if (openEnded && offset == 0L) "bytes=0-" else {
                        val end = if (total > 0) minOf(offset + chunkSize - 1, total - 1) else offset + chunkSize - 1
                        "bytes=$offset-$end"
                    }
                    val stepRead = retry {
                        Net.http.newCall(buildRequest(req.url, req.headers, referer, rangeStr)).await().use { r ->
                            if (r.code == 416) return@use -1L
                            if (!r.isSuccessful) {
                                // alguns servidores recusam bytes=0- sem fim; tenta faixa fechada na próxima tentativa
                                if (openEnded && offset == 0L && r.code in listOf(400, 413, 416)) {
                                    openEnded = false
                                    return@use -2L
                                }
                                throw DlException("HTTP ${r.code} no download por faixa")
                            }
                            val ct = r.header("Content-Type") ?: ""
                            if (offset == 0L && (ct.contains("text/html", true) || ct.startsWith("image/", true))) {
                                throw DlException("Servidor devolveu $ct em vez de vídeo")
                            }
                            val cr = r.header("Content-Range")
                            val parsed = parseContentRange(cr)
                            if (parsed != null && parsed.total > 0) total = parsed.total
                            val body = r.body ?: return@use 0L
                            if (r.code == 200 && total < 0) {
                                val cl = body.contentLength()
                                if (cl > 0) total = cl
                            }
                            val buf = ByteArray(256 * 1024)
                            var gotHere = 0L
                            var lastUi = 0L
                            body.byteStream().use { ins ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n)
                                    gotHere += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 300) {
                                        lastUi = now
                                        progress(offset + gotHere, total)
                                    }
                                }
                            }
                            if (r.code == 200 && gotHere > 0) {
                                // resposta inteira sem Range
                                total = offset + gotHere
                            }
                            gotHere
                        }
                    }
                    if (stepRead == -2L) continue
                    if (stepRead <= 0L) break
                    offset += stepRead
                    progress(offset, total)
                    if (total < 0 && openEnded) break
                }
            }
            if (out.length() < 64) { out.delete(); throw DlException("Arquivo de faixa vazio") }
            val k = Downloader.sniffFile(out)
            if (k == "html" || k == "image") { out.delete(); throw DlException("Resposta inválida ($k)") }
            return out
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    data class ContentRangeInfo(val start: Long, val end: Long, val total: Long)

    fun parseContentRange(cr: String?): ContentRangeInfo? {
        if (cr.isNullOrBlank()) return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).find(cr) ?: return null
        val s = m.groupValues[1].toLongOrNull() ?: return null
        val e = m.groupValues[2].toLongOrNull() ?: return null
        val t = m.groupValues[3].toLongOrNull() ?: -1L
        return ContentRangeInfo(s, e, t)
    }

    suspend fun downloadSegmentList(
        segs: List<CapturedReq>,
        referer: String,
        dir: File,
        title: String,
        progress: (Long, Long) -> Unit,
        muxFn: (File, File?, File) -> File = { v, a, o -> Remux.muxTracks(v, a, o) },
    ): File {
        if (segs.isEmpty()) throw DlException("Nenhum segmento capturado")
        val base = Downloader.safe(title) + "_" + System.nanoTime()
        val raw = File(dir, "$base.segs.mp4")
        val out = File(dir, "$base.mp4")
        try {
            var hasInit = false
            var got = 0L
            raw.outputStream().buffered(1 shl 20).use { os ->
                for ((i, s) in segs.withIndex()) {
                    coroutineContext.ensureActive()
                    val b = retry { fetchBytes(s.url, s.headers, referer) }
                    hasInit = Mp4Boxes.appendFmp4Clean(hasInit, b, os)
                    got += b.size
                    val est = if (i + 1 > 0) got / (i + 1) * segs.size else -1L
                    progress(got, est)
                }
            }
            if (raw.length() < 64) throw DlException("Segmentos vazios")
            return muxFn(raw, null, out)
        } catch (e: Throwable) {
            raw.delete(); out.delete()
            throw e
        }
    }
}
