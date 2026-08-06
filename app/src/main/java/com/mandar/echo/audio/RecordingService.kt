package com.mandar.echo.audio

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.mandar.echo.EchoApp
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.summary.SummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "RecordingService"

/** Warn once the backlog passes roughly two hours of un-transcribed audio. */
private const val BACKLOG_WARN_CHUNKS = 12

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.mandar.echo.START"
        const val ACTION_STOP = "com.mandar.echo.STOP"
        const val ACTION_GENERATE_SUMMARY = "com.mandar.echo.GENERATE_SUMMARY"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: EchoApp

    private var chunker: AudioChunker? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var restartAttempt = 0
    @Volatile private var stopping = false
    @Volatile private var diskPaused = false

    /** onStartCommand can fire many times; the monitor loops must only ever start once. */
    @Volatile private var monitorsStarted = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as EchoApp
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Woken purely to write the summary, with no capture running: promote as
        // dataSync, because a background start with the microphone type is refused
        // on Android 14+.
        val summaryOnly = intent?.action == ACTION_GENERATE_SUMMARY &&
            !EchoServiceState.recording.value

        // Must happen fast and unconditionally, or Android kills us with an ANR.
        if (!promoteToForeground(
                title = if (summaryOnly) "Writing your summary" else "Starting",
                body = if (summaryOnly) "One moment" else "Preparing to listen",
                useMicrophoneType = !summaryOnly,
            )
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { app.settings.setRecordingEnabled(false) }
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_GENERATE_SUMMARY -> {
                // Runs on the already-alive foreground service, so Doze cannot
                // defer it the way it would defer a WorkManager job at 23:00.
                val wasRecording = EchoServiceState.recording.value
                SummaryScheduler.scheduleNext(this, runBlocking { app.settings.current() })

                scope.launch {
                    runCatching { app.summaryEngine.generateAndNotifyForToday() }
                        .onFailure { Log.e(TAG, "summary generation failed", it) }
                    // If we were only woken to write the summary, do not linger as a
                    // foreground service showing a notification that claims we are
                    // recording when we are not.
                    if (!wasRecording) shutdown()
                }
                if (!wasRecording) return START_NOT_STICKY
            }
            else -> Unit
        }

        if (chunker?.isRunning != true) beginCapture()
        app.pipeline.start(scope)
        startMonitors()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---- capture ----------------------------------------------------------

    private fun beginCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted")
            EchoServiceState.setPaused("Microphone permission not granted")
            updateNotification("Paused", "Microphone permission needed")
            return
        }

        if (freeBytes() < DiskSpace.MIN_FREE_BYTES) {
            diskPaused = true
            EchoServiceState.setPaused("Storage almost full")
            updateNotification("Paused", "Free up space to resume recording")
            return
        }

        acquireWakeLock()

        val cfg = runBlocking { app.settings.current() }
        val dir = File(filesDir, "chunks").apply { mkdirs() }

        val chunkSamples = AudioFormatSpec.SAMPLE_RATE.toLong() * 60L * cfg.chunkMinutes

        chunker = AudioChunker(
            outputDir = dir,
            audioSource = cfg.audioSource,
            chunkSamples = chunkSamples,
            callbacks = object : AudioChunker.Callbacks {

                override fun onChunkStarted(file: File, startedAtMs: Long): Long {
                    EchoServiceState.setChunkStart(startedAtMs)
                    // Called on the writer thread; a single insert is fast and any
                    // jitter is absorbed by the handoff queue rather than the mic.
                    return runBlocking {
                        app.db.chunkDao().insert(
                            ChunkEntity(
                                startedAt = startedAtMs,
                                filePath = file.absolutePath,
                                status = ChunkStatus.RECORDING,
                            )
                        )
                    }
                }

                override fun onChunkClosed(
                    chunkId: Long,
                    file: File,
                    sampleCount: Long,
                    endedAtMs: Long,
                ) {
                    runBlocking {
                        app.db.chunkDao().closeChunk(
                            id = chunkId,
                            endedAt = endedAtMs,
                            sampleCount = sampleCount,
                            status = ChunkStatus.PENDING,
                        )
                    }
                    Log.i(TAG, "closed chunk $chunkId (${sampleCount} samples)")
                }

                override fun onLevel(rms: Float) = EchoServiceState.setLevel(rms)

                override fun onFatalError(t: Throwable) {
                    Log.e(TAG, "capture fatal", t)
                    EchoServiceState.setPaused(t.message ?: "Recording failed")
                    scheduleRestart()
                }

                override fun onCaptureLost(reason: String) {
                    Log.w(TAG, "capture lost: $reason")
                    EchoServiceState.setPaused(reason)
                    scheduleRestart()
                }
            },
        ).also { it.start() }

        if (chunker?.isRunning == true) {
            restartAttempt = 0
            EchoServiceState.setRecording(true)
            EchoServiceState.setPaused(null)
            EchoServiceState.setSessionStart(System.currentTimeMillis())
            updateNotification("Listening", "Since ${timeFmt.format(System.currentTimeMillis())}")
        }
    }

    /**
     * The mic is exclusive on Android: a call, the assistant, or another recorder
     * takes it away without warning. Back off and keep trying rather than dying.
     */
    private fun scheduleRestart() {
        if (stopping) return
        EchoServiceState.setRecording(false)
        chunker?.stop()
        chunker = null

        val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        val wait = delays[restartAttempt.coerceAtMost(delays.lastIndex)]
        restartAttempt++

        updateNotification("Paused", "Microphone unavailable — retrying")
        scope.launch {
            delay(wait)
            if (stopping) return@launch
            if (!app.settings.current().recordingEnabled) return@launch
            Log.i(TAG, "retrying capture (attempt $restartAttempt)")
            beginCapture()
        }
    }

    private fun startMonitors() {
        if (monitorsStarted) return
        monitorsStarted = true

        scope.launch {
            while (true) {
                delay(15_000)
                if (stopping) return@launch

                val free = freeBytes()
                EchoServiceState.setFreeBytes(free)
                chunker?.let { EchoServiceState.setDropped(it.dropped) }

                if (!diskPaused && free < DiskSpace.MIN_FREE_BYTES) {
                    Log.w(TAG, "pausing: low storage ($free bytes)")
                    diskPaused = true
                    chunker?.stop()
                    chunker = null
                    EchoServiceState.setRecording(false)
                    EchoServiceState.setPaused("Storage almost full")
                    updateNotification("Paused", "Free up space to resume recording")
                    Notifications.notify(
                        this@RecordingService, Notifications.ID_STATUS,
                        Notifications.status(
                            this@RecordingService,
                            "Recording paused",
                            "Echo stopped recording because storage is nearly full.",
                        ),
                    )
                } else if (diskPaused && free > DiskSpace.RESUME_FREE_BYTES) {
                    Log.i(TAG, "resuming: storage recovered")
                    diskPaused = false
                    if (app.settings.current().recordingEnabled) beginCapture()
                }

                val backlog = runCatching { app.db.chunkDao().pendingCountNow() }.getOrDefault(0)
                if (EchoServiceState.recording.value) {
                    val since = EchoServiceState.sessionStartedAt.value
                    val suffix = when {
                        backlog > BACKLOG_WARN_CHUNKS -> " · $backlog chunks queued"
                        backlog > 0 -> " · transcribing $backlog"
                        else -> ""
                    }
                    updateNotification(
                        "Listening",
                        "Since ${timeFmt.format(since ?: System.currentTimeMillis())}$suffix",
                    )
                }
            }
        }

        // Keeps the whisper progress bar in the UI moving.
        scope.launch {
            while (true) {
                delay(500)
                app.pipeline.refreshProgress()
            }
        }
    }

    private fun shutdown() {
        stopping = true
        EchoServiceState.setRecording(false)
        EchoServiceState.setSessionStart(null)
        EchoServiceState.setChunkStart(null)
        chunker?.stop()
        chunker = null
        app.pipeline.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- plumbing ---------------------------------------------------------

    /** @return false if Android refused the promotion, in which case the caller must stop. */
    private fun promoteToForeground(
        title: String,
        body: String,
        useMicrophoneType: Boolean,
    ): Boolean = try {
        val notification = Notifications.recording(this, title, body)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifications.ID_RECORDING,
                notification,
                if (useMicrophoneType) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.ID_RECORDING, notification)
        }
        true
    } catch (t: Throwable) {
        // Better to exit cleanly than to crash-loop on a system-initiated restart
        // that lands while the app has no valid foreground-start context.
        Log.e(TAG, "could not enter foreground", t)
        false
    }

    private fun updateNotification(title: String, body: String) {
        Notifications.notify(this, Notifications.ID_RECORDING, Notifications.recording(this, title, body))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo::capture").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun freeBytes(): Long = DiskSpace.freeBytes(this)
}
