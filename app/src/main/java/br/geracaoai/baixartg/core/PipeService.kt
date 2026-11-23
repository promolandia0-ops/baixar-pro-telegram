package br.geracaoai.baixartg.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.conflate
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import br.geracaoai.baixartg.R
import br.geracaoai.baixartg.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** Serviço em primeiro plano da fila de envio: tela apagada / app em segundo plano continuam enviando. */
class PipeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wl: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    private fun goForeground(special: Boolean) {
        val type = if (special && Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try { startForeground(ID, notif("Fila de envio…", 0, 0, false), type) }
        catch (e: Exception) { if (!special) try { startForeground(ID, notif("Fila de envio…", 0, 0, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) } catch (_: Exception) {} }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> { Pipeline.pause() }
            RESUME -> { getSystemService(NotificationManager::class.java).cancel(ID + 1); Pipeline.resume() }
        }
        goForeground(intent?.getBooleanExtra("special", false) == true)
        if (wl == null) wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baixartg:queue").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        if (wifi == null) wifi = try {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL, "baixartg:queue").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }
        if (intent?.action != PAUSE) Pipeline.start()
        scope.coroutineContext.cancelChildren()
        scope.launch(Dispatchers.Default) {
            // fora da thread principal e no máximo ~1 atualização por segundo (a fila emite muito com 1000+ itens)
            combine(Pipeline.items, Pipeline.paused, Pipeline.running) { a, b, c -> Triple(a, b, c) }
                .conflate().onEach { kotlinx.coroutines.delay(1_000) }.flowOn(Dispatchers.Default).collect { (l, paused, running) ->
                val nm = getSystemService(NotificationManager::class.java)
                val done = l.count { it.st == Pipeline.PS.DONE }; val fail = l.count { it.st == Pipeline.PS.FAIL }
                val total = l.size
                if (!running && !paused) {
                    if (l.all { Pipeline.isFinal(it.st) }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        nm.notify(ID + 1, Notification.Builder(this@PipeService, WorkService.CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Fila concluída")
                            .setContentText("$done de $total enviados" + (if (fail > 0) " • $fail falharam" else "")).setAutoCancel(true).setContentIntent(openIntent()).build())
                        stopSelf()
                    }
                    return@collect
                }
                if (paused && !running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    nm.notify(ID + 1, notif("Pausado • $done de $total enviados" + (if (fail > 0) " • $fail falhas" else ""), total, done + fail, true).also { it.flags = it.flags and Notification.FLAG_ONGOING_EVENT.inv() })
                    stopSelf()
                    return@collect
                }
                val cur = l.firstOrNull { it.st == Pipeline.PS.UP } ?: l.firstOrNull { it.st == Pipeline.PS.DOWN }
                nm.notify(ID, notif("$done de $total enviados" + (if (fail > 0) " • $fail falhas" else "") + (cur?.let { " • " + it.title.take(40) } ?: ""), total, done + fail, false))
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        // limite de tempo do tipo dataSync (Android 15): continua como serviço de uso especial
        goForeground(true)
    }

    private fun openIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("tab", "queue"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notif(text: String, max: Int, p: Int, paused: Boolean): Notification {
        val act = if (paused) Intent(this, PipeService::class.java).setAction(RESUME) else Intent(this, PipeService::class.java).setAction(PAUSE)
        val pi = PendingIntent.getService(this, if (paused) 3 else 2, act, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, WorkService.CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Enviando para o Telegram").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openIntent()).setProgress(max, p, max == 0)
            .addAction(Notification.Action.Builder(null, if (paused) "Retomar" else "Pausar", pi).build()).build()
    }

    override fun onDestroy() {
        scope.cancel()
        wl?.let { if (it.isHeld) it.release() }; wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ID = 17; const val PAUSE = "pause"; const val RESUME = "resume"
        fun start(c: Context, special: Boolean = false) {
            try { c.startForegroundService(Intent(c, PipeService::class.java).putExtra("special", special)) } catch (_: Exception) {}
        }
    }
}

/** Depois de reiniciar o celular (ou atualizar o app), retoma a fila salva. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Pipeline.hasWork()) PipeService.start(c, special = true)
    }
}
