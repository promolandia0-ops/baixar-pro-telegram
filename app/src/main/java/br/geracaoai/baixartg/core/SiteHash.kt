package br.geracaoai.baixartg.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Código curto irreversível (ex.: cdn#7f3a) com salt aleatório por instalação.
 * Nunca devolve o valor original.
 */
object SiteHash {
    @Volatile private var salt: String = "test-salt"

    @Synchronized fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("site_analyze", Context.MODE_PRIVATE)
        var s = p.getString("salt", "") ?: ""
        if (s.isBlank()) {
            val raw = ByteArray(32)
            SecureRandom().nextBytes(raw)
            s = raw.joinToString("") { "%02x".format(it) }
            p.edit().putString("salt", s).apply()
        }
        salt = s
    }

    /** Só testes: salt fixo e conhecido, para asserts de estabilidade do hash. */
    fun initForTest(fixedSalt: String) { salt = fixedSalt }

    fun hostOf(url: String): String = try {
        val u = java.net.URI(url.trim())
        (u.host ?: "").lowercase().removePrefix("www.").trimEnd('.')
    } catch (_: Exception) { "" }

    fun sameHost(a: String, b: String): Boolean {
        val ha = hostOf(a); val hb = hostOf(b)
        return ha.isNotBlank() && ha == hb
    }

    fun short(kind: String, raw: String): String {
        val norm = hostOf(raw).ifBlank { raw.trim().lowercase() }
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest("$salt|$kind|$norm".toByteArray(Charsets.UTF_8))
        val hex = "%02x%02x".format(dig[0], dig[1])
        return "$kind#$hex"
    }
}
