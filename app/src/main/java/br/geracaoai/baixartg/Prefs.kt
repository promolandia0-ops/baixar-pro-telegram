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
    var strongBlock: Boolean get() = p.getBoolean("strong_block", true); set(v) = p.edit().putBoolean("strong_block", v).apply()
    /** "Fotos também": coleta fotos de verdade (nunca capas/miniaturas) numa seção separada. Desligado por padrão. */
    /** 0 = Só vídeos (padrão), 1 = Só fotos, 2 = Vídeos e fotos. (Antes: chave "photos" ligada = 2.) */
    var mediaMode: Int get() = p.getInt("media_mode", if (p.getBoolean("photos", false)) 2 else 0); set(v) = p.edit().putInt("media_mode", v).apply()
    var photos: Boolean get() = mediaMode != 0; set(v) { mediaMode = if (v) 2 else 0 }
    /** Enviar legenda (título/link) junto com a mídia. Padrão: desligado (só a mídia). */
    var visualDedupe: Boolean get() = p.getBoolean("visual_dedupe", true); set(v) = p.edit().putBoolean("visual_dedupe", v).apply()
    var replaceHigher: Boolean get() = p.getBoolean("replace_higher", false); set(v) = p.edit().putBoolean("replace_higher", v).apply()
    var sendCaption: Boolean get() = p.getBoolean("send_caption", false); set(v) = p.edit().putBoolean("send_caption", v).apply()
    var pipelineMigrated: Boolean get() = p.getBoolean("pipe_migrated", false); set(v) = p.edit().putBoolean("pipe_migrated", v).apply()
    var askedBattery: Boolean get() = p.getBoolean("asked_battery", false); set(v) = p.edit().putBoolean("asked_battery", v).apply()
    /** Última página aberta no Navegador do app. */
    var browserUrl: String get() = p.getString("browser_url", "")!!; set(v) = p.edit().putString("browser_url", v).apply()
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
