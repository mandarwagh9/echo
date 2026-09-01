package com.mandar.echo.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mandar.echo.EchoApp
import com.mandar.echo.audio.EchoServiceState
import com.mandar.echo.audio.RecordingService
import com.mandar.echo.data.AudioHold
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.Settings
import com.mandar.echo.data.SttLanguage
import com.mandar.echo.data.SummaryEntity
import com.mandar.echo.stt.DownloadState
import com.mandar.echo.stt.WhisperModel
import com.mandar.echo.summary.SummaryScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private const val LEVEL_HISTORY = 64

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class EchoViewModel(application: Application) : AndroidViewModel(application) {

    private val echo = application as EchoApp
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val settings: StateFlow<Settings> = echo.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /**
     * Which of the two front doors to open, and whether that is known yet.
     *
     * [Launch.Undecided] is not a formality. `settings` is a StateFlow seeded
     * with `Settings()`, whose `onboardingComplete` is false, so reading the flag
     * directly shows the welcome screen for a frame on every single cold start,
     * to every user, forever. Worse, the flag is *absent* on any install that
     * predates it, which is every phone already running Echo: they would each be
     * dropped into first-run setup with their recordings still on disk, offered a
     * model they already have, and invited to leave the recorder switched off.
     *
     * So an install that already holds a model or any captured audio counts as
     * onboarded, and the flag is written to say so.
     */
    enum class Launch { Undecided, Onboarding, Ready }

    private val _launch = MutableStateFlow(Launch.Undecided)
    val launch: StateFlow<Launch> = _launch

    init {
        viewModelScope.launch {
            _launch.value = when {
                echo.settings.current().onboardingComplete -> Launch.Ready
                hasExistingData() -> {
                    echo.settings.setOnboardingComplete(true)
                    Launch.Ready
                }
                else -> Launch.Onboarding
            }
        }
    }

    private suspend fun hasExistingData(): Boolean {
        if (echo.models.installed().isNotEmpty()) return true
        return runCatching {
            echo.db.chunkDao().samplesBetween(0L, Long.MAX_VALUE)
        }.getOrDefault(0L) > 0L
    }

    val recording = EchoServiceState.recording
    val pausedReason = EchoServiceState.pausedReason
    val droppedSamples = EchoServiceState.droppedSamples
    val sessionStartedAt = EchoServiceState.sessionStartedAt
    val currentChunkStartedAt = EchoServiceState.currentChunkStartedAt
    val freeBytes = EchoServiceState.freeBytes

    val pipelineState = echo.pipeline.state
    val downloadState: StateFlow<DownloadState> = echo.models.state

    val pendingCount: StateFlow<Int> = echo.db.chunkDao().pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedChunks: StateFlow<List<ChunkEntity>> = echo.db.chunkDao().failedChunks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Chunks whose audio is being kept because a better transcript is still
     * possible. Without somewhere to show it the hold is a promise nothing keeps:
     * the audio is retained forever and the redo it was retained for never happens.
     */
    val redoableChunks: StateFlow<Int> = echo.db.chunkDao().redoableCount(AudioHold.REDOABLE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Chunks handed to the batch pipeline whose transcripts have not returned. */
    val awaitingRemote: StateFlow<Int> = echo.db.chunkDao().awaitingRemoteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    /**
     * Today, held apart from [selectedDate].
     *
     * Home always shows today and the day browser owns the date being read.
     * Sharing one date between them means opening yesterday silently blanks the
     * home screen, which reads as data loss rather than as navigation.
     */
    private val today = MutableStateFlow(LocalDate.now())

    val segmentsToday: StateFlow<List<SegmentEntity>> = today
        .flatMapLatest { date ->
            val (from, to) = date.bounds()
            echo.db.segmentDao().betweenFlow(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chunksToday: StateFlow<List<ChunkEntity>> = today
        .flatMapLatest { date ->
            val (from, to) = date.bounds()
            echo.db.chunkDao().chunksBetweenFlow(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val summaryToday: StateFlow<SummaryEntity?> = today
        .flatMapLatest { echo.db.summaryDao().forDayFlow(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Rolls [today] over when the app is opened after midnight. Cheap enough to
     * call from the UI's resume, and the alternative is a home screen that keeps
     * showing yesterday until the process is killed.
     */
    fun refreshToday() {
        val now = LocalDate.now()
        if (today.value != now) today.value = now
    }

    val segmentsForDay: StateFlow<List<SegmentEntity>> = _selectedDate
        .flatMapLatest { date ->
            val (from, to) = date.bounds()
            echo.db.segmentDao().betweenFlow(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chunksForDay: StateFlow<List<ChunkEntity>> = _selectedDate
        .flatMapLatest { date ->
            val (from, to) = date.bounds()
            echo.db.chunkDao().chunksBetweenFlow(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val summaryForDay: StateFlow<SummaryEntity?> = _selectedDate
        .flatMapLatest { echo.db.summaryDao().forDayFlow(it.toEpochDay()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val summaries: StateFlow<List<SummaryEntity>> = echo.db.summaryDao().recent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Days that hold a recording, newest first. Drives the day browser. */
    val recordedDays: StateFlow<List<LocalDate>> = echo.db.chunkDao()
        .recordedDays(zone.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L)
        .map { days -> days.map(LocalDate::ofEpochDay) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val searchResults: StateFlow<List<SegmentEntity>> = _query
        .debounce(220)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else echo.db.segmentDao().search(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Seed the storage figure immediately. It is otherwise only written by the
        // recording service's monitor loop, so Settings would report "0 B" free —
        // which reads as "disk full" — until recording had run at least once.
        EchoServiceState.setFreeBytes(currentFreeBytes())

        viewModelScope.launch {
            EchoServiceState.level.collect { value ->
                _levels.value = (_levels.value + value).takeLast(LEVEL_HISTORY)
            }
        }
    }

    private fun currentFreeBytes(): Long = runCatching {
        android.os.StatFs(getApplication<Application>().filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    private fun LocalDate.bounds(): Pair<Long, Long> {
        val from = atStartOfDay(zone).toInstant().toEpochMilli()
        val to = plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from to to
    }

    // ---- actions ----------------------------------------------------------

    fun selectDate(date: LocalDate) { _selectedDate.value = date }

    fun setQuery(value: String) { _query.value = value }

    fun toggleRecording(context: Context) {
        viewModelScope.launch {
            val enabled = !settings.value.recordingEnabled
            echo.settings.setRecordingEnabled(enabled)
            if (enabled) RecordingService.start(context) else RecordingService.stop(context)
        }
    }

    fun startRecording(context: Context) {
        viewModelScope.launch {
            echo.settings.setRecordingEnabled(true)
            RecordingService.start(context)
        }
    }

    // ---- first run --------------------------------------------------------

    fun completeOnboarding() {
        _launch.value = Launch.Ready
        viewModelScope.launch { echo.settings.setOnboardingComplete(true) }
    }

    /**
     * Whether Android will let Echo run outside its Doze restrictions.
     *
     * This is the single setting that decides whether a 24/7 recorder actually
     * records for 24 hours. Without the exemption the process is frozen during
     * Doze, capture stops some time after the screen goes off, and the user
     * wakes up to a night that was never recorded and no error explaining it.
     */
    fun batteryExemptionGranted(context: Context): Boolean {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * Opens the system dialog asking for that exemption.
     *
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the one that shows a
     * one-tap prompt. If the OEM has removed it (some do), fall back to the
     * settings list, which is a longer road but is always present.
     *
     * **This is why Echo cannot be published on Google Play.** Lint flags it
     * correctly: requesting the exemption directly violates Play's content
     * policy, which allows it only for a short list of app types a personal
     * journal is not on. The suppression records a decision rather than hiding a
     * problem. Echo is distributed as a sideloaded APK, and without the
     * exemption a 24/7 recorder simply stops recording overnight, which is not a
     * degraded version of the product but the absence of it.
     */
    @android.annotation.SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context): android.content.Intent {
        val direct = android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + context.packageName),
        )
        val resolvable = direct.resolveActivity(context.packageManager) != null
        return if (resolvable) {
            direct
        } else {
            android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            )
        }
    }

    fun modelsInstalled(): List<WhisperModel> = echo.models.installed()

    fun isInstalled(model: WhisperModel) = echo.models.isInstalled(model)

    fun downloadModel(model: WhisperModel) {
        viewModelScope.launch { echo.models.download(model) }
    }

    fun cancelDownload() = echo.models.cancel()

    fun deleteModel(model: WhisperModel) {
        viewModelScope.launch { echo.models.delete(model) }
    }

    fun setModel(model: WhisperModel) {
        viewModelScope.launch { echo.settings.setModel(model.fileName) }
    }

    fun setLanguage(language: SttLanguage) {
        viewModelScope.launch { echo.settings.setLanguage(language) }
    }

    fun setSttBackend(backend: com.mandar.echo.data.SttBackend) {
        viewModelScope.launch {
            echo.settings.setSttBackend(backend)
            echo.pipeline.onCloudSettingsChanged()
        }
    }

    /**
     * Blank url or key falls back to the value baked in at build time; see
     * [com.mandar.echo.data.EchoSettings.setSttServer].
     *
     * Clearing the halt here is the point of the whole screen: a bad key stops
     * the cloud path until the settings that could fix it change, and this is
     * that change.
     */
    fun setSttServer(url: String, apiKey: String) {
        viewModelScope.launch {
            echo.settings.setSttServer(url, apiKey)
            echo.pipeline.onCloudSettingsChanged()
        }
    }

    /** The batch pipeline's upload service. Blank falls back to the build default. */
    fun setUploadService(url: String, key: String) {
        viewModelScope.launch { echo.settings.setUploadService(url, key) }
    }

    /** What the build was compiled with, so the UI can say whether it is overridden. */
    fun buildDefaultServer(): Pair<String, String> =
        com.mandar.echo.BuildConfig.STT_URL.trim().trimEnd('/') to
            com.mandar.echo.BuildConfig.STT_KEY

    fun setAudioSource(source: Int) {
        viewModelScope.launch { echo.settings.setAudioSource(source) }
    }

    fun setChunkMinutes(minutes: Int) {
        viewModelScope.launch { echo.settings.setChunkMinutes(minutes) }
    }

    fun setSkipSilent(skip: Boolean) {
        viewModelScope.launch { echo.settings.setSkipSilent(skip) }
    }

    fun setKeepAudio(keep: Boolean) {
        viewModelScope.launch { echo.settings.setKeepAudio(keep) }
    }

    fun setSummaryTime(context: Context, hour: Int, minute: Int) {
        viewModelScope.launch {
            echo.settings.setSummaryTime(hour, minute)
            SummaryScheduler.scheduleNext(context, echo.settings.current())
        }
    }

    /** Builds the summary for the selected day immediately, without waiting for 11 PM. */
    fun generateSummaryNow() {
        viewModelScope.launch { echo.summaryEngine.generate(_selectedDate.value) }
    }

    fun retryFailedChunks() {
        viewModelScope.launch {
            // A failed chunk whose audio is gone can only fail again, and the retry
            // query skips it by design, so it would otherwise sit in the count for
            // ever with a button that provably cannot clear it.
            echo.db.chunkDao().discardFailedWithoutAudio()
            echo.db.chunkDao().retryAllFailed()
        }
    }

    /** Queues the held chunks to be transcribed again, against whatever backend is configured now. */
    fun redoHeldChunks() {
        viewModelScope.launch { echo.db.chunkDao().requeueRedoable(AudioHold.REDOABLE) }
    }

    fun whisperSystemInfo(): String = echo.whisper.systemInfo()

    fun deleteEverything(context: Context) {
        viewModelScope.launch {
            echo.settings.setRecordingEnabled(false)
            RecordingService.stop(context)
            echo.db.chunkDao().deleteAll()
            echo.db.summaryDao().deleteAll()
            java.io.File(context.filesDir, "chunks").listFiles()?.forEach { it.delete() }
        }
    }
}
