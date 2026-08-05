package com.mandar.echo.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.mandar.echo.MainActivity
import com.mandar.echo.R

object Notifications {

    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_SUMMARY = "summary"

    const val ID_RECORDING = 1001
    const val ID_STATUS = 1002
    const val ID_SUMMARY = 1003
    const val ID_RESUME = 1004

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                context.getString(R.string.notif_channel_recording),
                // LOW: permanently visible but silent. It must never buzz.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_recording_desc)
                setShowBadge(false)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY,
                context.getString(R.string.notif_channel_summary),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notif_channel_summary_desc) }
        )
    }

    fun contentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun recording(context: Context, title: String, body: String): android.app.Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    fun status(context: Context, title: String, body: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun summaryReady(context: Context, headline: String, body: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle(headline)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(id, notification) }
    }
}
