package com.mandar.echo.stt

import android.content.Context
import android.util.Log
import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.AudioGain
import com.mandar.echo.audio.DiskSpace
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.data.AudioHold
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.EchoSettings
import com.mandar.echo.data.LeaseLostException
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.Settings
import com.mandar.echo.data.SttBackend
import com.mandar.echo.data.TranscriptSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
         * How long a chunk that failed for an unknown reason waits before its next
         * attempt.
         *
         * `finishChunk` used to zero `notBefore` on the way back to PENDING, and
         * `nextClaimableId` prefers a chunk holding server work, so all three
         * attempts were spent inside the same second. Anything transient — a radio
         * handover, a moment of memory pressure — therefore cost the whole budget.
         */
        const val FAIL_BACKOFF_MS = 60_000L

        /**
         * Ceiling on WAV Echo will hold back from deletion.
         *
         * Parking rather than falling back means a long outage releases no audio at
         * all. Past this point a degraded transcript beats no recording, so the
         * cloud is bypassed — the trade is made deliberately and logged, instead of
         * arriving as a silent stop. The disk is the *other* half of the valve; see
         * [relieveAudioPressure], which decides which audio actually goes.
         */
        const val MAX_RETAINED_BYTES = 1_000_000_000L

        /** Per-piece integer division can lose a millisecond or two across a chunk. */
        const val HOLE_TOLERANCE_MS = 1_000L

        /**
         * Claims that ended with nothing recorded before a chunk is retired.
         *
         * The counter exists because `attempts` cannot see a crash loop: it is only
         * bumped from a catch block, which a SIGSEGV in the JNI layer or the
         * low-memory killer never reaches. A chunk that reliably kills the process
         * is claimed first on every boot — `nextClaimableId` orders oldest first —
         * so nothing behind it is ever transcribed again. Retiring it to FAILED
         * keeps its audio and puts it behind the same retry button as any other
         * failure, which is the difference between one lost chunk and a queue that
         * never advances.
         */
        const val MAX_ABANDONED_CLAIMS = 3

        /**
         * How long a chunk may sit at the batch pipeline before it is queued
         * again.
         *
         * Longer than the tier's own 24-hour SLA, so a slow-but-working batch is
         * never mistaken for a lost one, and shorter than the bucket's three-day
         * lifecycle, so the requeue happens while the uploaded copy still exists
         * rather than after it has been swept. Those two numbers are the actual
         * bounds; 30 hours sits between them with room either side.
         */
        const val UPLOAD_TIMEOUT_MS = 30L * 60 * 60 * 1000
    }

    private val appContext = context.applicationContext
    private val gate = CloudGate(appContext)

    /** Socket half of the batch upload; an interface so the loop is testable. */
    private val uploadTransport: UploadTransport = HttpUploadTransport()

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    private var job: Job? = null
    private var lastStaleSweep = 0L

    // Rebuilt only when the server settings actually change, so the transport's
    // bounded dispatcher and the gate's backoff survive across chunks.
    private var cloud: CloudTranscriber? = null
    private var cloudUrl: String? = null
    private var cloudKey: String? = null

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
            // A backlog held from an earlier run is over the cap the moment this
            // process starts, and if nothing new settles — the server came back, so
            // every chunk is already DONE — no later pass would ever look.
            runCatching { relieveAudioPressure() }
                .onFailure { Log.e(TAG, "audio pressure relief failed", it) }

            lastStaleSweep = clock()

            while (isActive) {
                runCatching { sweepStaleClaims() }
                // The batch backend's return leg. Cheap when there is nothing
                // waiting -- it is a local query that finds no rows -- and it has
                // to run here rather than on its own timer, because a chunk that
                // never gets its transcript never releases its audio.
                runCatching { collectBatchTranscripts() }
                    .onFailure { Log.e(TAG, "batch transcript sync failed", it) }
                val worked = runCatching { processNext() }
                    .onFailure { Log.e(TAG, "pipeline iteration failed", it) }
                    .getOrDefault(false)
                // Only a settled chunk can have produced audio the valve is allowed
                // to trade, and only a settled chunk changes what is being held.
                if (worked) {
                    runCatching { relieveAudioPressure() }
                        .onFailure { Log.e(TAG, "audio pressure relief failed", it) }
                }
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
        // Any backend that transcribes elsewhere, not just CLOUD. The batch
        // backend needs a local model exactly as little as the server does, and
        // gating it on one reproduces the bug the comment above describes --
        // chunks closing correctly and piling up for ever with nothing able to
        // run, which is precisely how eleven chunks once reached zero words.
        val remoteSelected = usesCloud(cfg) || usesBatch(cfg)
        val modelFile = models.resolveInstalledOrNull(cfg.modelFile)

        if (modelFile == null && !remoteSelected) {
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
            if (loaded.isFailure && !remoteSelected) {
                _state.value = _state.value.copy(
                    modelReady = false,
                    lastError = loaded.exceptionOrNull()?.message,
                )
                delay(10_000)
                return false
            }
        }
        _state.value = _state.value.copy(modelReady = engine.isLoaded || remoteSelected)

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

        if (chunk.abandonedClaims >= MAX_ABANDONED_CLAIMS) {
            retireCrashingChunk(chunk, lease)
            return true
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
            /**
             * A better transcript of this audio is still plausibly available, so the
             * WAV is worth keeping.
             *
             * Carried from the run rather than re-derived from [source], because
             * "the weaker engine wrote this because the server was unreachable" and
             * "the weaker engine wrote this because the server refused the audio"
             * produce identical rows and want opposite answers: only the first is
             * worth redoing.
             */
            val provisional: Boolean,
        ) : Attempt

        /** Nothing is wrong; the thing we need has not happened yet. */
        data class Park(val reason: String, val backoffMs: Long) : Attempt

        /** This run failed for a reason retrying cannot obviously fix. */
        data class Fail(val reason: String) : Attempt
    }

    private val batchSync by lazy {
        BatchSync(db.chunkDao(), db.segmentDao(), db.transcriptDao(), uploadTransport::mint)
    }

    /**
     * Ask the batch pipeline for transcripts of chunks this device uploaded.
     *
     * Runs on every loop iteration regardless of backend, deliberately: a user
     * who uploads a day's audio and then switches back to on-device would
     * otherwise strand those chunks in UPLOADED for ever, holding their WAVs and
     * waiting for a poll that no longer happens.
     */
    private suspend fun collectBatchTranscripts() {
        val cfg = settings.current()
        if (cfg.uploadUrl.isBlank() || cfg.uploadKey.isBlank()) return
        val done = batchSync.run(cfg)
        if (done > 0) Log.i(TAG, "batch pipeline returned $done transcript(s)")

        // Anything still waiting after this long is not coming. Queue it again
        // rather than leaving it stranded: the recording is still on this device,
        // which is exactly what the hold was for.
        val reclaimed = db.chunkDao().reclaimStuckUploads(clock() - UPLOAD_TIMEOUT_MS)
        if (reclaimed > 0) {
            Log.w(TAG, "$reclaimed chunk(s) never came back from the batch pipeline; requeued")
        }
    }

    /**
     * Hand one chunk to the batch pipeline and stop there.
     *
     * The chunk lands in [ChunkStatus.UPLOADED] holding
     * [AudioHold.AWAITING_REMOTE], which keeps its WAV. That is deliberate and
     * is the whole safety argument for this backend: an upload proves a second
     * copy exists in a bucket, not that anything has transcribed it, and the
     * bucket deletes its copy on a lifecycle timer either way. The rule the rest
     * of this file enforces -- audio is released when a transcript is *stored* --
     * is left exactly as it was. The sync that writes the returned segments is
     * what clears the hold.
     *
     * A configuration problem parks long rather than failing, for the same
     * reason the cloud path does: burning attempts against a missing setting
     * retires a chunk that nothing was ever wrong with.
     */
    private suspend fun handOffToBatch(
        chunk: ChunkEntity,
        lease: Long,
        attempts: Int,
        cfg: Settings,
        file: File,
        speechRatio: Float,
        voicedMs: Long,
    ): Boolean {
        if (cfg.uploadUrl.isBlank() || cfg.uploadKey.isBlank()) {
            park(chunk, "batch upload service is not configured", PARK_LONG_MS)
            return false
        }

        val uploader = GcsUploader(
            transport = uploadTransport,
            mintUrl = "${cfg.uploadUrl}/v1/upload-url",
            apiKey = cfg.uploadKey,
        )

        return when (val result = uploader.upload(file, chunk.startedAt)) {
            is GcsUploader.Result.Uploaded -> {
                Log.i(TAG, "chunk ${chunk.id} uploaded as ${result.objectName}")
                // The chunk's cloud attempt is over -- it is being transcribed by
                // something else now. settle() only clears these rows for a
                // terminal status, and UPLOADED deliberately is not one, so they
                // would otherwise outlive the attempt they describe. That matters
                // because nextClaimableId orders by outstanding SUBMITTED jobs:
                // a chunk later requeued by reclaimStuckUploads would jump the
                // queue on the strength of a server job that no longer exists.
                runCatching { db.cloudJobDao().clearForChunk(chunk.id) }
                    .onFailure { Log.w(TAG, "could not clear cloud jobs for ${chunk.id}", it) }
                settle(
                    chunk = chunk,
                    lease = lease,
                    status = ChunkStatus.UPLOADED,
                    attempts = attempts,
                    hold = AudioHold.AWAITING_REMOTE,
                    // null, not emptyList(): passing a list would delete the
                    // chunk's existing segments, and a redo of an already
                    // transcribed chunk would destroy the text it already had.
                    segments = null,
                    speechRatio = speechRatio,
                    transcriptSource = TranscriptSource.GCS_BATCH,
                    voicedMs = voicedMs,
                )
                true
            }

            is GcsUploader.Result.Retry -> {
                park(chunk, result.reason, PARK_MEDIUM_MS)
                false
            }

            is GcsUploader.Result.Halt -> {
                park(chunk, result.reason, PARK_LONG_MS)
                false
            }
        }
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
            val landed = settle(
                chunk = chunk,
                lease = lease,
                status = ChunkStatus.FAILED,
                attempts = chunk.attempts + 1,
                error = "audio file missing",
                speechRatio = chunk.speechRatio,
                voicedMs = 0,
                hold = null,
            )
            // The path points at nothing, so the row must stop claiming otherwise:
            // it was still counted in the retained-audio total, and
            // discardFailedWithoutAudio — the one thing that can retire it — matches
            // on filePath IS NULL and so could never see it.
            if (landed && chunk.filePath != null) chunkDao.markAudioDeleted(chunk.id)
            return true
        }

        val attempts = chunk.attempts + 1
        try {
            WavWriter.repairIfTruncated(file)
            val samples = WavWriter.readAsFloats(file)
            val vad = VoiceActivityDetector.analyse(samples)

            // `wordCount > 0` vetoes the silence short-circuit, and it has to.
            //
            // This chunk can be here for the second time: a redo re-runs the gate
            // with today's settings, and the two silence tests do not agree —
            // analyse() wants 2% of frames above the noise floor, extractVoiced()
            // keeps any merged run of 400 ms. A ten-minute chunk holding one
            // sentence fails the first and passes the second. So a user who turned
            // "skip silent chunks" on and then tapped Redo, hoping for a better
            // transcript, instead ran commitSilent over a chunk that already had
            // one: settle() clears the chunk's segments before writing, so the text
            // was destroyed, the row went SILENT, and the WAV was released. It then
            // appeared in neither the failed list nor the redo list. Words we have
            // already recovered from this audio are proof it is not silence,
            // whatever the ratio says this time.
            if (cfg.skipSilentChunks && !vad.hasSpeech && chunk.wordCount == 0) {
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

            // The batch backend's unit of work is the chunk file itself, not the
            // voiced stream. Uploading the compacted audio would be cheaper, but
            // Chirp 3 returns offsets relative to whatever it was given, and
            // against a compacted stream those stop mapping to wall-clock time --
            // which is the one thing the object name exists to establish. Both
            // silence gates above still apply, so a quiet chunk is never uploaded
            // at all, and that is where the saving actually comes from.
            val underPressure = underAudioPressure()

            if (usesBatch(cfg)) {
                val gatedMs = voiced.samples.size * 1000L / AudioFormatSpec.SAMPLE_RATE
                when {
                    !underPressure ->
                        return handOffToBatch(chunk, lease, attempts, cfg, file, vad.speechRatio, gatedMs)

                    // Over the cap. An uploaded chunk holds AWAITING_REMOTE, and
                    // the disk valve may only trade DEGRADED -- so uploading more
                    // adds audio nothing is allowed to release, and the backlog
                    // can only grow. Same trade the cloud path makes here: a
                    // degraded transcript beats no recording.
                    models.resolveInstalledOrNull(cfg.modelFile) != null ->
                        Log.w(TAG, "chunk ${chunk.id}: held audio over cap; using the on-device engine")

                    // Nothing local to fall back to. Park rather than upload:
                    // holding still is what lets the return leg catch up, and the
                    // storage guard owns the endgame if it never does.
                    else -> {
                        park(chunk, "waiting for transcripts; held audio is over the cap", PARK_LONG_MS)
                        return false
                    }
                }
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
                    commitText(chunk, lease, attempts, attempt, voiced, vad.speechRatio, voicedMs, cfg, file)
                    true
                }
            }
        } catch (cancelled: CancellationException) {
            // A stop is not a crash, but it looks exactly like one from the outside:
            // the claim is left standing and the next start counts it as a run that
            // died with nothing recorded. Handing the claim back keeps
            // `abandonedClaims` counting only the deaths it was written to bound, so
            // toggling recording during a long chunk cannot retire a healthy one.
            withContext(NonCancellable) {
                runCatching {
                    chunkDao.deferChunk(chunk.id, lease, clock(), chunk.transientFailures, chunk.error)
                }.onFailure { Log.w(TAG, "chunk ${chunk.id}: could not release the claim on stop", it) }
            }
            throw cancelled
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
    /**
     * The cloud configuration changed, so a configuration halt is no longer
     * evidence about the *current* settings.
     *
     * This has to exist as its own entry point. [CloudGate.clearHalt] is
     * otherwise only reached on a successful round trip, and
     * [CloudGate.blockedReason] reports the halt before anything can attempt
     * one — so a rotated key halted the path until the process was killed, and
     * correcting the key in Settings changed nothing.
     */
    fun onCloudSettingsChanged() {
        gate.clearHalt()
    }

    private suspend fun runBackends(
        chunk: ChunkEntity,
        cfg: Settings,
        audio: FloatArray,
        voicedMs: Long,
        underPressure: Boolean,
    ): Attempt {
        // Whether a cloud transcript is still owed to this audio. False once the
        // server has answered and refused: the weaker engine is then the best
        // answer that exists, not a placeholder for a better one.
        var provisional = false

        if (usesCloud(cfg)) {
            val now = clock()
            when (val blocked = gate.blockedReason(now, wifiOnly = false)) {
                null -> when (val outcome = runCloud(chunk, cfg, audio)) {
                    is CloudTranscriber.CloudOutcome.Settled -> {
                        gate.noteReachable()
                        gate.clearHalt()
                        return fromCloud(chunk, cfg, outcome, audio, voicedMs)
                    }

                    is CloudTranscriber.CloudOutcome.Park -> {
                        if (outcome.progressed) gate.noteReachable()
                        else if (outcome.offline || !gate.hasNetwork()) {
                            gate.noteUnreachable(clock(), outcome.reason)
                        }
                        if (!underPressure) return Attempt.Park(outcome.reason, parkBackoff(outcome))
                        Log.w(TAG, "chunk ${chunk.id}: held audio over cap; using the on-device engine")
                        provisional = true
                    }

                    is CloudTranscriber.CloudOutcome.Halt -> {
                        // Configuration, not a chunk problem. Halting leaves every
                        // queued chunk's audio untouched and says why, which is
                        // strictly better than burning a backlog at Whisper quality
                        // because a key was rotated.
                        gate.halt(outcome.reason)
                        if (!underPressure) return Attempt.Park(outcome.reason, PARK_LONG_MS)
                        provisional = true
                    }

                    is CloudTranscriber.CloudOutcome.Rejected -> {
                        // The server understood this audio and refused it. Nothing
                        // better will happen for it, so the weaker engine is now the
                        // best available answer rather than a downgrade. The piece
                        // rows go with it: they describe an attempt that is over, and
                        // a later retry must start from a clean plan.
                        Log.w(TAG, "chunk ${chunk.id}: server refused the audio (${outcome.reason})")
                        runCatching { db.cloudJobDao().clearForChunk(chunk.id) }
                    }
                }

                else -> {
                    if (!underPressure) return Attempt.Park(blocked, parkBackoff(null))
                    provisional = true
                }
            }
        }

        if (!engine.isLoaded) {
            // Naming the real problem matters most in the one case where the escape
            // valve has nothing to escape to: there is no on-device model, so the
            // backlog cannot be drained at any price and the recorder is walking
            // into its own low-storage pause.
            return Attempt.Park(
                if (underPressure) "storage is filling up and no on-device model is installed"
                else "waiting for a transcriber",
                PARK_MEDIUM_MS,
            )
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
            provisional = provisional,
        )
    }

    /**
     * Assembles a settled cloud run, covering any piece the server returned no text
     * for with the on-device engine.
     *
     * A whole-chunk rejection already falls through to that engine; a per-piece one
     * used to be filtered out of the results and offered to nothing, so those
     * seconds of speech reached no transcriber at all and the only record of them
     * was a `coveredMs` no query reads. Same audio, same refusal, same answer —
     * except that here only the refused span is re-decoded, and the pieces the
     * server did return are kept.
     *
     * Two different things arrive as "no text", and they part company over the WAV:
     * a piece the server *refused* is finished with, while a piece it kept losing is
     * still owed a real transcript. See [CloudTranscriber.PieceResult.retryable].
     */
    private suspend fun fromCloud(
        chunk: ChunkEntity,
        cfg: Settings,
        outcome: CloudTranscriber.CloudOutcome.Settled,
        audio: FloatArray,
        voicedMs: Long,
    ): Attempt.Text {
        val kept = outcome.pieces.filterNot { it.rejected }
        val refused = outcome.pieces.filter { it.rejected }

        val spoken = kept.filter { it.text.isNotBlank() }.map {
            Spoken(
                startMs = it.offsetMs,
                endMs = it.offsetMs + it.durationMs,
                text = it.text.trim(),
                language = it.language,
            )
        }.toMutableList()
        var coveredMs = kept.sumOf { it.durationMs }
        var elapsedMs = outcome.elapsedMs
        var covered = 0

        for (piece in refused) {
            Log.w(
                TAG,
                "chunk ${chunk.id}: no server text for the piece at ${piece.offsetMs / 1000}s " +
                    if (piece.retryable) "(the server kept losing it); covering it on device for now"
                    else "(the server refused it); covering it on device",
            )
            if (!engine.isLoaded) continue
            val slice = sliceOf(audio, piece.offsetMs, piece.durationMs)
            if (slice.isEmpty()) continue
            val attempt = engine.transcribe(slice, cfg.language.code)
            val ok = attempt.getOrNull()
            if (ok == null) {
                Log.w(
                    TAG,
                    "chunk ${chunk.id}: on-device engine could not cover the refused piece",
                    attempt.exceptionOrNull(),
                )
                continue
            }
            // Whisper's timestamps are relative to the slice it was handed.
            spoken += ok.segments.map {
                Spoken(piece.offsetMs + it.startMs, piece.offsetMs + it.endMs, it.text, ok.language)
            }
            coveredMs += piece.durationMs
            elapsedMs += ok.elapsedMs
            covered++
        }

        return Attempt.Text(
            spoken = spoken.sortedBy { it.startMs },
            source = if (covered > 0) TranscriptSource.MIXED else TranscriptSource.CLOUD,
            coveredMs = coveredMs.coerceAtMost(voicedMs),
            elapsedMs = elapsedMs,
            // Audio the server *refused* is finished with: asking again gets the same
            // answer, so nothing better is coming and the WAV is not owed. Audio it
            // kept losing is the opposite — the piece stopped being re-uploaded to
            // bound the bandwidth, not because anything was decided about the speech
            // in it — so that span is still owed a real transcript and its audio has
            // to survive until the server can give one.
            provisional = refused.any { it.retryable },
        )
    }

    /** A span of the compacted voiced stream, addressed the way `cloud_jobs` rows address it. */
    private fun sliceOf(audio: FloatArray, offsetMs: Long, durationMs: Long): FloatArray {
        val from = (offsetMs * AudioFormatSpec.SAMPLE_RATE / 1000L).toInt().coerceIn(0, audio.size)
        val to = (from + durationMs * AudioFormatSpec.SAMPLE_RATE / 1000L).toInt().coerceIn(from, audio.size)
        return audio.copyOfRange(from, to)
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

    /**
     * The one door every status write goes through, so that "what happens to the
     * audio" and "what happens to the server work" are answered once per outcome
     * rather than once per call site.
     *
     * @return false if the lease was gone, in which case nothing was written and
     *   the caller must not touch the audio either.
     */
    private suspend fun settle(
        chunk: ChunkEntity,
        lease: Long,
        status: ChunkStatus,
        attempts: Int,
        hold: String?,
        segments: List<SegmentEntity>? = null,
        error: String? = null,
        transcribeMs: Long? = null,
        speechRatio: Float = chunk.speechRatio,
        wordCount: Int = 0,
        transcriptSource: String? = null,
        voicedMs: Long = 0,
        coveredMs: Long = 0,
        notBefore: Long = 0,
    ): Boolean = try {
        db.transcriptDao().settle(
            chunkDao = db.chunkDao(),
            segmentDao = db.segmentDao(),
            cloudJobDao = db.cloudJobDao(),
            chunkId = chunk.id,
            lease = lease,
            segments = segments,
            attempts = attempts,
            error = error,
            transcribeMs = transcribeMs,
            speechRatio = speechRatio,
            wordCount = wordCount,
            status = status,
            transcriptSource = transcriptSource,
            voicedMs = voicedMs,
            coveredMs = coveredMs,
            audioHold = hold,
            notBefore = notBefore,
        )
        true
    } catch (lost: LeaseLostException) {
        Log.w(TAG, "chunk ${chunk.id}: lease lost before the write landed; discarding it", lost)
        false
    }

    private suspend fun commitSilent(
        chunk: ChunkEntity,
        lease: Long,
        attempts: Int,
        speechRatio: Float,
        file: File,
        cfg: Settings,
    ): Boolean {
        val hold = holdReason(attempt = null, voicedMs = 0, cfg = cfg)
        if (settle(
                chunk = chunk,
                lease = lease,
                status = ChunkStatus.SILENT,
                attempts = attempts,
                hold = hold,
                segments = emptyList(),
                transcribeMs = 0,
                speechRatio = speechRatio,
            )
        ) {
            releaseAudioIfProven(chunk.id, hold, file)
        }
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
        val hold = holdReason(attempt, voicedMs, cfg)

        // Segments, terminal status and the audio decision commit together,
        // conditional on still holding the lease. Only after this returns is it
        // safe to act on that decision.
        val landed = settle(
            chunk = chunk,
            lease = lease,
            status = ChunkStatus.DONE,
            attempts = attempts,
            hold = hold,
            segments = segments,
            transcribeMs = attempt.elapsedMs,
            speechRatio = speechRatio,
            wordCount = words,
            transcriptSource = attempt.source,
            voicedMs = voicedMs,
            coveredMs = attempt.coveredMs,
        )
        if (!landed) return

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
                if (hold != AudioHold.UNLINK_PENDING) " — audio kept: $hold" else "",
        )

        releaseAudioIfProven(chunk.id, hold, file)
    }

    /**
     * What is keeping this chunk's audio, or [AudioHold.UNLINK_PENDING] to release it.
     *
     * Always an answer, never an absence, because this is the value a later sweep
     * reads back: "no hold" and "no decision has ever been recorded here" have to be
     * distinguishable, or a startup pass cannot tell a crashed release apart from an
     * archive the user asked for.
     *
     * The order is the point.
     *
     * A hole outranks everything: those seconds of speech reached no engine at all,
     * so the WAV is the only copy of them that exists, and no disk policy makes
     * deleting it safe. The user's own request comes next — audio kept because Echo
     * was told to keep it is not Echo's to trade for space, and the disk valve, which
     * sees only rows, must not be able to reach it. (The cost is that such a chunk is
     * not listed for redo even when it holds a provisional transcript. Its audio is
     * intact, which is what was asked for.) Only then the provisional transcript,
     * which is the one hold [relieveAudioPressure] may spend, because a transcript
     * of that audio already exists and only its accuracy is at stake.
     *
     * Disk pressure deliberately does not appear here. It used to, as the first arm,
     * which meant a backlog large enough to trip the valve silently deleted audio
     * containing speech nothing had transcribed.
     *
     * @param attempt null for a chunk with no speech in it: there is no transcript,
     *   so neither the hole nor the provisional arm can apply. Silence goes through
     *   the same function rather than deciding two of these arms a second time.
     */
    private fun holdReason(
        attempt: Attempt.Text?,
        voicedMs: Long,
        cfg: Settings,
    ): String = when {
        attempt != null && attempt.coveredMs + HOLE_TOLERANCE_MS < voicedMs -> AudioHold.HOLE
        cfg.keepAudioAfterTranscription -> AudioHold.USER_REQUEST
        attempt != null && attempt.provisional -> AudioHold.DEGRADED
        else -> AudioHold.UNLINK_PENDING
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
        settle(
            chunk = chunk,
            lease = lease,
            // Audio is deliberately kept on FAILED so a bad run stays recoverable.
            status = if (terminal) ChunkStatus.FAILED else ChunkStatus.PENDING,
            attempts = attempts,
            hold = if (terminal) AudioHold.FAILED else null,
            // A stored transcript is left alone: this may be a redo of a chunk that
            // already has one, and a failed redo must not take the old text with it.
            segments = null,
            error = reason,
            speechRatio = speechRatio,
            voicedMs = voicedMs,
            notBefore = if (terminal) 0 else clock() + FAIL_BACKOFF_MS,
        )
        _state.value = _state.value.copy(lastError = reason)
    }

    /**
     * Retires a chunk that keeps taking the process down with it.
     *
     * FAILED rather than DISCARDED, and with the audio kept: nothing here knows
     * *why* the runs died — that is the whole reason [ChunkEntity.abandonedClaims]
     * exists — so the chunk goes behind the same retry button as any other failure
     * instead of being thrown away. `attempts` is left alone; a claim that recorded
     * nothing was never an attempt at anything.
     */
    private suspend fun retireCrashingChunk(chunk: ChunkEntity, lease: Long) {
        Log.e(
            TAG,
            "chunk ${chunk.id}: ${chunk.abandonedClaims} claims ended without an outcome; " +
                "retiring it so the queue can move",
        )
        settle(
            chunk = chunk,
            lease = lease,
            status = ChunkStatus.FAILED,
            attempts = chunk.attempts,
            hold = AudioHold.FAILED,
            error = "transcribing this repeatedly stopped Echo",
            voicedMs = chunk.voicedMs,
        )
        _state.value = _state.value.copy(lastError = "A chunk kept crashing and was set aside")
    }

    private suspend fun park(chunk: ChunkEntity, reason: String, backoffMs: Long) {
        val lease = chunk.claimedAt ?: return
        val wait = backoffMs.coerceIn(PARK_SHORT_MS, PARK_LONG_MS)
        db.chunkDao().deferChunk(
            id = chunk.id,
            lease = lease,
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

    /**
     * The single guarded unlink point. Nothing else in Echo deletes a WAV a chunk
     * row still points at.
     *
     * The argument is the row's own recorded [AudioHold] and nothing else — not the
     * settings, not the backend, not the shape of the row. Every unlink therefore
     * carries out a decision that was written down at the same instant as the
     * status it belongs to, which is the only version of this that a sweep running
     * at startup, with no memory of the run, can also get right.
     */
    private suspend fun releaseAudioIfProven(chunkId: Long, hold: String?, file: File) {
        if (hold != AudioHold.UNLINK_PENDING) return
        if (file.delete() || !file.exists()) {
            db.chunkDao().markAudioDeleted(chunkId)
        } else {
            Log.w(TAG, "could not delete ${file.name}")
        }
    }

    /**
     * Finishes releases that were decided but never carried out — the service was
     * killed in the window between committing the transcript and unlinking the file.
     * Nothing else ever revisits a terminal chunk, so without this sweep the WAV
     * stays forever and Echo quietly breaks its own promise to delete it.
     *
     * It resumes a decision; it never makes one. That distinction is the whole
     * design: this used to select rows with no hold recorded, which is also every
     * row written before the column existed — including the archive a previous
     * version was told to keep, deleted in a single pass on first launch.
     */
    private suspend fun releaseLeftoverAudio() {
        db.chunkDao().releasableLeftovers(AudioHold.UNLINK_PENDING).forEach { row ->
            val f = row.filePath?.let(::File) ?: return@forEach
            releaseAudioIfProven(row.id, AudioHold.UNLINK_PENDING, f)
            Log.i(TAG, "released leftover audio for chunk ${row.id}")
        }
    }

    /**
     * True when Echo's own retained audio is what is squeezing the device, which is
     * the only condition under which bypassing the good transcriber is better than
     * waiting for it.
     *
     * Two arms, because there are two ways for holding audio to cost more than it is
     * worth. The absolute cap stops Echo hoarding on a phone with room to spare. The
     * free-space arm is the one that matters in practice: the recorder pauses on
     * *free bytes*, so a cap denominated in *held bytes* could only ever open first
     * on a device with 2 GB free, and every phone below that — the ones the app
     * explicitly still records on — reached the pause with the valve shut.
     *
     * The conjunction on that arm is not a nicety. A phone can sit near its limit
     * because of everything else installed on it; releasing a hoard too small to lift
     * the recorder's pause spends audio and buys nothing, and the honest answer there
     * is the low-storage notice, not a worse transcript.
     */
    private suspend fun underAudioPressure(): Boolean {
        val retained = db.chunkDao().retainedAudioBytes(AudioHold.UNLINK_PENDING)
        if (retained > MAX_RETAINED_BYTES) return true
        if (retained < DiskSpace.RELIEVABLE_BYTES) return false
        return DiskSpace.freeBytes(appContext) < DiskSpace.PRESSURE_FREE_BYTES
    }

    /**
     * Spends held audio, oldest first, until the device is no longer squeezed.
     *
     * The other half of the escape valve, and deliberately a separate act from
     * transcribing. Deciding at commit time — "this chunk is being written under
     * pressure, so release its audio" — sacrifices whichever chunk happens to be in
     * hand, including one whose speech no engine ever saw. Here the choice is made
     * across the whole archive, over exactly one recorded reason
     * ([AudioHold.DEGRADED]): a transcript of that audio exists, so what is lost is
     * accuracy that the user can already see recorded as such, and what is bought is
     * the recorder staying alive. A hole and a copy the user asked for are out of
     * reach at any pressure; if those are all that is left, Echo stops releasing and
     * the recorder pauses with a notice, which is the outcome that can be undone.
     */
    private suspend fun relieveAudioPressure() {
        if (!underAudioPressure()) return
        val candidates = db.chunkDao().tradeableAudio(AudioHold.DEGRADED)
        if (candidates.isEmpty()) return

        var released = 0
        for (row in candidates) {
            val f = row.filePath?.let(::File) ?: continue
            if (f.delete() || !f.exists()) {
                db.chunkDao().markAudioDeleted(row.id)
                released++
                Log.w(
                    TAG,
                    "released audio for chunk ${row.id} to stay under the retention cap; " +
                        "its transcript is the on-device one",
                )
            }
            if (!underAudioPressure()) break
        }
        if (released > 0) Log.w(TAG, "released $released chunk(s) of held audio under storage pressure")
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

    /** The batch pipeline is usable: selected, and pointed at a service. */
    private fun usesBatch(cfg: Settings): Boolean =
        cfg.sttBackend == SttBackend.BATCH &&
            cfg.uploadUrl.startsWith("http") &&
            cfg.uploadKey.isNotBlank()

    private fun usesCloud(cfg: Settings): Boolean =
        cfg.sttBackend == SttBackend.CLOUD &&
            cfg.sttServerUrl.startsWith("http") &&
            CloudTranscriber.supports(cfg.language.code)

    private fun cloudFor(cfg: Settings): CloudTranscriber {
        // Compared as two fields rather than as one joined string. Any separator is
        // a character that could legitimately appear in a key, and the one that
        // provably cannot -- a NUL -- makes this source file read as binary, so
        // every grep over the repo silently skips the pipeline.
        val same = cloudUrl == cfg.sttServerUrl && cloudKey == cfg.sttApiKey
        cloud?.let { if (same) return it }
        // The settings that could have caused a halt just changed, so whatever was
        // wrong may well be fixed. Without this the cloud path stays dead until the
        // process restarts, however many times the user corrects the key.
        if (cloudUrl != null) gate.clearHalt()
        cloudUrl = cfg.sttServerUrl
        cloudKey = cfg.sttApiKey
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
