package br.geracaoai.baixartg

import android.content.Context
import android.content.SharedPreferences
import br.geracaoai.baixartg.core.Downloader

object Prefs {
    private lateinit var p: SharedPreferences
    fun init(c: Context) { p = c.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var apiId: Int get() = p.getInt("api_id", 0); set(v) = p.edit().putInt("api_id", v).apply()
    var apiHash: String get() = p.getString("api_hash", "")!!; set(v) = p.edit().putString("api_hash", v).apply()
    var lastError: String get() = p.getString("err", "")!!; set(v) = p.edit().putString("err", v).apply()
    var server: String get() = p.getString("server", Downloader.DEFAULT_SERVER)!!; set(v) = p.edit().putString("server", v).apply()
    var depth: Int get() = p.getInt("depth", 10); set(v) = p.edit().putInt("depth", v).apply()
    var maxPages: Int get() = p.getInt("pages", 500); set(v) = p.edit().putInt("pages", v).apply()
    var probe: Boolean get() = p.getBoolean("probe", false); set(v) = p.edit().putBoolean("probe", v).apply()
    var parDown: Int get() = p.getInt("pdown", 3); set(v) = p.edit().putInt("pdown", v).apply()
    var parUp: Int get() = p.getInt("pup", 2); set(v) = p.edit().putInt("pup", v).apply()
    var synced: Boolean get() = p.getBoolean("synced", false); set(v) = p.edit().putBoolean("synced", v).apply()
    var botToken: String get() = p.getString("bot", "")!!; set(v) = p.edit().putString("bot", v).apply()
    /** 0 = original (máxima). Senão altura máxima (só afeta streams HLS com várias qualidades). */
    var quality: Int get() = p.getInt("quality", 0); set(v) = p.edit().putInt("quality", v).apply()
    var autoSend: Boolean get() = p.getBoolean("auto", true); set(v) = p.edit().putBoolean("auto", v).apply()
    var lastUrl: String get() = p.getString("url", "")!!; set(v) = p.edit().putString("url", v).apply()
}
