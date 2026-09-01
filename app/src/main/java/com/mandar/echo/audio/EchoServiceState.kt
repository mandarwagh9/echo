package com.mandar.echo.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live state published by [RecordingService] for the UI to observe.
 *
 * A process-wide object rather than a bound-service connection: the UI and the
 * service always share one process here, and this keeps the Compose layer free of
 * binder lifecycle handling.
 */
object EchoServiceState {

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Non-null when capture is temporarily unavailable, with the reason. */
    private val _pausedReason = MutableStateFlow<String?>(null)
    val pausedReason: StateFlow<String?> = _pausedReason

    private val _droppedSamples = MutableStateFlow(0L)
    val droppedSamples: StateFlow<Long> = _droppedSamples

    private val _sessionStartedAt = MutableStateFlow<Long?>(null)
    val sessionStartedAt: StateFlow<Long?> = _sessionStartedAt

    private val _currentChunkStartedAt = MutableStateFlow<Long?>(null)
    val currentChunkStartedAt: StateFlow<Long?> = _currentChunkStartedAt

    private val _freeBytes = MutableStateFlow(0L)
    val freeBytes: StateFlow<Long> = _freeBytes

    /**
     * Whether a UI is actually on screen to look at any of this.
     *
     * Set from [com.mandar.echo.MainActivity]'s START/STOP, and read by the
     * service to decide whether work that exists purely to feed the interface is
     * worth doing at all. A 24/7 recorder spends nearly all of its life with the
     * screen off, so the level meter and the transcription progress bar are, in
     * the aggregate, the app's most expensive animations of nothing.
     */
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible

    internal fun setRecording(value: Boolean) { _recording.value = value }
    internal fun setLevel(value: Float) { _level.value = value }
    internal fun setPaused(reason: String?) { _pausedReason.value = reason }
    internal fun setDropped(value: Long) { _droppedSamples.value = value }
    internal fun setSessionStart(value: Long?) { _sessionStartedAt.value = value }
    internal fun setChunkStart(value: Long?) { _currentChunkStartedAt.value = value }
    internal fun setFreeBytes(value: Long) { _freeBytes.value = value }

    fun setUiVisible(value: Boolean) { _uiVisible.value = value }
}
