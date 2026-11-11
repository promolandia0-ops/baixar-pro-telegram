package br.geracaoai.baixartg

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import br.geracaoai.baixartg.core.Jobs
import br.geracaoai.baixartg.core.WorkService
import br.geracaoai.baixartg.tg.Td

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this); Jobs.init(this)
        try { br.geracaoai.baixartg.core.Net.userAgent = android.webkit.WebSettings.getDefaultUserAgent(this) } catch (_: Exception) {}
        try { br.geracaoai.baixartg.web.WebCookies.init(this); br.geracaoai.baixartg.core.Net.cookies = br.geracaoai.baixartg.web.WebCookies } catch (_: Exception) {}
        try { br.geracaoai.baixartg.web.AdBlock.warm(this) } catch (_: Exception) {}
        br.geracaoai.baixartg.web.MegaStore.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(WorkService.CHANNEL, "Downloads e envios", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
    }
}
