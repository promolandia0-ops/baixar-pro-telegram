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
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(WorkService.CHANNEL, "Downloads e envios", NotificationManager.IMPORTANCE_LOW))
        Td.init(this)
    }
}
