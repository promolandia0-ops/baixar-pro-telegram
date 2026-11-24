package br.geracaoai.baixartg.core

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de erros e quedas do app. Vai junto no "Copiar diagnóstico". */
object CrashLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    fun init(c: Context) { file = File(c.filesDir, "crash.log") }

    @Synchronized
    fun write(msg: String, t: Throwable? = null) {
        val body = buildString {
            append(fmt.format(Date())).append(' ').append(msg)
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
                t.stackTrace.take(6).forEach { append("\n    at ").append(it) }
            }
            append('\n')
        }
        try {
            val f = file ?: return
            if (f.length() > 200_000) f.writeText("")
            f.appendText(body)
        } catch (_: Exception) {}
    }

    fun tail(n: Int = 120): String = try {
        file?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    /** Renderer do WebView caiu: não derruba o app. Registra e segue. */
    fun rendererGone(tag: String, detail: RenderProcessGoneDetail?): Boolean {
        write("WEBVIEW $tag: a página travou e foi recarregada (didCrash=${detail?.didCrash()}, prioridade=${detail?.rendererPriorityAtExit()})")
        return true
    }
}
