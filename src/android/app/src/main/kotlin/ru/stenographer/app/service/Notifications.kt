package ru.stenographer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import ru.stenographer.app.R

/** Каналы уведомлений: запись, обработка и «готово». */
object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_PROCESSING = "processing"
    const val CHANNEL_DONE = "done"

    const val ID_RECORDING = 1
    const val ID_PROCESSING = 2
    const val ID_DONE = 3

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_PROCESSING, context.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun builder(context: Context, channel: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        return b.setSmallIcon(R.drawable.ic_notification)
    }
}
