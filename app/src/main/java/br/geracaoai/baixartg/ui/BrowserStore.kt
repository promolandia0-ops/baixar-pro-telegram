package br.geracaoai.baixartg.ui

import kotlinx.coroutines.launch
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import br.geracaoai.baixartg.core.Extract
import br.geracaoai.baixartg.core.Found
import br.geracaoai.baixartg.core.Kind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Disco do Navegador: lista do painel por site, páginas recentes e favoritos. */
object BrowserStore {
    data class Site(val url: String, val title: String)
    private var dir: File? = null
    private var recentsFile: File? = null
    private var favFile: File? = null
    val recents = mutableStateListOf<Site>()
    val favorites = mutableStateListOf<Site>()

    fun init(c: Context) {
        dir = File(c.filesDir, "panel").apply { mkdirs() }
        recentsFile = File(c.filesDir, "browser_recents.json"); favFile = File(c.filesDir, "browser_favorites.json")
        recents.clear(); recents.addAll(readSites(recentsFile)); favorites.clear(); favorites.addAll(readSites(favFile))
    }

    private fun readSites(f: File?): List<Site> = try {
        val a = JSONArray(f!!.readText()); (0 until a.length()).map { a.getJSONObject(it).let { o -> Site(o.getString("u"), o.optString("t")) } }
    } catch (_: Exception) { emptyList() }
    /** Grava fora da thread principal. */
    private fun writeSites(f: File?, l: List<Site>) { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { writeSitesNow(f, l) } }
    @Synchronized private fun writeSitesNow(f: File?, l: List<Site>) { try { f?.writeText(JSONArray(l.map { JSONObject().put("u", it.url).put("t", it.title) }).toString()) } catch (_: Exception) {} }

    fun visit(url: String, title: String) {
        if (!url.startsWith("http")) return
        val i = recents.indexOfFirst { it.url == url }
        val t = title.ifBlank { recents.getOrNull(i)?.title ?: "" }
        if (i == 0 && recents[0].title == t) return
        if (i >= 0) recents.removeAt(i)
        recents.add(0, Site(url, t))
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        writeSites(recentsFile, recents.toList())
    }
    fun removeRecent(url: String) { recents.removeAll { it.url == url }; writeSites(recentsFile, recents.toList()) }
    fun isFav(url: String) = favorites.any { it.url == url }
    fun toggleFav(url: String, title: String) {
        if (!url.startsWith("http")) return
        if (isFav(url)) favorites.removeAll { it.url == url } else favorites.add(0, Site(url, title))
        writeSites(favFile, favorites.toList())
    }
    fun removeFav(url: String) { favorites.removeAll { it.url == url }; writeSites(favFile, favorites.toList()) }

    // ---------- lista do painel ----------
    private fun fToJ(f: Found) = JSONObject().put("u", f.url).put("p", f.page).put("t", f.title).put("k", f.kind.name).put("r", f.ref ?: "").put("x", f.extra.take(800)).put("q", f.quality)
    private fun jToF(j: JSONObject) = Found(j.getString("u"), j.optString("p"), j.optString("t"), Kind.valueOf(j.optString("k", "SERVER")), j.optString("r").ifBlank { null }, j.optString("x"), j.optInt("q"))
    private fun fileFor(host: String) = File(dir, host.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    fun hostOf(d: BrowserDetect.Det) = Extract.host(d.f.page.ifBlank { d.f.url })

    /** Grava a lista agrupada por site (e zera os sites carregados que ficaram vazios). */
    @Synchronized fun savePanel(videos: List<BrowserDetect.Det>, photos: List<BrowserDetect.Det>, loaded: Set<String>) {
        if (dir == null) return
        val hosts = (videos.map { hostOf(it) } + photos.map { hostOf(it) } + loaded).filter { it.isNotBlank() }.toSet()
        for (h in hosts) {
            val o = JSONObject()
            o.put("v", JSONArray(videos.filter { hostOf(it) == h }.map { detJ(it) }))
            o.put("ph", JSONArray(photos.filter { hostOf(it) == h }.map { detJ(it) }))
            try { val f = fileFor(h); val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f) } catch (_: Exception) {}
        }
    }
    private fun detJ(d: BrowserDetect.Det) = JSONObject().put("key", d.key).put("f", fToJ(d.f)).put("sel", d.sel).put("keys", JSONArray(d.keys)).put("st", d.state)

    @Synchronized fun clearPanels() {
        try { dir?.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    fun loadPanel(host: String): Pair<List<BrowserDetect.Det>, List<BrowserDetect.Det>> = try {
        val o = JSONObject(fileFor(host).readText())
        fun arr(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).mapNotNull { i ->
            try { val j = a.getJSONObject(i)
                val ks = j.optJSONArray("keys")?.let { ka -> (0 until ka.length()).map { ka.getString(it) } } ?: emptyList()
                BrowserDetect.Det(j.getString("key"), jToF(j.getJSONObject("f")), sel = j.optBoolean("sel"), keys = ks, state = j.optInt("st"))
            } catch (_: Exception) { null } } } ?: emptyList()
        arr("v") to arr("ph")
    } catch (_: Exception) { emptyList<BrowserDetect.Det>() to emptyList() }
}
