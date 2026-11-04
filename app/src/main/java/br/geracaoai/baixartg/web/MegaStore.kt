package br.geracaoai.baixartg.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import br.geracaoai.baixartg.core.Mega
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda SÓ a sessão do MEGA (nunca a senha), criptografada com chave do Android Keystore. */
object MegaStore {
    private const val ALIAS = "mega_session"
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("mega", Context.MODE_PRIVATE)

    fun init(c: Context) { ctx = c.applicationContext; try { load()?.let { Mega.session = it } } catch (_: Exception) { clear() } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    fun save(s: Mega.Session) {
        val plain = JSONObject().put("sid", s.sid).put("mk", Mega.b64e(s.masterKey)).put("u", s.user).put("e", s.email).toString().toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("iv", Mega.b64e(c.iv)).putString("data", Mega.b64e(c.doFinal(plain))).apply()
    }

    private fun load(): Mega.Session? {
        val iv = prefs.getString("iv", null) ?: return null; val d = prefs.getString("data", null) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Mega.b64d(iv))) }
        val j = JSONObject(String(c.doFinal(Mega.b64d(d))))
        return Mega.Session(j.getString("sid"), Mega.b64d(j.getString("mk")), j.getString("u"), j.optString("e"))
    }

    fun clear() { prefs.edit().clear().apply() }
}
