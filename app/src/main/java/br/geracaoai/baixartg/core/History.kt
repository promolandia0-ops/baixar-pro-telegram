package br.geracaoai.baixartg.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

data class HistEntry(val f: Found, val time: Long, val size: Long)

/** Histórico de vídeos enviados (com título/link), para a aba Histórico e o botão Reenviar. */
object History {
    private var file: File? = null
    val list = MutableStateFlow<List<HistEntry>>(emptyList())

    fun init(dir: File) {
        file = File(dir, "history.jsonl")
        list.value = file!!.takeIf { it.exists() }?.readLines()?.mapNotNull { parse(it) }?.reversed() ?: emptyList()
    }

    private fun parse(l: String): HistEntry? = try {
        val j = JSONObject(l)
        HistEntry(Found(j.getString("u"), j.getString("p"), j.optString("t"), Kind.valueOf(j.optString("k", "DIRECT")), j.optString("r").ifBlank { null }), j.optLong("ts"), j.optLong("s", -1))
    } catch (_: Exception) { null }

    @Synchronized fun add(f: Found, size: Long) {
        val e = HistEntry(f, System.currentTimeMillis(), size)
        val j = JSONObject().put("u", f.url).put("p", f.page).put("t", f.title).put("k", f.kind.name).put("r", f.ref ?: "").put("ts", e.time).put("s", size)
        try { file?.appendText(j.toString() + "\n") } catch (_: Exception) {}
        list.value = listOf(e) + list.value
    }

    @Synchronized fun clear() { file?.delete(); list.value = emptyList() }
}
