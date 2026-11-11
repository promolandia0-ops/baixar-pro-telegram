package br.geracaoai.baixartg.web

import android.content.Context
import android.webkit.WebResourceResponse
import br.geracaoai.baixartg.core.Net
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Bloqueio de anúncios e rastreadores no estilo EasyList + EasyPrivacy:
 * conjunto de hosts em HashSet (rápido), regras host+caminho, e CSS cosmético.
 * Lista empacotada nos assets; atualização periódica a partir das listas oficiais.
 * EasyList/EasyPrivacy © EasyList authors, CC BY-SA 3.0 / GPLv3 — https://easylist.to/
 */
object AdBlock {
    private val hosts = HashSet<String>(131072)
    private val hostPaths = HashMap<String, ArrayList<String>>(8192)
    private val paths = ArrayList<String>(512)
    @Volatile var cosmeticCss: String = DEFAULT_CSS
        private set
    @Volatile var ready = false
        private set
    private val loading = AtomicBoolean(false)
    private val updating = AtomicBoolean(false)

    /** Domínios de primeira parte: nunca bloqueamos o host inteiro (só caminhos de anúncio/rastreador). */
    private val firstParty = setOf(
        "google.com", "youtube.com", "youtu.be", "googlevideo.com", "gstatic.com", "googleapis.com",
        "ytimg.com", "ggpht.com", "googleusercontent.com", "accounts.google.com", "gmail.com",
        "twitter.com", "x.com", "twimg.com", "t.co", "facebook.com", "instagram.com", "fbcdn.net",
        "cdninstagram.com", "telegram.org", "t.me", "tdesktop.com", "mega.nz", "mega.io", "mega.app",
        "wikipedia.org", "wikimedia.org", "github.com", "githubusercontent.com",
        "apple.com", "vimeo.com", "vimeocdn.com", "dailymotion.com", "reddit.com", "redditmedia.com",
        "redd.it", "duckduckgo.com", "bing.com", "microsoft.com", "live.com", "microsoftonline.com",
        "office.com", "archive.org", "render.com", "onrender.com"
    )
    private val cdnApex = setOf("cloudfront.net", "amazonaws.com", "akamaihd.net", "akamaized.net", "cloudflare.com", "amazon.com")
    private val trackLabels = setOf(
        "ad", "ads", "adsdk", "adserver", "adsense", "adsystem", "adservice", "adapis", "ogads", "pagead",
        "doubleclick", "analytics", "telemetry", "tracking", "tracker", "pixel", "metrics", "metric",
        "smetric", "measure", "diagnostics", "logging", "log", "fcmatch", "imasdk", "prebid", "sponsor",
        "advertising", "advert", "adn", "adx"
    )

    private const val UPDATE_MS = 4L * 24 * 60 * 60 * 1000
    private val listUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt"
    )

    val emptyResponse: WebResourceResponse
        get() = try {
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", hashMapOf<String, String>(), ByteArrayInputStream(ByteArray(0)))
        } catch (_: Exception) {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

    fun warm(ctx: Context) {
        if (ready || !loading.compareAndSet(false, true)) {
            maybeUpdate(ctx); return
        }
        Thread({
            try { loadBundled(ctx); loadOverlay(ctx) } catch (_: Exception) {}
            ready = true
            loading.set(false)
            maybeUpdate(ctx)
        }, "adblock-load").start()
    }

    /** Para testes unitários, sem Android assets. */
    fun loadForTest(hostList: List<String>, pathList: List<String> = emptyList(), hostPathList: List<Pair<String, String>> = emptyList()) {
        synchronized(hosts) {
            hosts.clear(); hostPaths.clear(); paths.clear()
            hosts.addAll(hostList.map { it.lowercase() })
            paths.addAll(pathList)
            for ((h, p) in hostPathList) hostPaths.getOrPut(h.lowercase()) { ArrayList() }.add(p.lowercase())
        }
        ready = true
    }

    fun shouldBlock(url: String, mainFrame: Boolean = false, pageUrl: String? = null): Boolean {
        return try {
        val u = try { java.net.URI(url) } catch (_: Exception) { return false }
        val scheme = (u.scheme ?: "").lowercase()
        if (scheme !in setOf("http", "https")) return false
        val host = (u.host ?: "").lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        val path = ((u.path ?: "/") + (u.query?.let { "?$it" } ?: "")).lowercase()
        if (mainFrame && isProtectedHost(host)) return false
        if (blockedHost(host) && !isProtectedHost(host)) return true
        if (matchHostPath(host, path)) return true
        if (matchGenericPath(path)) {
            // em sites de primeira parte, só caminhos claramente de anúncio/rastreador
            if (isProtectedHost(host) && !looksLikeAdPath(path)) return false
            return true
        }
        return false
        } catch (_: Exception) { false }
    }

    fun isProtectedHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h in firstParty || h in cdnApex) return true
        for (a in firstParty) {
            if (h.endsWith(".$a")) {
                val sub = h.substring(0, h.length - a.length - 1)
                val labels = sub.split('.', '-')
                return labels.none { isTrackLabel(it) }
            }
        }
        return false
    }

    fun sameSite(a: String?, b: String?): Boolean {
        val ha = registrable(hostOf(a) ?: return false)
        val hb = registrable(hostOf(b) ?: return false)
        return ha.isNotEmpty() && ha == hb
    }

    fun injectScript(css: String = cosmeticCss): String {
        val safe = css.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\$").replace("</", "<\\/")
        return """
            (function(){
              if (window.__bptShield) return; window.__bptShield=1;
              try {
                var s=document.createElement('style'); s.id='bpt-adhide';
                s.textContent=`$safe`;
                (document.documentElement||document.head||document.body).appendChild(s);
              } catch(e) {}
              try { window.open = function(){ return null; }; } catch(e) {}
              document.addEventListener('click', function(ev){
                var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
                if (!a) return;
                var href = a.getAttribute('href')||'';
                if (/^\s*(javascript:|data:)/i.test(href)) { ev.stopPropagation(); ev.preventDefault(); }
              }, true);
            })();
        """.trimIndent()
    }

    private fun blockedHost(host: String): Boolean {
        var h = host
        synchronized(hosts) {
            while (h.contains('.')) {
                if (hosts.contains(h)) return true
                h = h.substringAfter('.')
            }
        }
        return false
    }

    private fun matchHostPath(host: String, path: String): Boolean {
        var h = host
        synchronized(hosts) {
            while (true) {
                val rules = hostPaths[h]
                if (rules != null) for (p in rules) if (path.startsWith(p)) return true
                if (!h.contains('.')) break
                h = h.substringAfter('.')
            }
        }
        return false
    }

    private fun matchGenericPath(path: String): Boolean {
        synchronized(hosts) { for (p in paths) if (path.contains(p)) return true }
        return looksLikeAdPath(path)
    }

    fun looksLikeAdPath(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("/pagead") || p.contains("googlesyndication") || p.contains("googleadservices") ||
            p.contains("/ptracking") || p.contains("doubleclick") || p.contains("/adsense") ||
            p.contains("/ads?") || p.contains("/ads/") || p.contains("/ad-") || p.contains("/adserver") ||
            p.contains("/adsystem") || p.contains("prebid") || p.contains("/pixel") && (p.contains("track") || p.contains("ad"))
    }

    private fun isTrackLabel(l: String): Boolean {
        if (l in trackLabels) return true
        return l.startsWith("ads") || l.endsWith("ads") || l.endsWith("-ad") || l.startsWith("ad-") ||
            "analytics" in l || "telemetry" in l || "doubleclick" in l || "pagead" in l
    }

    private fun hostOf(url: String?): String? = try { url?.let { java.net.URI(it).host?.lowercase() } } catch (_: Exception) { null }
    private fun registrable(host: String): String {
        val parts = host.removePrefix("www.").split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val sld = parts[parts.size - 2]
        val n = if (sld.length <= 3 && parts.last().length == 2) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    private fun openAsset(ctx: Context, name: String): InputStream {
        // O aapt descompacta assets *.gz e publica o arquivo sem o .gz no APK.
        return try { GZIPInputStream(ctx.assets.open("$name.gz")) } catch (_: Exception) { ctx.assets.open(name) }
    }

    private fun loadBundled(ctx: Context) {
        openAsset(ctx, "adblock/hosts.txt").use { loadHosts(it) }
        try { openAsset(ctx, "adblock/hostpaths.txt").use { loadHostPaths(it) } } catch (_: Exception) {}
        try { ctx.assets.open("adblock/paths.txt").use { loadPaths(it) } } catch (_: Exception) {}
        try { ctx.assets.open("adblock/cosmetic.css.txt").bufferedReader().use { cosmeticCss = DEFAULT_CSS + "\n" + it.readText() } } catch (_: Exception) {}
    }

    private fun loadOverlay(ctx: Context) {
        val dir = File(ctx.filesDir, "adblock")
        val hf = File(dir, "hosts.txt")
        if (hf.isFile) hf.inputStream().use { loadHosts(it) }
        val pf = File(dir, "hostpaths.txt")
        if (pf.isFile) pf.inputStream().use { loadHostPaths(it) }
    }

    private fun loadHosts(ins: InputStream) {
        BufferedReader(ins.reader()).use { br ->
            synchronized(hosts) {
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    val h = line.trim().lowercase()
                    if (h.isEmpty() || h.startsWith("#") || isProtectedApex(h) && !isTrackyFirstParty(h)) continue
                    if (!isProtectedApex(h) || isTrackyFirstParty(h)) hosts.add(h)
                }
            }
        }
    }

    private fun loadHostPaths(ins: InputStream) {
        BufferedReader(ins.reader()).use { br ->
            synchronized(hosts) {
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) continue
                    val i = t.indexOf('\t')
                    if (i <= 0) continue
                    val h = t.substring(0, i).lowercase()
                    val p = t.substring(i + 1).lowercase()
                    hostPaths.getOrPut(h) { ArrayList() }.add(p)
                }
            }
        }
    }

    private fun loadPaths(ins: InputStream) {
        BufferedReader(ins.reader()).use { br ->
            synchronized(hosts) {
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    val p = line.trim().lowercase()
                    if (p.isNotEmpty() && !p.startsWith("#")) paths.add(p)
                }
            }
        }
    }

    private fun isProtectedApex(h: String): Boolean {
        if (h in firstParty || h in cdnApex) return true
        return firstParty.any { h.endsWith(".$it") } && !isTrackyFirstParty(h)
    }

    private fun isTrackyFirstParty(h: String): Boolean {
        for (a in firstParty) if (h.endsWith(".$a")) {
            return h.substring(0, h.length - a.length - 1).split('.', '-').any { isTrackLabel(it) }
        }
        return false
    }

    private fun maybeUpdate(ctx: Context) {
        val dir = File(ctx.filesDir, "adblock")
        val stamp = File(dir, "updated")
        val last = stamp.let { if (it.isFile) it.lastModified() else 0L }
        if (System.currentTimeMillis() - last < UPDATE_MS) return
        if (!updating.compareAndSet(false, true)) return
        Thread({
            try { updateFromNetwork(ctx) } catch (_: Exception) {}
            updating.set(false)
        }, "adblock-update").start()
    }

    private fun updateFromNetwork(ctx: Context) {
        val newHosts = LinkedHashSet<String>()
        val newHp = ArrayList<Pair<String, String>>()
        for (u in listUrls) {
            val body = try {
                Net.http.newCall(okhttp3.Request.Builder().url(u).header("Accept", "text/plain").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            } catch (_: Exception) { null } ?: continue
            parseFilterList(body, newHosts, newHp)
        }
        if (newHosts.size < 1000) return
        val dir = File(ctx.filesDir, "adblock"); dir.mkdirs()
        File(dir, "hosts.txt").writeText(newHosts.sorted().joinToString("\n", postfix = "\n"))
        File(dir, "hostpaths.txt").writeText(newHp.joinToString("") { "${it.first}\t${it.second}\n" })
        File(dir, "updated").writeText(System.currentTimeMillis().toString())
        synchronized(hosts) { hosts.addAll(newHosts) }
        synchronized(hosts) { for ((h, p) in newHp) hostPaths.getOrPut(h) { ArrayList() }.add(p) }
    }

    /** Parser simples de EasyList: só ||host^ (host inteiro) e ||host/caminho. */
    fun parseFilterList(text: String, outHosts: MutableSet<String>, outHp: MutableList<Pair<String, String>>) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line.startsWith("@@") || "##" in line) continue
            val full = Regex("""^\|\|([a-zA-Z0-9.-]+)\^(?:\$.*)?$""").matchEntire(line)
            if (full != null) {
                val h = full.groupValues[1].lowercase().trimStart('.')
                if (h.contains('.') && !h.contains('*') && (!isProtectedApex(h) || isTrackyFirstParty(h))) outHosts.add(h)
                continue
            }
            val hp = Regex("""^\|\|([a-zA-Z0-9.-]+)(?:\^)?(/[^\s^$*]{1,80})""").find(line)
            if (hp != null) {
                val h = hp.groupValues[1].lowercase().trimStart('.')
                val p = hp.groupValues[2].substringBefore('$').substringBefore('^').lowercase()
                if (h.contains('.') && p.startsWith("/")) outHp.add(h to p)
            }
        }
    }

    const val DEFAULT_CSS = """
#ad,#ads,#ad-banner,#ad-container,#advertisement,#adsense,#ad_holder,
.ad,.ads,.adsbox,.adsbygoogle,.ad-banner,.ad-container,.ad-slot,.adbox,
.advert,.advertisement,.advertising,.adunit,.ad-unit,.ad_unit,.ad-wrapper,.ads-wrapper,
.sponsored,.sponsor,.promo-ad,.popup-ad,.pop-ad,.dfp-ad,.gpt-ad,
[id*="google_ads"],[class*="adsbygoogle"],[id^="div-gpt-ad"],
iframe[src*="doubleclick"],iframe[src*="googlesyndication"]
{display:none!important;visibility:hidden!important;height:0!important;width:0!important;overflow:hidden!important;}
"""
}
