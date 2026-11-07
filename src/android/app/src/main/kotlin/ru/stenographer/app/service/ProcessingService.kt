package ru.stenographer.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.stenographer.app.MainActivity
import ru.stenographer.app.R
import ru.stenographer.app.StenographerApp

/**
 * Держит процесс живым, пока в очереди есть записи: без foreground-сервиса
 * Android может остановить обработку через минуту после сворачивания.
 * Сам ничего не обрабатывает, только показывает прогресс в шторке.
 */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(0)
        val container = (application as StenographerApp).container
        scope.launch {
            combine(container.queue.pending, container.queue.live) { pending, live -> pending to live }
                .collect { (pending, live) ->
                    if (pending == 0) {
                        notifyDone()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else if (live != null) {
                        startInForeground((live.overall * 100).toInt())
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startInForeground(percent: Int) {
        val n = Notifications.builder(this, Notifications.CHANNEL_PROCESSING)
            .setContentTitle(getString(R.string.notif_processing))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_PROCESSING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_PROCESSING, n)
        }
    }

    private fun notifyDone() {
        val n = Notifications.builder(this, Notifications.CHANNEL_DONE)
            .setContentTitle(getString(R.string.notif_done))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_DONE, n)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, ProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
}
