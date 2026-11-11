package br.geracaoai.baixartg.web

import android.webkit.JavascriptInterface
import br.geracaoai.baixartg.core.Extract
import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

/**
 * Detecta vídeos na página aberta no Navegador (tag &lt;video&gt;, pedidos de mídia,
 * evento play e a própria URL quando o yt-dlp reconhece o site).
 * Não tenta furar DRM nem login; hosts de vazamento e pirataria são ignorados.
 */
object PageVideos {
    data class Hit(
        val url: String,
        val page: String,
        val title: String,
        val kind: Kind,
        val played: Boolean = false,
    )

    private val MEDIA_EXT = Regex("""\.(mp4|webm|m3u8|mpd|mov|m4v|mkv)(?:$|[?#/])""", RegexOption.IGNORE_CASE)
    private val SEGMENT = Regex("""\.(ts|m4s|cmfa|cmfv)(?:$|[?#])""", RegexOption.IGNORE_CASE)
    private val SKIP_HOST = Regex(
        """(?:^|\.)(?:googlevideo\.com|ytimg\.com|ggpht\.com|akamaihd\.net|fbcdn\.net)$""",
        RegexOption.IGNORE_CASE,
    )
    /** Agregadores de vazamento e sites de pirataria (bloqueados de propósito). */
    private val BLOCK_HOSTS = listOf(
        "leakgallery", "leakedzone", "leakedmodels", "leaked.xxx", "leaknudes",
        "thothub", "coomer.su", "coomer.party", "kemono.su", "kemono.party",
        "simpcity", "nudostar", "fapello", "thenude", "celebrityleaks",
        "socialmediagirls", "bunkr-leak", "hotleak", "leak.xxx",
        "thepiratebay", "1337x", "1377x", "rarbg", "yts.mx", "yts.lt", "yify",
        "fitgirl", "pirate-bay", "limetorrents", "torrentz", "nyaa.si",
    )

    private val _hits = MutableStateFlow<List<Hit>>(emptyList())
    private val lock = Any()
    val hits: StateFlow<List<Hit>> = _hits
    @Volatile var currentPage: String = ""
        private set
    @Volatile var currentTitle: String = ""
        private set

    fun resetIfNewPage(url: String, title: String = "") {
        val key = canonPage(url)
        if (key.isBlank()) return
        if (key != canonPage(currentPage)) {
            currentPage = url
            currentTitle = title
            synchronized(lock) { _hits.value = emptyList() }
            considerPage(url, title)
        } else {
            currentPage = url
            if (title.isNotBlank()) currentTitle = title
        }
    }

    fun clear() {
        currentPage = ""
        currentTitle = ""
        synchronized(lock) { _hits.value = emptyList() }
    }

    fun blocked(url: String): Boolean {
        val h = host(url)
        if (h.isBlank()) return false
        return BLOCK_HOSTS.any { token -> h == token || h.endsWith(".$token") || h.startsWith("$token.") || (token.contains(".") && h.contains(token)) }
    }

    fun isMediaRequest(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http", true)) return false
        if (blocked(url)) return false
        if (SEGMENT.containsMatchIn(url)) return false
        val h = host(url)
        if (h.isNotBlank() && SKIP_HOST.containsMatchIn(h)) return false
        return MEDIA_EXT.containsMatchIn(url.substringBefore('#'))
    }

    fun isYtdlpPage(url: String): Boolean = url.startsWith("http", true) && Extract.YTDLP_HOSTS.containsMatchIn(url)

    fun isDash(url: String) = Regex("""\.mpd(?:$|[?#/])""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun kindOf(url: String): Kind = when {
        isYtdlpPage(url) || isDash(url) -> Kind.SERVER
        Extract.isHls(url) -> Kind.HLS
        else -> Kind.DIRECT
    }

    fun considerPage(url: String, title: String = "") {
        if (!url.startsWith("http", true) || blocked(url)) return
        if (isYtdlpPage(url)) offer(url, url, title.ifBlank { currentTitle }, played = false)
    }

    fun offerMedia(url: String, page: String = currentPage, title: String = currentTitle, played: Boolean = false) {
        if (isMediaRequest(url)) offer(url, page, title, played)
    }

    fun offerFromJs(raw: String, title: String, played: Boolean) {
        val page = currentPage
        val u = raw.trim()
        if (u.startsWith("blob:") || u.startsWith("mediasource:") || u.isBlank()) {
            if (page.startsWith("http", true) && (played || isYtdlpPage(page))) offer(page, page, title, played)
            return
        }
        if (!u.startsWith("http", true)) return
        if (blocked(u) || blocked(page)) return
        when {
            isMediaRequest(u) -> offer(u, page, title, played)
            isYtdlpPage(u) -> offer(u, page.ifBlank { u }, title, played)
            played && isYtdlpPage(page) -> offer(page, page, title, true)
        }
    }

    fun toFound(hit: Hit): Found {
        val title = hit.title.ifBlank { hit.page.ifBlank { hit.url } }
        return Found(
            url = hit.url, page = hit.page.ifBlank { hit.url }, title = title,
            kind = hit.kind, ref = hit.page.ifBlank { null }, extra = title,
            bestQuality = true,
        )
    }

    fun hookScript(): String = HOOK

    class Bridge {
        @JavascriptInterface
        fun onVideo(url: String?, title: String?, played: Int) {
            try { offerFromJs(url ?: "", title ?: "", played != 0) } catch (_: Exception) {}
        }
    }

    private fun offer(url: String, page: String, title: String, played: Boolean) {
        if (blocked(url) || blocked(page)) return
        val clean = url.substringBefore('#')
        if (clean.isBlank()) return
        val hit = Hit(clean, page.substringBefore('#').ifBlank { clean }, title.trim(), kindOf(clean), played)
        synchronized(lock) {
            val cur = _hits.value
            val i = cur.indexOfFirst { it.url == hit.url }
            _hits.value = if (i >= 0) {
                val old = cur[i]
                if (hit.played && !old.played) cur.toMutableList().also { it[i] = old.copy(played = true, title = hit.title.ifBlank { old.title }) }
                else cur
            } else cur + hit
        }
    }

    internal fun host(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (_: Exception) { Extract.host(url) }

    private fun canonPage(url: String) = url.substringBefore('#').trim()

    private const val HOOK = """(function(){
if (window.__baixarHook) { try { window.__baixarScan && window.__baixarScan(); } catch(e) {} return; }
window.__baixarHook = true;
function report(u, played){
  try {
    BaixarPage.onVideo(u||'', document.title||'', played?1:0);
  } catch(e) {}
}
function srcOf(v){
  if (!v) return '';
  var s = v.currentSrc || v.src || '';
  if (!s) {
    var src = v.querySelector && v.querySelector('source[src]');
    if (src) s = src.src || src.getAttribute('src') || '';
  }
  return s||'';
}
window.__baixarScan = function(){
  try {
    var list = document.querySelectorAll('video');
    for (var i=0;i<list.length;i++) report(srcOf(list[i]), false);
  } catch(e) {}
};
document.addEventListener('play', function(e){
  var t = e.target;
  if (!t || (t.tagName!=='VIDEO' && t.tagName!=='AUDIO')) return;
  var s = srcOf(t);
  if (!s || s.indexOf('blob:')===0 || s.indexOf('mediasource:')===0) report(location.href, true);
  else report(s, true);
}, true);
try {
  new MutationObserver(window.__baixarScan).observe(document.documentElement||document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src']});
} catch(e) {}
window.__baixarScan();
})();"""
}
