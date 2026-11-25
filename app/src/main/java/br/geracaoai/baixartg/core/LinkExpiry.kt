package br.geracaoai.baixartg.core

/** Lê a data de vencimento de links temporários (?expires=1732500000, ?e=..., ?exp=...). */
object LinkExpiry {
    private val NAMES = Regex("^(e|exp|expire|expires|expiry|expiration|validto|valid|deadline|until|end)$")

    /** Momento (ms) em que o link vence, ou null se não tem campo de validade reconhecível. */
    fun expiresAt(url: String): Long? {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return null
        for (p in q.split('&')) {
            val k = p.substringBefore('=').lowercase()
            if (!NAMES.matches(k)) continue
            val n = p.substringAfter('=', "").toLongOrNull() ?: continue
            val ms = when { n in 1_000_000_000L..9_999_999_999L -> n * 1000; n in 1_000_000_000_000L..9_999_999_999_999L -> n; else -> continue }
            if (ms > 1_500_000_000_000L) return ms
        }
        return null
    }

    /** Vencido ou vence em menos de [marginMs]. */
    fun isExpired(url: String, marginMs: Long = 20_000): Boolean {
        val at = expiresAt(url) ?: return false
        return at - System.currentTimeMillis() < marginMs
    }
}
