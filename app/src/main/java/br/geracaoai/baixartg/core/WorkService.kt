package br.geracaoai.baixartg.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import br.geracaoai.baixartg.R
import br.geracaoai.baixartg.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Mantém o app vivo (tela apagada) enquanto busca/baixa/envia. Notificação com botão Parar. */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { Jobs.stop(); return START_NOT_STICKY }
        startForeground(ID, notif("Iniciando…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baixartg:work").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            combine(Jobs.phase, Jobs.crawl, Jobs.items, Jobs.status) { ph, c, it, st -> arrayOf(ph, c, it, st) }.collect { a ->
                val ph = a[0] as Phase
                if (ph != Phase.CRAWLING && ph != Phase.RUNNING) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collect }
                val nm = getSystemService(NotificationManager::class.java)
                if (ph == Phase.CRAWLING) { val c = a[1] as CrawlProgress; nm.notify(ID, notif("Procurando vídeos: ${c.pagesDone} páginas, ${c.found} vídeos", 0, 0)) }
                else {
                    @Suppress("UNCHECKED_CAST") val l = (a[2] as List<Item>).filter { it.sel }
                    val done = l.count { it.st == St.DONE || it.st == St.ERR }
                    nm.notify(ID, notif("${a[3]} • $done/${l.size} concluídos", l.size, done))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String, max: Int, p: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WorkService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Baixar pro Telegram").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setProgress(max, p, max == 0)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build()).build()
    }

    override fun onDestroy() { scope.cancel(); wl?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    companion object {
        const val CHANNEL = "work"; const val ID = 7; const val STOP = "stop"
        fun start(c: Context) { try { c.startForegroundService(Intent(c, WorkService::class.java)) } catch (_: Exception) {} }
    }
}
