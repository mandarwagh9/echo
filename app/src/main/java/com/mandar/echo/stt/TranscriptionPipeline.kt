package com.mandar.echo.stt

import android.content.Context
import android.util.Log
import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.AudioGain
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.EchoSettings
import com.mandar.echo.data.LeaseLostException
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.Settings
import com.mandar.echo.data.SttBackend
import com.mandar.echo.data.TranscriptSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TranscriptionPipeline"

private val WHITESPACE = Regex("\\s+")

data class PipelineState(
    val busy: Boolean = false,
    val currentChunkId: Long? = null,
    val progress: Int = 0,
    /** Audio seconds processed per wall-clock second. Must stay above 1.0 to keep up. */
    val realtimeFactor: Float = 0f,
    val lastError: String? = null,
    /**
     * Why the queue is deliberately waiting rather than working.
     *
     * Distinct from [lastError] on purpose. A cold server, no wi-fi, or a job the
     * server is still running are all normal states of a 24/7 recorder, and calling
     * them errors trains the user to ignore the one notice that matters. But a park
     * with nothing on screen is exactly the failure that let eighteen chunks queue
     * in silence, so it is not allowed to be invisible either.
     */
    val waiting: String? = null,
    val modelReady: Boolean = false,
)

/**
 * Drains claimable chunks one at a time, oldest first, and owns the decision of
 * when a chunk's audio may be deleted.
 *
 * Single-threaded by design: a whisper context cannot be shared, the server runs
 * one batch worker behind a global lock, and running two transcriptions at once on
 * a phone would starve the recorder thread — the one thing this app must never do.
 *
 * The loop never blocks on a chunk it cannot finish. Work that is waiting on
 * something external *parks*: the chunk goes back to PENDING with a `notBefore`,
 * the worker is released, and the durable `cloud_jobs` rows keep whatever the
 * server has already been paid to do. Parking is not failing, so it never burns an
 * attempt and never downgrades a chunk to the weaker engine.
 */
class TranscriptionPipeline(
    context: Context,
    private val db: EchoDatabase,
    private val settings: EchoSettings,
    private val models: ModelManager,
    private val engine: WhisperEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        const val MAX_ATTEMPTS = 3

        /**
         * A TRANSCRIBING claim older than this is provably abandoned.
         *
         * Generous because it must exceed the slowest legitimate run: a ten-minute
         * chunk on the on-device engine, stretched by Doze. Recovering a live claim
         * early costs the work twice; recovering a dead one late costs nothing but
         * latency, so the asymmetry points one way.
         */
        const val LEASE_TIMEOUT_MS = 45 * 60_000L

        const val STALE_SWEEP_MS = 5 * 60_000L

        /** Park lengths, by what is being waited on. */
        const val PARK_SHORT_MS = 12_000L
        const val PARK_MEDIUM_MS = 60_000L
        const val PARK_LONG_MS = 10 * 60_000L

        const val IDLE_MS = 8_000L

        /**
         * Ceiling on WAV Echo will hold back from deletion.
         *
         * Parking rather than falling back means a long outage releases no audio at
         * all, and 19 MB per ten-minute chunk reaches the recorder's own low-storage
         * pause in about a day. Past this point a degraded transcript beats no
         * recording, so the cloud is bypassed and the audio released — the trade is
         * made deliberately and logged, instead of arriving as a silent stop.
         */
        const val MAX_RETAINED_BYTES = 1_000_000_000L

        /** Per-piece integer division can lose a millisecond or two across a chunk. */
        const val HOLE_TOLERANCE_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val gate = CloudGate(appContext)

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private var job: Job? = null
    private var lastStaleSweep = 0L

    // Rebuilt only when the server settings actually change, so the transport's
    // bounded dispatcher and the gate's backoff survive across chunks.
    private var cloud: CloudTranscriber? = null
    private var cloudIdentity: String? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            // Nothing can still be holding a lease taken before this process
            // existed, so every claim is stale at startup regardless of age.
            runCatching { db.chunkDao().requeueStale(clock()) }
                .onSuccess { if (it > 0) Log.i(TAG, "recovered $it claim(s) from a previous run") }
                .onFailure { Log.e(TAG, "claim recovery failed", it) }

            runCatching { recoverAbandonedRecording() }
                .onFailure { Log.e(TAG, "recording recovery failed", it) }
            runCatching { releaseLeftoverAudio() }
                .onFailure { Log.e(TAG, "leftover audio sweep failed", it) }
            runCatching { reconcileOrphanAudio() }
                .onFailure { Log.e(TAG, "orphan audio sweep failed", it) }

            lastStaleSweep = clock()

            while (isActive) {
                runCatching { sweepStaleClaims() }
                val worked = runCatching { processNext() }
                    .onFailure { Log.e(TAG, "pipeline iteration failed", it) }
                    .getOrDefault(false)
                if (!worked) delay(idleWait())
            }
        }
    }

    fun stop() {
        engine.requestAbort()
        job?.cancel()
        job = null
    }

    /**
     * @return true if a chunk reached a terminal state, so the loop should try
     *   again immediately. A park returns false: the chunk is deliberately not
     *   ready, and spinning on it is the head-of-line block this design removes.
     *
     * Visible for instrumentation tests, which drive one chunk through the whole
     * pipeline deterministically rather than racing the background loop.
     */
    internal suspend fun processNext(): Boolean {
        val chunkDao = db.chunkDao()
        val now = clock()

        if (chunkDao.claimableCountNow(now) == 0) {
            _state.value = _state.value.copy(waiting = waitingReason(now))
            return false
        }

        val cfg = settings.current()

        // Choosing the server must not require a 190 MB Whisper download. This
        // check used to run unconditionally, so with the cloud selected and no
        // local model installed the pipeline returned here every time and nothing
        // was ever transcribed — chunks closed correctly and simply piled up.
        val cloudSelected = usesCloud(cfg)
        val modelFile = models.resolveInstalledOrNull(cfg.modelFile)

        if (modelFile == null && !cloudSelected) {
            _state.value = _state.value.copy(
                modelReady = false,
                lastError = "No speech model installed",
            )
            return false
        }

        if (modelFile != null &&
            (!engine.isLoaded || engine.modelPath != modelFile.absolutePath)
        ) {
            val loaded = engine.load(modelFile)
            if (loaded.isFailure && !cloudSelected) {
                _state.value = _state.value.copy(
                    modelReady = false,
                    lastError = loaded.exceptionOrNull()?.message,
                )
                delay(10_000)
                return false
            }
        }
        _state.value = _state.value.copy(modelReady = engine.isLoaded || cloudSelected)

        // Silero VAD is deliberately NOT enabled here, despite the plumbing being
        // in place and working. Measured on the Indic fixtures, turning it on made
        // Hindi *worse*: word recall fell from 0.385 to 0.231, with the output
        // echoing the tail of the Hindi initial prompt. Enable by setting
        // engine.vadModelPath (see ModelManager.ensureVad) and re-running
        // IndicSpeechInstrumentedTest before believing it helps.

        val chunk = chunkDao.claimNext(now) ?: return false
        val lease = chunk.claimedAt ?: run {
            Log.e(TAG, "chunk ${chunk.id} claimed without a lease stamp")
            return false
        }

        _state.value = _state.value.copy(
            busy = true,
            currentChunkId = chunk.id,
            progress = 0,
            waiting = null,
        )
        return try {
            transcribeChunk(chunk, lease, cfg)
        } finally {
            _state.value = _state.value.copy(busy = false, currentChunkId = null, progress = 0)
        }
    }

    // ---- one chunk ----------------------------------------------------------

    /** Text a backend produced, positioned in the *compacted* voiced stream. */
    private data class Spoken(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val language: String,
    )

    private sealed interface Attempt {
        data class Text(
            val spoken: List<Spoken>,
            val source: String,
            /** Voiced ms this transcript actually accounts for. */
            val coveredMs: Long,
            val elapsedMs: Long,
        ) : Attempt

        /** Nothing is wrong; the thing we need has not happened yet. */
        data class Park(val reason: String, val backoffMs: Long) : Attempt

        /** This run failed for a reason retrying cannot obviously fix. */
        data class Fail(val reason: String) : Attempt
    }

    private suspend fun transcribeChunk(
        chunk: ChunkEntity,
        lease: Long,
        cfg: Settings,
    ): Boolean {
        val chunkDao = db.chunkDao()
        val file = chunk.filePath?.let(::File)

        if (file == null || !file.exists()) {
            // Nothing to transcribe and nothing recoverable. FAILED with no
            // filePath is retired by discardFailedWithoutAudio rather than retried
            // forever, one attempt at a time, with no way for the user to clear it.
            chunkDao.finishChunk(
                id = chunk.id,
                lease = lease,
                status = ChunkStatus.FAILED,
                attempts = chunk.attempts + 1,
                error = "audio file missing",
                transcribeMs = null,
                speechRatio = chunk.speechRatio,
                wordCount = 0,
                transcriptSource = null,
                voicedMs = 0,
                coveredMs = 0,
                audioHold = null,
            )
            runCatching { db.cloudJobDao().clearForChunk(chunk.id) }
            return true
        }

        val attempts = chunk.attempts + 1
        try {
            WavWriter.repairIfTruncated(file)
            val samples = WavWriter.readAsFloats(file)
            val vad = VoiceActivityDetector.analyse(samples)

            if (cfg.skipSilentChunks && !vad.hasSpeech) {
                Log.i(TAG, "chunk ${chunk.id} is silent (ratio=${vad.speechRatio}); not transcribing")
                return commitSilent(chunk, lease, attempts, vad.speechRatio, file, cfg)
            }

            // A backend only ever sees the voiced parts. Feeding the silence in
            // between is what produced pages of invented, looping text.
            val voiced = VoiceActivityDetector.extractVoiced(samples)
            if (voiced.isEmpty) {
                Log.i(TAG, "chunk ${chunk.id} has no voiced regions after gating")
                return commitSilent(chunk, lease, attempts, vad.speechRatio, file, cfg)
            }

            val levelled = AudioGain.normalize(voiced.samples)
            val voicedMs = levelled.samples.size * 1000L / AudioFormatSpec.SAMPLE_RATE
            Log.i(
                TAG,
                "chunk ${chunk.id}: ${voiced.regions.size} voiced regions, " +
                    "${"%.1f".format(voicedMs / 1000f)} s of " +
                    "${"%.1f".format(samples.size / 16_000f)} s to transcribe " +
                    "(rms=${"%.4f".format(levelled.inputRms)}, gain=${"%.1f".format(levelled.gain)}x)",
            )

            val underPressure = chunkDao.retainedAudioBytes() > MAX_RETAINED_BYTES

            return when (val attempt = runBackends(chunk, cfg, levelled.samples, voicedMs, underPressure)) {
                is Attempt.Park -> {
                    park(chunk, attempt.reason, attempt.backoffMs)
                    false
                }

                is Attempt.Fail -> {
                    failChunk(chunk, lease, attempts, attempt.reason, vad.speechRatio, voicedMs)
                    true
                }

                is Attempt.Text -> {
                    commitText(chunk, lease, attempts, attempt, voiced, vad.speechRatio, voicedMs, cfg, underPressure, file)
                    true
                }
            }
        } catch (lost: LeaseLostException) {
            // Another worker owns this chunk now and wrote its own result. Ours is
            // the copy that must disappear: no attempt burned, no audio deleted.
            Log.w(TAG, "chunk ${chunk.id}: lease lost mid-run; discarding this result", lost)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "chunk ${chunk.id} failed (attempt $attempts)", t)
            failChunk(
                chunk, lease, attempts,
                t.message ?: t::class.java.simpleName,
                chunk.speechRatio, chunk.voicedMs,
            )
            return true
        }
    }

    /**
     * Runs the configured backend, falling back to the on-device engine only when
     * falling back is actually better than waiting.
     *
     * The default is to wait. A 24/7 recorder meets cold servers, lifts and hotel
     * wifi constantly, and Whisper is measured at 0.23 word recall on Hindi and
     * 0.00 on Marathi against IndicConformer's 1.00 — so grinding a day of Marathi
     * through it because a server took two minutes to wake destroys the transcript
     * far more thoroughly than a delay does. Two things override that: the audio
     * backlog reaching [MAX_RETAINED_BYTES], and a server that answered and refused.
     */
    private suspend fun runBackends(
        chunk: ChunkEntity,
        cfg: Settings,
        audio: FloatArray,
        voicedMs: Long,
        underPressure: Boolean,
    ): Attempt {
        if (usesCloud(cfg)) {
            val now = clock()
            when (val blocked = gate.blockedReason(now, wifiOnly = false)) {
                null -> when (val outcome = runCloud(chunk, cfg, audio)) {
                    is CloudTranscriber.CloudOutcome.Settled -> {
                        gate.noteReachable()
                        gate.clearHalt()
                        val kept = outcome.pieces.filterNot { it.rejected }
                        return Attempt.Text(
                            spoken = kept.filter { it.text.isNotBlank() }.map {
                                Spoken(
                                    startMs = it.offsetMs,
                                    endMs = it.offsetMs + it.durationMs,
                                    text = it.text.trim(),
                                    language = it.language,
                                )
                            },
                            source = TranscriptSource.CLOUD,
                            coveredMs = kept.sumOf { it.durationMs },
                            elapsedMs = outcome.elapsedMs,
                        )
                    }

                    is CloudTranscriber.CloudOutcome.Park -> {
                        if (outcome.progressed) gate.noteReachable()
                        else if (outcome.offline || !gate.hasNetwork()) {
                            gate.noteUnreachable(clock(), outcome.reason)
                        }
                        if (!underPressure) return Attempt.Park(outcome.reason, parkBackoff(outcome))
                        Log.w(TAG, "chunk ${chunk.id}: held audio over cap; using the on-device engine")
                    }

                    is CloudTranscriber.CloudOutcome.Halt -> {
                        // Configuration, not a chunk problem. Halting leaves every
                        // queued chunk's audio untouched and says why, which is
                        // strictly better than burning a backlog at Whisper quality
                        // because a key was rotated.
                        gate.halt(outcome.reason)
                        if (!underPressure) return Attempt.Park(outcome.reason, PARK_LONG_MS)
                    }

                    is CloudTranscriber.CloudOutcome.Rejected -> {
                        // The server understood this audio and refused it. Nothing
                        // better will happen for it, so the weaker engine is now the
                        // best available answer rather than a downgrade.
                        Log.w(TAG, "chunk ${chunk.id}: server refused the audio (${outcome.reason})")
                        runCatching { db.cloudJobDao().clearForChunk(chunk.id) }
                    }
                }

                else -> if (!underPressure) return Attempt.Park(blocked, parkBackoff(null))
            }
        }

        if (!engine.isLoaded) {
            return Attempt.Park("waiting for a transcriber", PARK_MEDIUM_MS)
        }

        val result = engine.transcribe(audio, cfg.language.code)
        val ok = result.getOrElse {
            return Attempt.Fail(it.message ?: it::class.java.simpleName)
        }
        return Attempt.Text(
            spoken = ok.segments.map { Spoken(it.startMs, it.endMs, it.text, ok.language) },
            source = TranscriptSource.DEVICE,
            // Whisper saw the whole compacted stream, so there is no hole to record.
            coveredMs = voicedMs,
            elapsedMs = ok.elapsedMs,
        )
    }

    private suspend fun runCloud(
        chunk: ChunkEntity,
        cfg: Settings,
        audio: FloatArray,
    ): CloudTranscriber.CloudOutcome =
        cloudFor(cfg).run(
            chunkId = chunk.id,
            voiced = audio,
            language = cfg.language.code,
            // Fingerprints the exact array that gets uploaded, so a gate or gain
            // change invalidates stored piece offsets instead of silently
            // re-labelling audio they were not cut from.
            fingerprint = fingerprint(audio),
        )

    // ---- terminal writes -----------------------------------------------------

    private suspend fun commitSilent(
        chunk: ChunkEntity,
        lease: Long,
        attempts: Int,
        speechRatio: Float,
        file: File,
        cfg: Settings,
    ): Boolean {
        db.transcriptDao().commitTranscript(
            chunkDao = db.chunkDao(),
            segmentDao = db.segmentDao(),
            chunkId = chunk.id,
            lease = lease,
            segments = emptyList(),
            attempts = attempts,
            transcribeMs = 0,
            speechRatio = speechRatio,
            wordCount = 0,
            status = ChunkStatus.SILENT,
            transcriptSource = null,
            voicedMs = 0,
            coveredMs = 0,
            audioHold = null,
        )
        runCatching { db.cloudJobDao().clearForChunk(chunk.id) }
        if (!cfg.keepAudioAfterTranscription) releaseAudio(chunk.id, file)
        return true
    }

    private suspend fun commitText(
        chunk: ChunkEntity,
        lease: Long,
        attempts: Int,
        attempt: Attempt.Text,
        voiced: VoiceActivityDetector.Voiced,
        speechRatio: Float,
        voicedMs: Long,
        cfg: Settings,
        underPressure: Boolean,
        file: File,
    ) {
        val segments = attempt.spoken.map {
            // Timestamps come back relative to the compacted stream, so they have
            // to be mapped back through the gate before they mean anything.
            SegmentEntity(
                chunkId = chunk.id,
                startMs = chunk.startedAt + voiced.originalMs(it.startMs),
                endMs = chunk.startedAt + voiced.originalMs(it.endMs),
                text = it.text,
                language = it.language,
            )
        }
        val words = segments.sumOf { seg -> seg.text.split(WHITESPACE).count { it.isNotBlank() } }
        val hold = holdReason(attempt, voicedMs, cfg, underPressure)

        // Segments and terminal status commit together, conditional on still
        // holding the lease. Only after this returns is it safe to touch the audio.
        db.transcriptDao().commitTranscript(
            chunkDao = db.chunkDao(),
            segmentDao = db.segmentDao(),
            chunkId = chunk.id,
            lease = lease,
            segments = segments,
            attempts = attempts,
            transcribeMs = attempt.elapsedMs,
            speechRatio = speechRatio,
            wordCount = words,
            status = ChunkStatus.DONE,
            transcriptSource = attempt.source,
            voicedMs = voicedMs,
            coveredMs = attempt.coveredMs,
            audioHold = hold,
        )
        runCatching { db.cloudJobDao().clearForChunk(chunk.id) }

        val rtf = if (attempt.elapsedMs > 0) {
            (voicedMs / 1000f) / (attempt.elapsedMs / 1000f)
        } else {
            0f
        }
        _state.value = _state.value.copy(realtimeFactor = rtf, lastError = null, waiting = null)
        Log.i(
            TAG,
            "chunk ${chunk.id}: ${segments.size} segments, $words words via ${attempt.source}, " +
                "${attempt.elapsedMs} ms (${"%.1f".format(rtf)}x realtime)" +
                if (hold != null) " — audio kept: $hold" else "",
        )

        if (hold == null && !cfg.keepAudioAfterTranscription) releaseAudio(chunk.id, file)
    }

    /**
     * Why this chunk's audio must survive its own transcript, or null to release.
     *
     * Storing text is not on its own proof the audio is expendable: a partial
     * transcript has a hole in it, and a device transcript taken while the server
     * was unavailable is provisional by construction. Both are re-transcribable
     * only while the WAV exists, and only findable later because
     * [com.mandar.echo.data.ChunkEntity.transcriptSource] records which engine wrote it.
     */
    private fun holdReason(
        attempt: Attempt.Text,
        voicedMs: Long,
        cfg: Settings,
        underPressure: Boolean,
    ): String? = when {
        underPressure -> null
        attempt.coveredMs + HOLE_TOLERANCE_MS < voicedMs ->
            "part of this chunk was never transcribed"
        attempt.source == TranscriptSource.DEVICE && usesCloud(cfg) ->
            "transcribed on device while the server was unavailable"
        else -> null
    }

    private suspend fun failChunk(
        chunk: ChunkEntity,
        lease: Long,
        attempts: Int,
        reason: String,
        speechRatio: Float,
        voicedMs: Long,
    ) {
        val terminal = attempts >= MAX_ATTEMPTS
        db.chunkDao().finishChunk(
            id = chunk.id,
            lease = lease,
            // Audio is deliberately kept on FAILED so a bad run stays recoverable.
            status = if (terminal) ChunkStatus.FAILED else ChunkStatus.PENDING,
            attempts = attempts,
            error = reason,
            transcribeMs = null,
            speechRatio = speechRatio,
            wordCount = 0,
            transcriptSource = null,
            voicedMs = voicedMs,
            coveredMs = 0,
            audioHold = if (terminal) "failed; kept so it can be retried" else null,
        )
        _state.value = _state.value.copy(lastError = reason)
    }

    private suspend fun park(chunk: ChunkEntity, reason: String, backoffMs: Long) {
        val wait = backoffMs.coerceIn(PARK_SHORT_MS, PARK_LONG_MS)
        db.chunkDao().deferChunk(
            id = chunk.id,
            notBefore = clock() + wait,
            // Never touches `attempts`: a park is not an attempt at anything, and
            // treating a cold server as one is what let three network blips
            // permanently downgrade a chunk to the weaker engine.
            transientFailures = chunk.transientFailures + 1,
            error = reason,
        )
        Log.i(TAG, "chunk ${chunk.id} parked: $reason (retry in ${wait / 1000}s)")
        _state.value = _state.value.copy(waiting = reason)
    }

    /**
     * How long to leave a parked chunk alone.
     *
     * Differentiated on purpose. `nextClaimableId` prefers whichever chunk holds a
     * job the server is already running, but only once its `notBefore` has passed —
     * so a long park on that chunk lets a different one be claimed, immediately hit
     * "another chunk is holding the server's only worker", and park having burned a
     * claim, while the server finishes a transcript nobody collects.
     */
    private suspend fun parkBackoff(outcome: CloudTranscriber.CloudOutcome.Park?): Long {
        val serverBackoff = gate.retryServerAt - clock()
        return when {
            serverBackoff > 0 -> serverBackoff
            outcome == null -> PARK_MEDIUM_MS
            outcome.progressed -> PARK_SHORT_MS
            db.cloudJobDao().outstandingChunkId() != null -> PARK_SHORT_MS
            else -> PARK_MEDIUM_MS
        }
    }

    // ---- audio lifecycle -----------------------------------------------------

    private suspend fun releaseAudio(chunkId: Long, file: File) {
        if (file.delete() || !file.exists()) {
            db.chunkDao().markAudioDeleted(chunkId)
        } else {
            Log.w(TAG, "could not delete ${file.name}")
        }
    }

    /**
     * Terminal chunks whose audio was cleared for release but is still on disk —
     * the service was killed between committing the transcript and unlinking the
     * file. Nothing else ever revisits a terminal chunk, so without this sweep the
     * WAV stays forever and Echo quietly breaks its own promise to delete it.
     */
    private suspend fun releaseLeftoverAudio() {
        db.chunkDao().releasableLeftovers().forEach { row ->
            val f = row.filePath?.let(::File) ?: return@forEach
            if (!f.exists() || f.delete()) {
                db.chunkDao().markAudioDeleted(row.id)
                Log.i(TAG, "released leftover audio for chunk ${row.id}")
            }
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

    /**
     * Chunks the recorder was mid-write on when the process died.
     *
     * The cutoff must clear the chunk the AudioChunker is writing *right now*, or
     * this steals audio out from under a live recording. Recovered rows carry
     * whatever reached the disk; a row whose file is gone can only ever fail, so it
     * is retired rather than queued.
     */
    private suspend fun recoverAbandonedRecording() {
        val cfg = settings.current()
        val cutoff = clock() - (cfg.chunkMinutes + 5) * 60_000L
        db.chunkDao().abandonedRecording(cutoff).forEach { row ->
            val f = row.filePath?.let(::File)
            if (f == null || !f.exists()) {
                db.chunkDao().setStatus(row.id, ChunkStatus.DISCARDED)
                Log.w(TAG, "discarded chunk ${row.id}: abandoned mid-recording with no audio")
                return@forEach
            }
            runCatching { WavWriter.repairIfTruncated(f) }
            val samples = ((f.length() - 44).coerceAtLeast(0)) / 2
            db.chunkDao().closeChunk(
                id = row.id,
                endedAt = row.startedAt + samples * 1000L / AudioFormatSpec.SAMPLE_RATE,
                sampleCount = samples,
                status = ChunkStatus.PENDING,
            )
            Log.i(TAG, "recovered chunk ${row.id} abandoned mid-recording ($samples samples)")
        }
    }

    private suspend fun sweepStaleClaims() {
        val now = clock()
        if (now - lastStaleSweep < STALE_SWEEP_MS) return
        lastStaleSweep = now
        val recovered = db.chunkDao().requeueStale(now - LEASE_TIMEOUT_MS)
        if (recovered > 0) Log.w(TAG, "requeued $recovered chunk(s) whose worker vanished")
    }

    // ---- helpers -------------------------------------------------------------

    private fun usesCloud(cfg: Settings): Boolean =
        cfg.sttBackend == SttBackend.CLOUD &&
            cfg.sttServerUrl.startsWith("http") &&
            CloudTranscriber.supports(cfg.language.code)

    private fun cloudFor(cfg: Settings): CloudTranscriber {
        val identity = cfg.sttServerUrl + " " + cfg.sttApiKey
        cloud?.let { if (cloudIdentity == identity) return it }
        // The settings that could have caused a halt just changed, so whatever was
        // wrong may well be fixed. Without this the cloud path stays dead until the
        // process restarts, however many times the user corrects the key.
        if (cloudIdentity != null) gate.clearHalt()
        cloudIdentity = identity
        return CloudTranscriber(cfg.sttServerUrl, cfg.sttApiKey, db.cloudJobDao())
            .also { cloud = it }
    }

    /** Cheap, stable identity for a float stream: length plus a strided sample sweep. */
    private fun fingerprint(samples: FloatArray): Long {
        var h = 1125899906842597L
        h = h * 31 + samples.size
        val stride = maxOf(1, samples.size / 4096)
        var i = 0
        while (i < samples.size) {
            h = h * 31 + samples[i].toRawBits()
            i += stride
        }
        return h
    }

    private suspend fun waitingReason(now: Long): String? {
        if (db.chunkDao().pendingCountNow() == 0) return null
        gate.haltReason?.let { return it }
        val next = db.chunkDao().nextClaimableAt(now) ?: return null
        val seconds = ((next - now) / 1000).coerceAtLeast(0)
        val pretty = if (seconds < 90) "${seconds}s" else "${seconds / 60} min"
        return "Queued — next try in $pretty"
    }

    private suspend fun idleWait(): Long {
        val now = clock()
        val next = runCatching { db.chunkDao().nextClaimableAt(now) }.getOrNull()
            ?: return IDLE_MS
        return (next - now).coerceIn(2_000L, 15_000L)
    }

    /** Poll target for the UI while a chunk is in flight. */
    fun refreshProgress() {
        if (_state.value.busy) {
            _state.value = _state.value.copy(progress = engine.progress())
        }
    }
}
