package com.mandar.echo.stt

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "WhisperEngine"

data class WhisperSegment(
    /** Milliseconds from the start of the supplied audio. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscriptionResult(
    val segments: List<WhisperSegment>,
    val language: String,
    val elapsedMs: Long,
) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }.trim()
    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/**
 * Serialised wrapper around [WhisperNative]. A whisper_context must never be
 * touched from two threads at once, so every entry point holds [mutex].
 */
class WhisperEngine {

    private val mutex = Mutex()
    @Volatile private var ptr: Long = 0
    @Volatile private var loadedPath: String? = null

    val isLoaded: Boolean get() = ptr != 0L
    val modelPath: String? get() = loadedPath

    val threadCount: Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    suspend fun load(model: File): Result<Unit> = mutex.withLock {
        if (ptr != 0L && loadedPath == model.absolutePath) return@withLock Result.success(Unit)
        releaseLocked()

        if (!model.exists()) {
            return@withLock Result.failure(IllegalStateException("model missing: ${model.absolutePath}"))
        }
        val p = try {
            WhisperNative.initContext(model.absolutePath)
        } catch (t: Throwable) {
            return@withLock Result.failure(t)
        }
        if (p == 0L) {
            return@withLock Result.failure(IllegalStateException("whisper failed to load ${model.name}"))
        }
        ptr = p
        loadedPath = model.absolutePath
        Log.i(TAG, "loaded ${model.name} with $threadCount threads")
        Result.success(Unit)
    }

    /**
     * @param samples 16 kHz mono, normalised to -1..1
     * @param language "auto", "en", "hi" or "mr"
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
    ): Result<TranscriptionResult> = mutex.withLock {
        val p = ptr
        if (p == 0L) return@withLock Result.failure(IllegalStateException("no model loaded"))
        if (samples.isEmpty()) {
            return@withLock Result.success(TranscriptionResult(emptyList(), language, 0))
        }

        val started = System.currentTimeMillis()
        val rc = try {
            WhisperNative.fullTranscribe(p, threadCount, samples, language, false)
        } catch (t: Throwable) {
            return@withLock Result.failure(t)
        }
        val elapsed = System.currentTimeMillis() - started
        if (rc != 0) {
            return@withLock Result.failure(IllegalStateException("whisper_full returned $rc"))
        }

        val count = WhisperNative.getTextSegmentCount(p)
        val segments = ArrayList<WhisperSegment>(count)
        for (i in 0 until count) {
            val text = WhisperNative.getTextSegment(p, i).trim()
            if (text.isEmpty()) continue
            // whisper timestamps are centiseconds
            segments += WhisperSegment(
                startMs = WhisperNative.getTextSegmentT0(p, i) * 10,
                endMs = WhisperNative.getTextSegmentT1(p, i) * 10,
                text = text,
            )
        }
        val detected = WhisperNative.getDetectedLanguage(p).ifBlank { language }
        Result.success(TranscriptionResult(segments, detected, elapsed))
    }

    /** 0..100 while a transcription is in flight. */
    fun progress(): Int = runCatching { WhisperNative.getProgress() }.getOrDefault(0)

    fun requestAbort() = runCatching { WhisperNative.requestAbort(true) }

    fun systemInfo(): String = runCatching { WhisperNative.getSystemInfo() }.getOrDefault("unavailable")

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        if (ptr != 0L) {
            WhisperNative.freeContext(ptr)
            ptr = 0
            loadedPath = null
        }
    }
}
