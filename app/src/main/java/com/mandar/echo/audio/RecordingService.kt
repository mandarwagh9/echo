package com.mandar.echo.audio

import android.Manifest
import android.annotation.SuppressLint
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "RecordingService"

/** Warn once the backlog passes roughly two hours of un-transcribed audio. */
private const val BACKLOG_WARN_CHUNKS = 12

/**
 * How often the housekeeping loop runs.
 *
 * Was 15 s. Nothing it checks moves at that speed — free space, the pending
 * backlog and the dropped-sample counter are all slow — and at 15 s it was the
 * single most frequent scheduled wakeup in a service designed to run for days.
 */
private const val MONITOR_INTERVAL_MS = 60_000L

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

    /** Last (title, body) actually posted, so identical re-posts are skipped. */
    private var lastNotification: Pair<String, String>? = null

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

                // Read on the reader thread, so it must stay a plain volatile
                // read. It spares that thread the RMS pass and the UI layer a
                // 64-element list allocation every second of a screen-off day.
                override fun wantsLevels(): Boolean = EchoServiceState.uiVisible.value

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
            // Acquired here rather than before start(): a wake lock taken on the
            // way to a capture that never began is held until shutdown() with
            // nothing recording behind it.
            acquireWakeLock()
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

        // The mic is gone, so the indefinite lock is now pinning the CPU awake for
        // nothing. It cannot simply be dropped either: coroutine delay() does not
        // wake a suspended device, and a recorder that resumes at the next
        // maintenance window instead of in 2 s loses the audio in between. So the
        // lock is re-taken bounded by the backoff itself, which cannot leak.
        releaseWakeLock()
        acquireWakeLock(timeoutMs = wait + 5_000)

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
            var tick = 0
            while (true) {
                delay(MONITOR_INTERVAL_MS)
                if (stopping) return@launch

                // statfs is a syscall against the filesystem, and free space does
                // not move fast: capture writes 32 KB/s, so a minute of drift is
                // under 2 MB against a threshold with far more headroom than that.
                val free = if (tick++ % 2 == 0 || diskPaused) {
                    freeBytes().also { EchoServiceState.setFreeBytes(it) }
                } else {
                    EchoServiceState.freeBytes.value
                }
                chunker?.let { EchoServiceState.setDropped(it.dropped) }

                if (!diskPaused && free < DiskSpace.MIN_FREE_BYTES) {
                    Log.w(TAG, "pausing: low storage ($free bytes)")
                    diskPaused = true
                    chunker?.stop()
                    chunker = null
                    // Nothing is being captured and nothing will be until the user
                    // frees space, which is not a 30-second wait. Sleeping is
                    // correct here; the loop resumes on the next device wake.
                    releaseWakeLock()
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

        // Keeps the whisper progress bar moving — and nothing else. It used to run
        // at 2 Hz for the life of the service whether or not a transcription was
        // running and whether or not anyone was looking, which is ~172,000 wakeups
        // a day to animate a bar on a screen that is off. Both flows are
        // conflated first so the pump suspends outright rather than polling to
        // discover it has nothing to do.
        scope.launch {
            combine(
                EchoServiceState.uiVisible,
                app.pipeline.state,
            ) { visible, pipeline -> visible && pipeline.busy }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    while (true) {
                        delay(500)
                        app.pipeline.refreshProgress()
                    }
                }
        }
    }

    private fun shutdown() {
        stopping = true
        // The notification is about to be removed, so the memo must not suppress
        // an identical string on the next start.
        lastNotification = null
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
        lastNotification = null
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

    /**
     * Posts the ongoing notification, skipping the post when nothing about it
     * changed.
     *
     * The monitor loop calls this on every tick, and on a quiet day the string is
     * identical every time — each redundant post still built a Notification,
     * crossed a binder to the system server and re-rendered the shade row.
     */
    private fun updateNotification(title: String, body: String) {
        val next = title to body
        if (next == lastNotification) return
        lastNotification = next
        Notifications.notify(this, Notifications.ID_RECORDING, Notifications.recording(this, title, body))
    }

    /**
     * Lint objects to the untimed branch, and for almost any app it would be
     * right. Here the untimed lock *is* the product: this is a recorder that is
     * meant to be holding the microphone open at 4 a.m., and a timeout on it
     * would be an alarm clock set to stop recording. What lint is actually
     * guarding against is a lock outliving the work, which is the bug that was
     * here and is now fixed a different way: the lock is taken only once capture
     * is confirmed running and dropped the moment it stops.
     *
     * @param timeoutMs when non-null, the lock releases itself after this long.
     *   Used for the restart backoff, where the alternative to a bounded lock is
     *   either a leak or a resume deferred to the next maintenance window.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(timeoutMs: Long? = null) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Echo::capture").apply {
            setReferenceCounted(false)
            if (timeoutMs == null) acquire() else acquire(timeoutMs)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun freeBytes(): Long = DiskSpace.freeBytes(this)
}
