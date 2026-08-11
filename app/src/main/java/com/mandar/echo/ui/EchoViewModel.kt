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

    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

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
