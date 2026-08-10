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

    /**
     * "Echo should be recording and is not."
     *
     * Its own channel because it is the only notification in the app that is
     * allowed to be loud, and it has to be: on 2026-08-09 the resume prompt was
     * posted on the silent default-importance Status channel at 10:20 and went
     * unnoticed for seven hours, which is seven hours of a 24/7 recorder not
     * recording. A separate channel also means the user can quiet Status without
     * quieting this.
     */
    const val CHANNEL_ALERT = "not_listening"

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
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.notif_channel_alert),
                // HIGH, so it heads-up. The only channel here that may interrupt.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_alert_desc)
                enableVibration(true)
            }
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

    /** Set on the resume intent so [MainActivity] knows to start capture on open. */
    const val EXTRA_RESUME = "com.mandar.echo.RESUME"

    /**
     * The recorder should be running and is not — after a reboot, or after the
     * mic was taken and not given back.
     *
     * Both the tap and the action open the app rather than poking the service
     * directly: a `microphone` foreground service cannot be started from the
     * background on Android 14+, so the only compliant resume path is through a
     * visible activity. The button exists because "tap this text" is not an
     * affordance anyone reads at a glance in a crowded shade.
     */
    fun notListening(context: Context, title: String, body: String): android.app.Notification {
        val resume = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RESUME, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_echo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // Ongoing, and explicitly not auto-cancelling. The old one could be
            // swiped away by accident, after which nothing anywhere said the
            // recorder was off. It is cleared when capture actually resumes.
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(resume)
            .addAction(0, "Resume recording", resume)
            .build()
    }

    fun cancel(context: Context, id: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(id) }
    }

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
