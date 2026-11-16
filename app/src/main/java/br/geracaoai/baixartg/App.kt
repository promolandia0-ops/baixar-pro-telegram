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
        br.geracaoai.baixartg.web.MegaStore.init(this)
        br.geracaoai.baixartg.web.AdBlock.enabled = Prefs.strongBlock; br.geracaoai.baixartg.web.AdBlock.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(WorkService.CHANNEL, "Downloads e envios", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
        br.geracaoai.baixartg.core.Pipeline.init(this)
        br.geracaoai.baixartg.ui.BrowserStore.init(this)
        br.geracaoai.baixartg.core.Dedupe.init(this)
        if (br.geracaoai.baixartg.core.Dedupe.hasPendingDelete()) br.geracaoai.baixartg.core.Dedupe.resumeDelete()
        if (br.geracaoai.baixartg.core.Pipeline.hasWork()) br.geracaoai.baixartg.core.Pipeline.start()
    }
}
