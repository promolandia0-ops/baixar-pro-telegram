package br.geracaoai.baixartg.core

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Diagnóstico da última busca. Nunca guarda URL nem domínio: só hash curto, contagens e motivos. */
class Diag {
    val status = ConcurrentHashMap<Int, AtomicInteger>()
    val reasons = ConcurrentHashMap<String, AtomicInteger>()
    val events = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile var site = ""
    @Volatile var mode = ""
    @Volatile var challenge = false
    @Volatile var staticCards = -1
    @Volatile var webCards = -1
    @Volatile var webviewAvailable = true
    @Volatile var searchMethod = ""
    @Volatile var searchRequests = 0
    @Volatile var searchPages = 0
    @Volatile var taxonomyPages = 0
    @Volatile var resultPages = 0
    val resolvedStatic = AtomicInteger()
    val resolvedYtdlp = AtomicInteger()
    val trustedYtdlp = AtomicInteger()
    val resolvedWebView = AtomicInteger()
    val failed = AtomicInteger()
    @Volatile var ytdlp = "não usado"
    @Volatile var found = 0
    private val t0 = System.currentTimeMillis()

    fun code(c: Int) { status.getOrPut(c) { AtomicInteger() }.incrementAndGet() }
    fun reason(r: String) { reasons.getOrPut(r) { AtomicInteger() }.incrementAndGet() }
    fun fail(page: String, why: String) { failed.incrementAndGet(); reason(why); note("falhou ${hash(page)}: $why") }
    fun note(s: String) { if (events.size < 60) events += "+" + ((System.currentTimeMillis() - t0) / 100) / 10.0 + "s " + s }

    fun report(): String = buildString {
        appendLine("Baixar Pro Telegram v${br.geracaoai.baixartg.BuildConfig.VERSION_NAME} — diagnóstico")
        appendLine("site: ${site.ifBlank { "-" }}  modo: $mode  tempo: ${(System.currentTimeMillis() - t0) / 1000}s")
        appendLine("HTTP: " + status.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}×${it.value.get()}" }.ifBlank { "-" })
        appendLine("anti-bot/desafio: ${if (challenge) "SIM" else "não"}  navegador oculto: ${if (webviewAvailable) "ok" else "indisponível"}")
        appendLine("cards: estático=${if (staticCards < 0) "-" else staticCards}  navegador=${if (webCards < 0) "-" else webCards}")
        if (searchMethod.isNotBlank() || searchRequests > 0)
            appendLine("busca: $searchMethod  pedidos=$searchRequests  págs.busca=$searchPages  págs.tag/categoria=$taxonomyPages  resultados=$resultPages")
        appendLine("resolvidos: estático=${resolvedStatic.get()} yt-dlp=${resolvedYtdlp.get()} (+${trustedYtdlp.get()} confiados) navegador=${resolvedWebView.get()} falhas=${failed.get()}")
        appendLine("yt-dlp: $ytdlp")
        appendLine("vídeos na lista: $found")
        if (reasons.isNotEmpty()) appendLine("motivos: " + reasons.entries.joinToString("; ") { "${it.key}×${it.value.get()}" })
        if (events.isNotEmpty()) { appendLine("eventos:"); synchronized(events) { events.forEach { appendLine("  $it") } } }
    }

    companion object {
        fun hash(u: String): String {
            val host = Extract.host(u)
            val d = MessageDigest.getInstance("SHA-256").digest(u.toByteArray())
            val h = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            fun hx(b: ByteArray, n: Int) = b.take(n).joinToString("") { "%02x".format(it) }
            return "#" + hx(h, 3) + "/" + hx(d, 3)
        }
    }
}
