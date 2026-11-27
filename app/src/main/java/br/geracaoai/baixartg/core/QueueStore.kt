package br.geracaoai.baixartg.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fila salva no celular: se o Android fechar o app no meio (memória, limite de tempo em segundo plano,
 * travamento), a lista volta ao abrir, com o que foi enviado, o que deu erro e o que ficou pela metade.
 */
object QueueStore {
    private var file: File? = null
    private var crashFile: File? = null

    fun init(dir: File) {
        file = File(dir, "fila.json")
        crashFile = File(dir, "ultimo_erro.txt")
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile?.writeText("${System.currentTimeMillis()}\n${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}") }
            anterior?.uncaughtException(t, e)
        }
    }

    private fun fJson(f: Found) = JSONObject().put("url", f.url).put("page", f.page).put("title", f.title).put("kind", f.kind.name)
        .put("ref", f.ref ?: "").put("extra", f.extra).put("thumb", f.thumb ?: "").put("h", f.height).put("d", f.duration)
        .put("size", f.size).put("site", f.site).put("prev", f.preview ?: "").put("best", f.bestQuality)

    private fun fDe(j: JSONObject) = Found(j.getString("url"), j.optString("page"), j.optString("title"), runCatching { Kind.valueOf(j.optString("kind")) }.getOrDefault(Kind.DIRECT),
        j.optString("ref").ifBlank { null }, j.optString("extra"), j.optString("thumb").ifBlank { null }, j.optInt("h"), j.optInt("d"),
        j.optLong("size", -1), j.optString("site"), j.optString("prev").ifBlank { null }, j.optBoolean("best"))

    @Synchronized fun salvar(items: List<Item>, rodando: Boolean) {
        val f = file ?: return
        runCatching {
            val a = JSONArray()
            items.forEach { a.put(JSONObject().put("f", fJson(it.f)).put("sel", it.sel).put("st", it.st.name).put("msg", it.msg).put("total", it.total).put("sent", it.sent)) }
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("rodando", rodando).put("quando", System.currentTimeMillis()).put("itens", a).toString())
            tmp.renameTo(f)
        }
    }

    data class Restaurado(val itens: List<Item>, val estavaRodando: Boolean, val quando: Long)

    fun carregar(): Restaurado? = runCatching {
        val f = file?.takeIf { it.exists() } ?: return null
        val j = JSONObject(f.readText())
        val a = j.getJSONArray("itens")
        val rodando = j.optBoolean("rodando")
        val l = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            var st = runCatching { St.valueOf(o.optString("st")) }.getOrDefault(St.WAIT)
            var msg = o.optString("msg")
            if (rodando && st in setOf(St.WAIT, St.DOWN, St.READY, St.UP)) { st = St.STOPPED; msg = "Interrompido quando o app fechou" }
            Item(i, fDe(o.getJSONObject("f")), sel = o.optBoolean("sel"), st = st, msg = msg, total = o.optLong("total", -1), sent = o.optBoolean("sent"),
                prog = if (st == St.DONE) 1f else 0f)
        }
        Restaurado(l, rodando, j.optLong("quando"))
    }.getOrNull()

    /** Por que o app fechou da última vez (o Android guarda o motivo). */
    fun motivoFechou(c: Context, desde: Long): String? = runCatching {
        val am = c.getSystemService(ActivityManager::class.java)
        val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 1).firstOrNull() ?: return null
        if (e.timestamp < desde - 60_000) return null
        val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(e.timestamp))
        val crash = crashFile?.takeIf { it.exists() }?.readLines()?.getOrNull(1)
        when (e.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "o Android fechou o app às $hora por falta de memória"
            ApplicationExitInfo.REASON_CRASH -> "o app travou às $hora" + (crash?.let { " ($it)" } ?: "")
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "o app travou às $hora ao converter/ler um vídeo"
            ApplicationExitInfo.REASON_ANR -> "o app parou de responder às $hora"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "o app foi fechado às $hora (deslizado da lista de recentes ou Forçar parada)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "o Android fechou o app às $hora por uso excessivo de bateria/CPU"
            ApplicationExitInfo.REASON_FREEZER -> "o Android congelou o app às $hora"
            else -> "o Android fechou o app às $hora (${e.description ?: "motivo ${e.reason}"})"
        }
    }.getOrNull()
}
