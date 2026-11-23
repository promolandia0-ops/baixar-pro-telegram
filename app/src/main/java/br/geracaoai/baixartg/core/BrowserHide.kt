package br.geracaoai.baixartg.core

/**
 * Política de esconder vídeos no painel do navegador.
 * Só esconde se o item está ATIVO na fila agora, ou se o envio ao Telegram foi confirmado (DONE / markSent).
 * FAIL, SKIP (repetido), CANCEL, ANALYZE e chaves só "já pedidas" (queued_keys) NÃO escondem.
 */
object BrowserHide {
    const val LOADING_TIMEOUT_MS = 60_000L

    enum class Reason { NONE, ACTIVE, SENT, FAILED_BEFORE, SKIPPED_BEFORE, CANCELLED_BEFORE }

    fun shouldHide(reason: Reason): Boolean = reason == Reason.ACTIVE || reason == Reason.SENT

    fun decide(activeInQueue: Boolean, confirmedSent: Boolean, finished: String? = null): Reason {
        if (activeInQueue) return Reason.ACTIVE
        if (confirmedSent) return Reason.SENT
        return when (finished) {
            "FAIL", "ANALYZE", "ANALYZING" -> Reason.FAILED_BEFORE
            "SKIP" -> Reason.SKIPPED_BEFORE
            "CANCEL" -> Reason.CANCELLED_BEFORE
            else -> Reason.NONE
        }
    }

    fun badge(reason: Reason): String? = when (reason) {
        Reason.FAILED_BEFORE -> "falhou antes"
        Reason.SKIPPED_BEFORE -> "repetido antes"
        Reason.CANCELLED_BEFORE -> "cancelado antes"
        else -> null
    }

    fun loadingTimedOut(startedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        startedAt > 0L && now - startedAt >= LOADING_TIMEOUT_MS
}
