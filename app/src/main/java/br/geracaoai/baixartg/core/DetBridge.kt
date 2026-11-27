package br.geracaoai.baixartg.core

import android.content.Context
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Detetive — ponte entre o app e o agente que roda em cada quadro da página (DetAgent).
 * Guarda o que os quadros contam sozinhos (respostas de rede com vídeo, <video> carregando, DRM) e permite mandar
 * comandos para todos os quadros de uma vez (varrer, fotos, play, carregar, fechar janelas).
 */
object DetBridge {
    const val NAME = "bptDet"
    @Volatile var installed = false
        private set
    /** Respostas de cada quadro (endereço do quadro → canal de volta). Quadros mortos só ignoram o comando. */
    private val proxies = object : LinkedHashMap<String, JavaScriptReplyProxy>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, JavaScriptReplyProxy>?) = size > 60
    }
    data class NetHit(val frame: String, val url: String, val body: String, val at: Long)
    private val net = ArrayDeque<NetHit>()
    private val media = LinkedHashSet<String>()
    @Volatile var drm: String = ""
    @Volatile var blobs = 0
    @Volatile var pageStart = 0L
    private val replies = ConcurrentHashMap<Int, MutableList<JSONObject>>()
    /** Sempre ligado: endereço de vídeo que o agente viu (player carregou, hls.js/shaka, resposta de rede) vai direto para a lista. */
    @Volatile var onMedia: ((url: String, frame: String, src: String) -> Unit)? = null
    @Volatile var onNet: ((url: String, frame: String, body: String) -> Unit)? = null
    private val seq = AtomicInteger(1)

    /** Liga a ponte no WebView (uma vez, quando ele é criado). */
    fun install(w: WebView): Boolean {
        return try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
            WebViewCompat.addWebMessageListener(w, NAME, setOf("*"), listener)
            WebViewCompat.addDocumentStartJavaScript(w, DetAgent.JS, setOf("*"))
            installed = true
            true
        } catch (e: Throwable) { CrashLog.write("detetive: ponte não instalada", e); false }
    }

    private val listener = WebViewCompat.WebMessageListener { _: WebView, message: WebMessageCompat, _: android.net.Uri, _: Boolean, reply: JavaScriptReplyProxy ->
        try {
            val o = JSONObject(message.data ?: return@WebMessageListener)
            val f = o.optString("f")
            synchronized(proxies) { if (f.isNotBlank()) proxies[f] = reply }
            when (o.optString("t")) {
                "net" -> {
                    synchronized(net) {
                        // guarda pouco (antes: até 200 × 400 KB e o Android fechava o app por falta de memória)
                        net.addLast(NetHit(f, o.optString("u"), o.optString("b").take(60_000), System.currentTimeMillis()))
                        while (net.size > 40) net.removeFirst()
                    }
                    try { onNet?.invoke(o.optString("u"), f, o.optString("b")) } catch (_: Throwable) {}
                }
                "media" -> if (o.optInt("blob") == 1) blobs++ else o.optString("u").takeIf { it.startsWith("http") }?.let { u ->
                    synchronized(media) { media += u; if (media.size > 300) media.remove(media.first()) }
                    try { onMedia?.invoke(u, f, o.optString("via")) } catch (_: Throwable) {}
                }
                "drm" -> drm = o.optString("ks").ifBlank { "sim" }
                "scan", "photos", "did" -> replies[o.optInt("id")]?.add(o)
            }
        } catch (_: Throwable) {}
    }

    /** Manda um comando para todos os quadros sem esperar resposta (ex.: "maxq" = player na maior qualidade). */
    fun broadcast(cmd: String) {
        val j = JSONObject().put("c", cmd).put("id", 0).toString()
        val targets = synchronized(proxies) { proxies.values.toList() }
        for (p in targets) try { p.postMessage(j) } catch (_: Throwable) {}
    }

    fun newPage() {
        pageStart = System.currentTimeMillis()
        synchronized(net) { net.clear() }; synchronized(media) { media.clear() }
        drm = ""; blobs = 0
    }

    fun netSince(t: Long): List<NetHit> = synchronized(net) { net.filter { it.at >= t } }
    fun mediaSeen(): List<String> = synchronized(media) { media.toList() }
    fun frames(): Int = synchronized(proxies) { proxies.size }

    suspend fun eval(web: WebView, js: String, timeoutMs: Long = 8_000): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<String?> { c ->
            try { web.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r) } } catch (_: Exception) { if (c.isActive) c.resume(null) }
        }
    }

    fun unq(raw: String?): String = try { org.json.JSONTokener(raw ?: "").nextValue() as? String ?: "" } catch (_: Exception) { "" }

    /** Garante o agente no quadro principal (aparelhos sem a ponte, ou página aberta antes do app instalar o agente). */
    suspend fun ensureTop(web: WebView): Boolean {
        val has = unq(eval(web, "(function(){return window.__bptDet?'1':'0'})()"))
        if (has == "1") return false
        eval(web, DetAgent.JS)
        return true
    }

    /**
     * Manda um comando a todos os quadros e junta as respostas. Só na thread principal (WebView).
     * O quadro principal responde direto pelo evaluateJavascript (funciona mesmo sem a ponte).
     */
    suspend fun ask(web: WebView, cmd: String, waitMs: Long = 2_500, globals: List<String> = emptyList()): List<JSONObject> {
        val id = seq.incrementAndGet()
        val got = java.util.Collections.synchronizedList(ArrayList<JSONObject>())
        replies[id] = got
        val j = JSONObject().put("c", cmd).put("id", id)
        if (globals.isNotEmpty()) j.put("g", JSONArray(globals))
        val targets = synchronized(proxies) { proxies.values.toList() }
        for (p in targets) try { p.postMessage(j.toString()) } catch (_: Throwable) {}
        val topJ = JSONObject(j.toString()).put("ret", 1)
        val top = unq(eval(web, "(function(){try{return window.__bptDet?window.__bptDet.cmd(" + JSONObject.quote(topJ.toString()) + "):''}catch(e){return ''}})()", 12_000))
        val out = ArrayList<JSONObject>()
        try { if (top.isNotBlank()) out += JSONObject(top).put("top", 1).put("f", web.url.orEmpty()) } catch (_: Exception) {}
        if (targets.isNotEmpty()) {
            var t = 0L
            while (t < waitMs) { delay(250); t += 250; if (got.size >= targets.size) break }
        }
        replies.remove(id)
        // o quadro principal também responde pela ponte: fica só a resposta direta
        synchronized(got) { got.forEach { if (it.optInt("top") != 1 || out.isEmpty()) out += it } }
        return out
    }
}

/** Referer certo para o servidor yt-dlp (ex.: Vimeo privado só abre com a página da aula como origem). */
object RefererHints {
    private val m = ConcurrentHashMap<String, String>()
    fun set(url: String, ref: String?) {
        if (ref.isNullOrBlank() || ref == url) return
        m[url] = ref; DetMemory.saveHint(url, ref)
        if (m.size > 400) m.keys.take(100).forEach { m.remove(it) }
    }
    fun get(url: String): String? = m[url] ?: DetMemory.hint(url)?.also { m[url] = it }
}

/** O que funcionou em cada site (o detetive tenta isso primeiro da próxima vez). */
object DetMemory {
    private var p: android.content.SharedPreferences? = null
    fun init(c: Context) { if (p == null) p = c.applicationContext.getSharedPreferences("detetive", Context.MODE_PRIVATE) }
    data class Site(val wins: List<String>, val recipes: List<String>, val players: List<String>, val hits: Int, val at: Long)

    fun load(host: String): Site? = try {
        p?.getString("s:$host", null)?.let { s ->
            val o = JSONObject(s)
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Site(arr("w"), arr("r"), arr("p"), o.optInt("n"), o.optLong("at"))
        }
    } catch (_: Exception) { null }

    fun save(host: String, wins: Collection<String>, recipes: Collection<String>, players: Collection<String>) {
        if (host.isBlank() || wins.isEmpty()) return
        val old = load(host)
        val o = JSONObject()
            .put("w", JSONArray((wins + old?.wins.orEmpty()).distinct().take(8)))
            .put("r", JSONArray((recipes + old?.recipes.orEmpty()).distinct().take(8)))
            .put("p", JSONArray((players + old?.players.orEmpty()).distinct().take(8)))
            .put("n", (old?.hits ?: 0) + 1).put("at", System.currentTimeMillis())
        p?.edit()?.putString("s:$host", o.toString())?.apply()
    }

    fun forget(host: String) { p?.edit()?.remove("s:$host")?.remove("l:$host")?.apply() }

    /** Método aprendido com um vídeo baixado (por site). */
    fun saveLesson(l: DetLearn.Lesson) {
        val o = JSONObject().put("site", l.site).put("pe", l.pageExample).put("me", l.mediaExample).put("k", l.kind).put("r", l.ref.name)
            .put("rf", l.refFixed ?: "").put("mp", l.mediaPattern).put("pp", l.pagePattern).put("pi", l.pageIdRegex).put("mt", l.mediaTemplate)
            .put("rc", l.recipe).put("m", l.method).put("via", l.via).put("sg", l.signed)
        p?.edit()?.putString("l:" + l.site, o.toString())?.apply()
    }
    fun lesson(host: String): DetLearn.Lesson? = try {
        p?.getString("l:$host", null)?.let { s ->
            val o = JSONObject(s)
            DetLearn.Lesson(o.getString("site"), o.optString("pe"), o.optString("me"), o.optString("k"),
                runCatching { DetKnowledge.Ref.valueOf(o.optString("r")) }.getOrDefault(DetKnowledge.Ref.PAGE), o.optString("rf").ifBlank { null },
                o.optString("mp"), o.optString("pp"), o.optString("pi"), o.optString("mt"), o.optString("rc"), o.optString("m"), o.optString("via"), o.optBoolean("sg"))
        }
    } catch (_: Exception) { null }

    fun saveHint(url: String, ref: String) {
        val pp = p ?: return
        val k = "h:" + url.hashCode()
        pp.edit().putString(k, "$url\n$ref").apply()
    }
    fun hint(url: String): String? = p?.getString("h:" + url.hashCode(), null)?.split('\n')?.takeIf { it.size == 2 && it[0] == url }?.get(1)
}

/**
 * Receitas novas pela internet: um arquivo no GitHub do projeto (detetive/receitas.json). Quando aparece um site
 * novo, basta acrescentar a receita lá; o app baixa sozinho (a cada 6 h) e guarda uma cópia para usar sem internet.
 */
object DetRecipes {
    const val URL = "https://raw.githubusercontent.com/promolandia0-ops/baixar-pro-telegram/estabilidade/detetive/receitas.json"
    @Volatile private var loadedAt = 0L
    @Volatile var lastInfo = ""

    suspend fun ensure(ctx: Context, force: Boolean = false) {
        val f = java.io.File(ctx.filesDir, "detetive_receitas.json")
        if (loadedAt == 0L && f.exists()) try { apply(f.readText()); loadedAt = 1L } catch (_: Exception) {}
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L
        if (fresh && !force) { if (lastInfo.isBlank()) lastInfo = "${DetKnowledge.remoteCount} receita(s) extra (cópia local)"; return }
        try {
            val txt = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Net.fast.newCall(okhttp3.Request.Builder().url(URL).build()).await().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }
            if (txt != null && txt.trimStart().startsWith("{")) {
                apply(txt); f.writeText(txt); loadedAt = System.currentTimeMillis()
                lastInfo = "${DetKnowledge.remoteCount} receita(s) extra baixada(s) agora"
            } else if (lastInfo.isBlank()) lastInfo = "sem atualização (usando as receitas do app)"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (lastInfo.isBlank()) lastInfo = "sem internet para atualizar (usando as receitas do app)"
        }
    }

    fun apply(txt: String) {
        val o = JSONObject(txt)
        val rs = ArrayList<DetKnowledge.Recipe>()
        val ra = o.optJSONArray("recipes") ?: JSONArray()
        for (i in 0 until ra.length()) try {
            val r = ra.getJSONObject(i)
            val leads = ArrayList<DetKnowledge.LeadT>()
            val la = r.optJSONArray("leads") ?: JSONArray()
            for (k in 0 until la.length()) {
                val l = la.getJSONObject(k)
                leads += DetKnowledge.LeadT(l.getString("to"),
                    runCatching { DetKnowledge.LeadKind.valueOf(l.optString("kind", "MEDIA").uppercase()) }.getOrDefault(DetKnowledge.LeadKind.MEDIA),
                    runCatching { DetKnowledge.Ref.valueOf(l.optString("ref", "PAGE").uppercase()) }.getOrDefault(DetKnowledge.Ref.PAGE))
            }
            rs += DetKnowledge.Recipe(r.getString("id"), r.optString("name", r.getString("id")), Regex(r.getString("match"), RegexOption.IGNORE_CASE), leads, r.optString("note"))
        } catch (_: Exception) {}
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()
        val hosts = strs("mediaHosts").mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        DetKnowledge.setRemote(rs, hosts, strs("globals"))
    }
}
