package br.geracaoai.baixartg.core

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * RASTRO de cada vídeo, de ponta a ponta: como foi achado (por qual caminho do app), se ficou escondido, quando você
 * mandou baixar, cada tentativa de download (qual método, erro ou sucesso, tamanho e tipo do arquivo, qualidade HLS…)
 * e o envio ao Telegram. Fica só no celular (arquivo trilha.jsonl). O detetive lê isso para saber EXATAMENTE como um
 * vídeo baixado saiu do site e foi para o Telegram, e repetir o mesmo caminho. Os textos não têm links (só o site).
 */
object Trail {
    data class Ev(val t: Long, val step: String, val detail: String)
    private class Line(val keys: List<String>, val ev: Ev)

    private var file: File? = null
    private val lines = ArrayList<Line>()
    private val once = HashSet<String>()

    fun init(dir: File) {
        file = File(dir, "trilha.jsonl")
        try {
            file?.takeIf { it.exists() }?.readLines()?.takeLast(4000)?.forEach { l ->
                try {
                    val o = JSONObject(l)
                    val ks = o.optJSONArray("k")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    synchronized(lines) { lines += Line(ks, Ev(o.optLong("t"), o.optString("s"), o.optString("d"))) }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Chave do endereço: sem query/fragmento (links temporários mudam só o token). */
    fun key(u: String?): String? = u?.takeIf { it.startsWith("http", true) }?.substringBefore('#')?.substringBefore('?')?.lowercase()?.trimEnd('/')

    fun host(u: String?): String = try { (java.net.URI(u ?: "").host ?: "").removePrefix("www.") } catch (_: Exception) { "" }

    fun add(urls: List<String?>, step: String, detail: String = "") {
        val ks = urls.mapNotNull { key(it) }.distinct()
        if (ks.isEmpty()) return
        val ev = Ev(System.currentTimeMillis(), step, detail.take(400))
        synchronized(lines) {
            lines += Line(ks, ev)
            if (lines.size > 6000) { val keep = lines.takeLast(4000); lines.clear(); lines.addAll(keep); rewrite() }
        }
        try { file?.appendText(JSONObject().put("k", JSONArray(ks)).put("t", ev.t).put("s", step).put("d", ev.detail).toString() + "\n") } catch (_: Exception) {}
    }

    /** Só a primeira vez para este endereço + passo (pedidos repetidos do player não enchem o rastro). */
    fun addOnce(urls: List<String?>, step: String, detail: String = "") {
        val k = (urls.firstNotNullOfOrNull { key(it) } ?: return) + "|" + step
        synchronized(once) { if (!once.add(k)) return; if (once.size > 20_000) once.clear() }
        add(urls, step, detail)
    }

    private fun rewrite() {
        val f = file ?: return
        try {
            f.writeText(lines.joinToString("") { l -> JSONObject().put("k", JSONArray(l.keys)).put("t", l.ev.t).put("s", l.ev.step).put("d", l.ev.detail).toString() + "\n" })
        } catch (_: Exception) {}
    }

    /** Tudo o que aconteceu com estes endereços (vídeo, página…), em ordem. Segue as ligações (link novo do mesmo vídeo). */
    fun of(urls: List<String?>, expand: Boolean = false): List<Ev> {
        val want = urls.mapNotNull { key(it) }.toHashSet()
        if (want.isEmpty()) return emptyList()
        val out = ArrayList<Ev>()
        synchronized(lines) {
            // seguir as ligações só quando pedido: pela página entravam os eventos de TODOS os vídeos daquela página
            if (expand) repeat(2) { lines.forEach { l -> if (l.keys.any { it in want }) want.addAll(l.keys) } }
            lines.forEach { l -> if (l.keys.any { it in want }) out += l.ev }
        }
        return out.sortedBy { it.t }.distinctBy { it.step + "|" + it.detail }
    }

    // ---------------- dentro do download: quem chama não precisa saber o item ----------------
    class Ctx(val urls: List<String?>) : AbstractCoroutineContextElement(Ctx) { companion object Key : CoroutineContext.Key<Ctx> }

    suspend fun here(step: String, detail: String = "") {
        currentCoroutineContext()[Ctx]?.let { add(it.urls, step, detail) }
    }
}
