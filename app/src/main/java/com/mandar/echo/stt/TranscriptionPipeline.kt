package com.mandar.echo.stt

import android.content.Context
import android.util.Log
import com.mandar.echo.audio.AudioGain
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.EchoSettings
import com.mandar.echo.data.SegmentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TranscriptionPipeline"
private const val MAX_ATTEMPTS = 3

data class PipelineState(
    val busy: Boolean = false,
    val currentChunkId: Long? = null,
    val progress: Int = 0,
    /** Audio seconds processed per wall-clock second. Must stay above 1.0 to keep up. */
    val realtimeFactor: Float = 0f,
    val lastError: String? = null,
    val modelReady: Boolean = false,
)

/**
 * Drains PENDING chunks one at a time, in age order.
 *
 * Single-threaded by design: a whisper context cannot be shared, and running two
 * transcriptions concurrently on a phone would starve the recorder thread — which
 * is the one thing this app must never do.
 */
class TranscriptionPipeline(
    context: Context,
    private val db: EchoDatabase,
    private val settings: EchoSettings,
    private val models: ModelManager,
    private val engine: WhisperEngine,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // A chunk left TRANSCRIBING by a crash is re-queued, never abandoned.
            runCatching { db.chunkDao().requeueStuck() }
            reconcileOrphanAudio()

            while (isActive) {
                val worked = runCatching { processNext() }
                    .onFailure { Log.e(TAG, "pipeline iteration failed", it) }
                    .getOrDefault(false)
                if (!worked) delay(4_000)
            }
        }
    }

    fun stop() {
        engine.requestAbort()
        job?.cancel()
        job = null
    }

    /**
     * @return true if a chunk was processed (so the loop should immediately try again).
     *
     * Visible for instrumentation tests, which drive one chunk through the whole
     * pipeline deterministically rather than racing the background loop.
     */
    internal suspend fun processNext(): Boolean {
        val chunkDao = db.chunkDao()
        if (chunkDao.pendingCountNow() == 0) return false

        val cfg = settings.current()
        val modelFile = models.resolveInstalledOrNull(cfg.modelFile)
        if (modelFile == null) {
            _state.value = _state.value.copy(
                modelReady = false,
                lastError = "No speech model installed",
            )
            return false
        }

        if (!engine.isLoaded || engine.modelPath != modelFile.absolutePath) {
            val loaded = engine.load(modelFile)
            if (loaded.isFailure) {
                _state.value = _state.value.copy(
                    modelReady = false,
                    lastError = loaded.exceptionOrNull()?.message,
                )
                delay(10_000)
                return false
            }
        }
        _state.value = _state.value.copy(modelReady = true)

        if (chunkDao.claimOldestPending() == 0) return false
        val chunk = chunkDao.currentlyTranscribing() ?: return false

        _state.value = _state.value.copy(busy = true, currentChunkId = chunk.id, progress = 0)
        try {
            transcribeChunk(chunk, cfg.language.code, cfg.skipSilentChunks, cfg.keepAudioAfterTranscription)
        } finally {
            _state.value = _state.value.copy(busy = false, currentChunkId = null, progress = 0)
        }
        return true
    }

    private suspend fun transcribeChunk(
        chunk: ChunkEntity,
        language: String,
        skipSilent: Boolean,
        keepAudio: Boolean,
    ) {
        val chunkDao = db.chunkDao()
        val path = chunk.filePath
        val file = path?.let(::File)

        if (file == null || !file.exists()) {
            // Nothing to transcribe and nothing recoverable: close it out rather
            // than looping on a chunk whose audio is gone.
            chunkDao.finishChunk(
                chunk.id, ChunkStatus.FAILED, chunk.attempts + 1,
                "audio file missing", null, 0f, 0,
            )
            return
        }

        val attempts = chunk.attempts + 1
        try {
            WavWriter.repairIfTruncated(file)
            val samples = WavWriter.readAsFloats(file)

            val vad = VoiceActivityDetector.analyse(samples)
            if (skipSilent && !vad.hasSpeech) {
                Log.i(TAG, "chunk ${chunk.id} is silent (ratio=${vad.speechRatio}); skipping whisper")
                db.transcriptDao().commitTranscript(
                    chunkDao = chunkDao,
                    segmentDao = db.segmentDao(),
                    chunkId = chunk.id,
                    segments = emptyList(),
                    attempts = attempts,
                    transcribeMs = 0,
                    speechRatio = vad.speechRatio,
                    wordCount = 0,
                    status = ChunkStatus.SILENT,
                )
                if (!keepAudio) deleteAudio(chunk.id, file)
                return
            }

            // Whisper only ever sees the voiced parts. Feeding it the silence in
            // between is what produced pages of invented, looping text.
            val voiced = VoiceActivityDetector.extractVoiced(samples)
            if (voiced.isEmpty) {
                Log.i(TAG, "chunk ${chunk.id} has no voiced regions after gating; skipping whisper")
                db.transcriptDao().commitTranscript(
                    chunkDao = chunkDao,
                    segmentDao = db.segmentDao(),
                    chunkId = chunk.id,
                    segments = emptyList(),
                    attempts = attempts,
                    transcribeMs = 0,
                    speechRatio = vad.speechRatio,
                    wordCount = 0,
                    status = ChunkStatus.SILENT,
                )
                if (!keepAudio) deleteAudio(chunk.id, file)
                return
            }
            val levelled = AudioGain.normalize(voiced.samples)
            Log.i(
                TAG,
                "chunk ${chunk.id}: ${voiced.regions.size} voiced regions, " +
                    "${"%.1f".format(voiced.samples.size / 16_000f)} s of " +
                    "${"%.1f".format(samples.size / 16_000f)} s sent to whisper " +
                    "(rms=${"%.4f".format(levelled.inputRms)}, gain=${"%.1f".format(levelled.gain)}x)",
            )

            val result = engine.transcribe(levelled.samples, language).getOrThrow()

            val segments = result.segments.map {
                // Timestamps come back relative to the compacted stream, so they
                // have to be mapped through the gate before they mean anything.
                SegmentEntity(
                    chunkId = chunk.id,
                    startMs = chunk.startedAt + voiced.originalMs(it.startMs),
                    endMs = chunk.startedAt + voiced.originalMs(it.endMs),
                    text = it.text,
                    language = result.language,
                )
            }

            // Segments + terminal status commit together. Only after this returns
            // is it safe to delete the audio.
            db.transcriptDao().commitTranscript(
                chunkDao = chunkDao,
                segmentDao = db.segmentDao(),
                chunkId = chunk.id,
                segments = segments,
                attempts = attempts,
                transcribeMs = result.elapsedMs,
                speechRatio = vad.speechRatio,
                wordCount = result.wordCount,
                status = ChunkStatus.DONE,
            )

            val audioSeconds = samples.size.toFloat() / 16_000f
            val rtf = if (result.elapsedMs > 0) audioSeconds / (result.elapsedMs / 1000f) else 0f
            _state.value = _state.value.copy(realtimeFactor = rtf, lastError = null)
            Log.i(
                TAG,
                "chunk ${chunk.id}: ${segments.size} segments, ${result.wordCount} words, " +
                    "lang=${result.language}, ${result.elapsedMs} ms (${"%.1f".format(rtf)}x realtime)",
            )

            if (!keepAudio) deleteAudio(chunk.id, file)
        } catch (t: Throwable) {
            Log.e(TAG, "chunk ${chunk.id} failed (attempt $attempts)", t)
            val terminal = attempts >= MAX_ATTEMPTS
            chunkDao.finishChunk(
                id = chunk.id,
                // Audio is deliberately kept on FAILED so a bad run is recoverable.
                status = if (terminal) ChunkStatus.FAILED else ChunkStatus.PENDING,
                attempts = attempts,
                error = t.message ?: t::class.java.simpleName,
                transcribeMs = null,
                speechRatio = chunk.speechRatio,
                wordCount = 0,
            )
            _state.value = _state.value.copy(lastError = t.message)
        }
    }

    private suspend fun deleteAudio(chunkId: Long, file: File) {
        if (file.delete() || !file.exists()) {
            db.chunkDao().markAudioDeleted(chunkId)
        } else {
            Log.w(TAG, "could not delete ${file.name}")
        }
    }

    /**
     * Deletes WAVs on disk that no live chunk row points at — otherwise a crash
     * between "transcript committed" and "file deleted" leaks 19 MB permanently.
     */
    private suspend fun reconcileOrphanAudio() {
        val dir = File(appContext.filesDir, "chunks")
        if (!dir.isDirectory) return
        val known = db.chunkDao().chunksWithAudio().mapNotNull { it.filePath }.toHashSet()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".wav") && f.absolutePath !in known) {
                if (f.delete()) Log.i(TAG, "reclaimed orphan ${f.name}")
            }
        }
    }

    /** Poll target for the UI while a chunk is in flight. */
    fun refreshProgress() {
        if (_state.value.busy) {
            _state.value = _state.value.copy(progress = engine.progress())
        }
    }
}
