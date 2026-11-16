package br.geracaoai.baixartg.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.geracaoai.baixartg.R
import br.geracaoai.baixartg.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Apaga os repetidos em segundo plano (tela apagada), com progresso; retoma se for interrompido. */
class DedupeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wl: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    private fun fg(special: Boolean) {
        val n = notif("Apagando repetidos…", 0, 0)
        try { startForeground(ID, n, if (special && Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: Exception) { try { startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (_: Exception) {} }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fg(false)
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baixartg:dedupe").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        if (job?.isActive != true) job = scope.launch {
            val prog = launch(Dispatchers.Main) {
                combine(Dedupe.delDone, Dedupe.delTotal, Dedupe.status) { a, b, c -> Triple(a, b, c) }.collect { (d, t, s) ->
                    getSystemService(NotificationManager::class.java).notify(ID, notif(s, t, d))
                }
            }
            try { Dedupe.runDelete() } finally {
                prog.cancel()
                withContext(NonCancellable + Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    getSystemService(NotificationManager::class.java).notify(ID + 1, Notification.Builder(this@DedupeService, WorkService.CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat).setContentTitle("Limpar repetidos").setContentText(Dedupe.status.value)
                        .setStyle(Notification.BigTextStyle().bigText(Dedupe.status.value)).setAutoCancel(true).build())
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) { fg(true) }

    private fun notif(text: String, max: Int, p: Int): Notification {
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, WorkService.CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Limpar repetidos").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setProgress(max, p, max == 0).build()
    }

    override fun onDestroy() { job?.cancel(); scope.cancel(); wl?.let { if (it.isHeld) it.release() }; super.onDestroy() }

    companion object {
        const val ID = 27
        fun start(c: Context) { try { c.startForegroundService(Intent(c, DedupeService::class.java)) } catch (_: Exception) {} }
    }
}
