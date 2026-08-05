package com.mandar.echo.summary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mandar.echo.audio.RecordingService
import com.mandar.echo.data.Settings
import java.util.Calendar

private const val TAG = "SummaryScheduler"
private const val REQUEST_CODE = 7301

/**
 * Fires the daily summary at an exact wall-clock time.
 *
 * WorkManager is not usable here: periodic work has a ~15-minute flex window and
 * will not reliably land on 11 PM. An exact alarm does, and because
 * [RecordingService] is already a live foreground service, the work itself runs
 * there — so Doze cannot defer it the way it would defer a queued job.
 */
object SummaryScheduler {

    fun scheduleNext(context: Context, settings: Settings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextOccurrence(settings.summaryHour, settings.summaryMinute)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true

        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
            } else {
                // Degrades to a window rather than failing outright.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
                Log.w(TAG, "exact alarms unavailable; summary may run late")
            }
            Log.i(TAG, "next summary at ${java.util.Date(triggerAt)}")
        } catch (t: Throwable) {
            Log.e(TAG, "could not schedule summary alarm", t)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context))
    }

    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SummaryAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class SummaryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "summary alarm fired")
        // Hand straight to the foreground service; the alarm's temporary
        // allowlist window is enough to start/deliver to it even in Doze.
        val forward = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_GENERATE_SUMMARY)
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, forward) }
            .onFailure { Log.e(TAG, "could not deliver summary intent", it) }
    }
}
