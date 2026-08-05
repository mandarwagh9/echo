package com.mandar.echo

import android.app.Application
import com.mandar.echo.audio.Notifications
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.EchoSettings
import com.mandar.echo.stt.ModelManager
import com.mandar.echo.stt.TranscriptionPipeline
import com.mandar.echo.stt.WhisperEngine
import com.mandar.echo.summary.SummaryEngine
import com.mandar.echo.summary.SummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual composition root. The object graph is small and entirely singleton, so a
 * DI framework would add build time and indirection without buying anything.
 */
class EchoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: EchoDatabase by lazy { EchoDatabase.get(this) }
    val settings: EchoSettings by lazy { EchoSettings(this) }
    val models: ModelManager by lazy { ModelManager(this) }
    val whisper: WhisperEngine by lazy { WhisperEngine() }
    val summaryEngine: SummaryEngine by lazy { SummaryEngine(this, db) }

    val pipeline: TranscriptionPipeline by lazy {
        TranscriptionPipeline(this, db, settings, models, whisper)
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        appScope.launch {
            // Re-arm on every launch so a dropped or cancelled alarm is self-healing.
            SummaryScheduler.scheduleNext(this@EchoApp, settings.current())
        }
    }
}
