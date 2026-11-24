package br.geracaoai.baixartg.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Caixa-preta do app. Funciona mesmo quando o app fecha:
 *  - queda em Kotlin/Java: o gancho grava a pilha completa ANTES do processo morrer;
 *  - queda nativa, travamento (ANR), falta de memória, morto pelo sistema: o Android guarda o motivo
 *    (ApplicationExitInfo) e o app lê na próxima vez que abre;
 *  - trilha: os últimos passos (tela aberta, página carregando, teclado, memória) ficam no disco,
 *    então dá pra saber o que estava acontecendo na hora.
 * Nada de links ou domínios: só eventos e o hash curto do site.
 */
object CrashLog {
    private var file: File? = null
    private var trail: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private var appCtx: Context? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    /** Relatório pendente: o app fechou de forma anormal da última vez. A tela mostra e oferece copiar. */
    val pending = MutableStateFlow<String?>(null)

    /** Chamar como PRIMEIRA coisa no Application.onCreate. */
    fun install(c: Context) {
        appCtx = c.applicationContext
        file = File(c.filesDir, "crash.log")
        trail = File(c.filesDir, "trail.log")
        prefs = c.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                write("QUEDA (Kotlin/Java) na thread '${t.name}'", e, full = true)
                prefs?.edit()?.putBoolean("javaCrash", true)?.commit()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        try { checkLastExit() } catch (e: Throwable) { write("falha ao ler o motivo do último fechamento", e) }
        mark("app aberto (v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME})")
    }

    /** Compatibilidade com o código antigo. */
    fun init(c: Context) { if (file == null) install(c) }

    @Synchronized
    fun write(msg: String, t: Throwable? = null, full: Boolean = false) {
        val body = buildString {
            append(fmt.format(Date())).append(' ').append(msg)
            if (t != null) appendThrowable(this, t, if (full) 40 else 8)
            append('\n')
        }
        try {
            val f = file ?: return
            if (f.length() > 300_000) f.writeText(f.readText().takeLast(100_000))
            f.appendText(body)
        } catch (_: Exception) {}
    }

    private fun appendThrowable(sb: StringBuilder, t0: Throwable, frames: Int) {
        var t: Throwable? = t0; var depth = 0
        while (t != null && depth < 5) {
            sb.append(if (depth == 0) " | " else "\n  causado por: ").append(t.javaClass.name).append(": ").append((t.message ?: "").take(300))
            t.stackTrace.take(frames).forEach { sb.append("\n    at ").append(it) }
            t = t.cause.takeIf { it !== t }; depth++
        }
    }

    /** Trilha de passos (fica no disco; sobrevive à queda). Barata: 1 linha curta. */
    @Synchronized
    fun mark(step: String) {
        try {
            val f = trail ?: return
            if (f.length() > 24_000) f.writeText(f.readLines().takeLast(60).joinToString("\n", postfix = "\n"))
            f.appendText(fmt.format(Date()) + " " + step + "\n")
        } catch (_: Exception) {}
    }

    fun trailTail(n: Int = 25): String = try { trail?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: "" } catch (_: Exception) { "" }

    fun tail(n: Int = 120): String = try {
        file?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    /** Relatório completo para colar no chat. */
    fun report(): String = buildString {
        appendLine("— quedas e fechamentos —")
        appendLine(device())
        val t = tail(80); appendLine(t.ifBlank { "(nenhuma queda registrada)" })
        appendLine("— últimos passos —")
        appendLine(trailTail(25).ifBlank { "(vazio)" })
    }

    fun clear() { try { file?.writeText(""); trail?.writeText("") } catch (_: Exception) {}; pending.value = null }

    private fun device(): String {
        val c = appCtx
        val mem = try {
            val am = c?.getSystemService(ActivityManager::class.java); val mi = ActivityManager.MemoryInfo(); am?.getMemoryInfo(mi)
            "RAM livre ${mi.availMem shr 20}/${mi.totalMem shr 20} MB, app máx ${Runtime.getRuntime().maxMemory() shr 20} MB"
        } catch (_: Exception) { "" }
        val wv = try { WebView.getCurrentWebViewPackage()?.versionName ?: "?" } catch (_: Throwable) { "?" }
        return "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), WebView $wv, $mem"
    }

    /** Lê por que o processo anterior terminou (Android 11+). Quedas nativas, ANR e falta de memória só aparecem aqui. */
    private fun checkLastExit() {
        val c = appCtx ?: return
        val p = prefs ?: return
        val hadJava = p.getBoolean("javaCrash", false)
        val lastSeen = p.getLong("lastExitTs", 0L)
        val am = c.getSystemService(ActivityManager::class.java) ?: return
        val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 8)
            .filter { it.timestamp > lastSeen && it.processName == c.packageName }
            .sortedBy { it.timestamp }
        if (list.isNotEmpty()) p.edit().putLong("lastExitTs", list.maxOf { it.timestamp }).apply()
        var abnormal: ApplicationExitInfo? = null
        list.forEach { e ->
            if (isAbnormal(e)) {
                abnormal = e
                val sb = StringBuilder("FECHOU: ").append(reasonText(e.reason))
                if (e.description != null) sb.append(" — ").append(e.description!!.take(200))
                sb.append(" (em ").append(fmt.format(Date(e.timestamp))).append(", importância=").append(e.importance)
                sb.append(", memória usada ").append(e.pss shr 10).append(" MB)")
                if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try {
                        e.traceInputStream?.use { ins ->
                            val txt = ins.bufferedReader().readText()
                            val lines = txt.lines()
                            val keep = if (e.reason == ApplicationExitInfo.REASON_ANR)
                                lines.dropWhile { !it.contains("\"main\"") }.take(30)
                            else lines.filter { it.contains("signal") || it.contains("Abort") || it.trimStart().startsWith("#") || it.contains("backtrace") }.take(30)
                            if (keep.isNotEmpty()) sb.append("\n    ").append(keep.joinToString("\n    "))
                        }
                    } catch (_: Throwable) {}
                }
                write(sb.toString())
            }
        }
        p.edit().putBoolean("javaCrash", false).apply()
        if (abnormal != null || hadJava) {
            pending.value = report()
        }
    }

    private fun isAbnormal(e: ApplicationExitInfo): Boolean = when (e.reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_SIGNALED -> true
        else -> false
    }

    private fun reasonText(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "queda no código Kotlin/Java"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "queda nativa (biblioteca C/C++, WebView ou TDLib)"
        ApplicationExitInfo.REASON_ANR -> "travou e o Android fechou (ANR: tela parada por mais de 5 s)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "o Android fechou por falta de memória"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excessivo de recursos"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falha ao iniciar"
        ApplicationExitInfo.REASON_SIGNALED -> "processo morto por sinal do sistema"
        else -> "motivo $r"
    }

    /**
     * O processo de renderização das páginas caiu (memória, página pesada ou bug do WebView).
     * Retorna true para o app NÃO fechar — e por isso o WebView afetado precisa ser descartado:
     * usar de novo um WebView cujo renderizador morreu derruba o app.
     */
    fun rendererGone(tag: String, view: WebView?, detail: RenderProcessGoneDetail?, after: (() -> Unit)? = null): Boolean {
        val why = if (detail?.didCrash() == true) "a página quebrou o renderizador" else "o Android matou o renderizador (falta de memória)"
        write("WEBVIEW $tag: $why (prioridade=${detail?.rendererPriorityAtExit()})")
        mark("webview $tag caiu")
        main.post {
            try { (view?.parent as? ViewGroup)?.removeView(view) } catch (_: Throwable) {}
            try { view?.destroy() } catch (_: Throwable) {}
            try { after?.invoke() } catch (e: Throwable) { write("falha ao recriar WebView $tag", e) }
        }
        return true
    }

    /** Compatibilidade: versão antiga sem a view (não use para WebViews que continuam em uso). */
    fun rendererGone(tag: String, detail: RenderProcessGoneDetail?): Boolean = rendererGone(tag, null, detail)

    fun siteTag(url: String?): String = try { SiteHash.short("site", url ?: "") } catch (_: Throwable) { "?" }
}
