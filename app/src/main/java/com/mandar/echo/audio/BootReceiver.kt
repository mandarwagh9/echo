package com.mandar.echo.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mandar.echo.EchoApp
import com.mandar.echo.summary.SummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "BootReceiver"

/**
 * Android does not allow a *microphone* foreground service to be started from
 * BOOT_COMPLETED — mic and camera are "while-in-use" permissions and are
 * explicitly excluded from the background-start exemptions on API 31+.
 *
 * So we do the only compliant thing: re-arm the summary alarm (which is allowed),
 * and post a high-visibility notification the user taps to resume listening.
 * Pretending we can auto-resume would just fail silently after every reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        val app = context.applicationContext as? EchoApp
        if (app == null) { pending.finish(); return }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Notifications.createChannels(context)
                val settings = app.settings.current()
                SummaryScheduler.scheduleNext(context, settings)

                if (settings.recordingEnabled) {
                    Log.i(TAG, "recording was enabled before reboot; prompting to resume")
                    Notifications.notify(
                        context,
                        Notifications.ID_RESUME,
                        Notifications.status(
                            context,
                            "Echo is not listening",
                            "Android does not let apps restart microphone recording " +
                                "automatically after a reboot. Tap to resume.",
                        ),
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "boot handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
