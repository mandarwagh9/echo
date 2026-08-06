package com.mandar.echo.stt

import android.util.Log
import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.data.CloudJobDao
import com.mandar.echo.data.CloudJobEntity
import com.mandar.echo.data.CloudJobState
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

private const val TAG = "CloudTranscriber"

/**
 * Transcribes through a self-hosted vexyl-stt server, which wraps AI4Bharat's
 * IndicConformer-600M.
 *
 * This exists because the on-device path cannot do the job. Measured on the same
 * fixtures: Whisper Base recovers 0.23 of Hindi words and 0.00 of Marathi, Small
 * manages 0.54 and 0.13, and IndicConformer returns both verbatim. A 600M
 * conformer will not run on a phone, so the trade is explicit — accuracy for
 * sending audio to a server the user owns.
 *
 * The unit of durable work is a **piece**, not this coroutine. A chunk longer than
 * [MAX_PIECE_SECONDS] is split, and each piece is a row in `cloud_jobs`, so
 * yielding the worker resumes the job the server is already running instead of
 * re-uploading megabytes it is still holding.
 *
 * The server returns no timestamps at all — one string per job — so a piece
 * becomes exactly one segment, positioned by the piece's own offset.
 */
class CloudTranscriber internal constructor(
    private val jobs: CloudJobDao,
    private val transport: BatchTransport,
    private val clock: () -> Long,
) {

    constructor(baseUrl: String, apiKey: String, jobs: CloudJobDao) :
        this(jobs, HttpUrlTransport(baseUrl.trimEnd('/'), apiKey), System::currentTimeMillis)

    companion object {
        /**
         * Server-sized piece. The server's real cap is 300 s
         * (BATCH_MAX_AUDIO_DURATION); 120 s sits well under it and halves both the
         * cost of a retry and the float32 PCM the server pins per job (7.7 MB
         * rather than 15.4 MB on a 4 GiB instance) versus the old 240 s.
         */
        const val MAX_PIECE_SECONDS = 120

        /**
         * ~120 s, matching the server's own startup budget on Cloud Run
         * (--startup-probe-period=10 x --startup-probe-failure-threshold=12).
         *
         * Waiting longer than the platform waits is not patience, it is hiding a
         * crash loop: past this point Cloud Run has already killed and restarted
         * the container, so "still warming" is not a state that exists.
         */
        const val WAKE_STALL_MS = 120_000L

        /** One GET /batch/status/{id}. */
        const val POLL_STALL_MS = 20_000L

        /**
         * Never poll faster than this.
         *
         * The server sets `Connection: close` on every response, so HttpURLConnection
         * can never reuse a socket and each poll costs a full TCP plus TLS
         * handshake. The old 3 s cadence pinned the radio in RRC_CONNECTED on a
         * device that runs 24/7 and produced nothing but log spam.
         */
        const val POLL_MIN_MS = 5_000L

        /**
         * Cap. Well inside Cloud Run's scale-in window, because on a
         * --min-instances=0 service the poll *is* the keep-alive: an instance with
         * no in-flight request is a scale-in candidate, and `_batch_jobs` is an
         * in-process dict with no persistence, so reclamation destroys the job
         * mid-inference.
         */
        const val POLL_MAX_MS = 15_000L

        /**
         * IndicConformer on a 2 vCPU CPU-only instance runs at roughly 2-3x
         * realtime, so a piece cannot possibly be ready before about a third of its
         * own duration. Waiting that long first turns ~40 pointless round trips per
         * piece into about six.
         */
        const val FIRST_POLL_FRACTION = 0.35

        /** A job the server is still holding after this is wedged, not slow. Scaled by piece length. */
        const val POLL_CEILING_FACTOR = 8

        const val POLL_CEILING_FLOOR_MS = 5 * 60_000L

        /** Consecutive resubmits after a JobGone before a piece stops re-uploading and parks. */
        const val MAX_CONSECUTIVE_RESUBMITS = 2

        /**
         * Cloud languages. English is deliberately absent: the server's batch path
         * never validates language_code and coerces anything not in its map to
         * Malayalam, and "en-IN" is not in the map. That failure *looks* like
         * success — HTTP 200, non-empty text, stored and labelled English — so the
         * only safe fix from a client that cannot edit the server is never to send
         * it. Whisper is fine at English anyway; it is the Indic languages it fails.
         */
        private val SUPPORTED = setOf("hi", "mr", "auto")

        fun supports(language: String): Boolean = language in SUPPORTED

        /**
         * The short code actually sent, which is what a segment must be labelled
         * with. The server echoes the posted code straight back, so treating its
         * reply as a detection result was fiction written into every stored row;
         * and storing the unresolved setting would put an "auto" bucket in the
         * day's language histogram.
         */
        fun resolvedLanguage(code: String): String = if (code == "mr") "mr" else "hi"

        /** The server has no auto mode; Hindi is the right single default for a code-switched day. */
        fun serverLanguage(code: String): String = if (code == "mr") "mr-IN" else "hi-IN"
    }

    /** One piece's finished text, positioned in the compacted voiced stream. */
    data class PieceResult(
        val offsetMs: Long,
        val durationMs: Long,
        val text: String,
        /**
         * Short code for the language this piece was actually posted as, read back
         * off the row rather than off the current setting. A language changed while
         * the chunk was parked must not relabel text the server already returned.
         */
        val language: String,
        val rejected: Boolean,
    )

    sealed interface CloudOutcome {
        /** Every piece reached a terminal state. Some may be [PieceResult.rejected]. */
        data class Settled(val pieces: List<PieceResult>, val elapsedMs: Long) : CloudOutcome

        /**
         * Yield the worker. The `cloud_jobs` rows persist, so resuming continues the
         * same jobs rather than re-uploading.
         *
         * @param progressed the server did something for us this round, so the
         *   caller must not spend the cloud's patience on it.
         */
        data class Park(val reason: String, val progressed: Boolean, val offline: Boolean) : CloudOutcome

        /** Credentials or URL are wrong. Halts the whole cloud path; never fails one chunk. */
        data class Halt(val reason: String) : CloudOutcome

        /** The server understood and refused. Nothing better will happen for this audio. */
        data class Rejected(val reason: String) : CloudOutcome
    }

    /**
     * Drives [chunkId]'s pieces as far as they will go this round.
     *
     * @param voiced the compacted voiced stream, 16 kHz mono in -1..1
     * @param fingerprint identifies that stream; stored rows cut from a different
     *   one are discarded rather than reused against audio they do not describe
     */
    suspend fun run(
        chunkId: Long,
        voiced: FloatArray,
        language: String,
        fingerprint: Long,
    ): CloudOutcome {
        val started = clock()
        if (voiced.isEmpty()) return CloudOutcome.Settled(emptyList(), 0)

        when (val health = awaken()) {
            is HealthOutcome.Awake -> Unit
            is HealthOutcome.NotOurServer ->
                return CloudOutcome.Park(health.reason, progressed = false, offline = false)
            is HealthOutcome.Unreachable ->
                return CloudOutcome.Park(health.reason, progressed = false, offline = false)
        }

        val rows = planPieces(chunkId, voiced, language, fingerprint)
        var progressed = false

        for (row in rows) {
            if (row.state == CloudJobState.COMPLETED || row.state == CloudJobState.REJECTED) continue

            // One outstanding job at a time. The server is strictly serial -- one
            // batch worker behind a global inference lock -- so a second job cannot
            // start until the first finishes, and submitting one anyway just makes
            // both look stuck while multiplying the PCM it has to hold resident.
            val outstanding = jobs.outstandingChunkId()
            if (outstanding != null && outstanding != chunkId) {
                return CloudOutcome.Park(
                    "another chunk is holding the server's only worker",
                    progressed = progressed,
                    offline = false,
                )
            }

            when (val step = advance(row, voiced)) {
                is Step.Done -> progressed = true
                is Step.Park -> return CloudOutcome.Park(
                    step.reason,
                    progressed = progressed || step.progressed,
                    offline = false,
                )
                is Step.Halt -> return CloudOutcome.Halt(step.reason)
                is Step.Fatal -> return CloudOutcome.Rejected(step.reason)
            }
        }

        val finished = jobs.forChunk(chunkId)
        if (finished.any { it.state != CloudJobState.COMPLETED && it.state != CloudJobState.REJECTED }) {
            return CloudOutcome.Park("pieces still outstanding", progressed = progressed, offline = false)
        }
        return CloudOutcome.Settled(finished.map(::toResult), clock() - started)
    }

    /** Pieces already finished, so a fallback can keep them instead of re-decoding the chunk. */
    suspend fun completedPieces(chunkId: Long, fingerprint: Long): List<PieceResult> =
        jobs.forChunk(chunkId)
            .filter { it.streamFingerprint == fingerprint && it.state == CloudJobState.COMPLETED }
            .map(::toResult)

    suspend fun forget(chunkId: Long) = jobs.clearForChunk(chunkId)

    // ---- the piece state machine -------------------------------------------

    private sealed interface Step {
        data object Done : Step
        data class Park(val reason: String, val progressed: Boolean) : Step
        data class Halt(val reason: String) : Step
        data class Fatal(val reason: String) : Step
    }

    /**
     * NEW ─submit─► SUBMITTED ─poll─► COMPLETED | REJECTED
     *   ▲                │
     *   └── JobGone ─────┘        (bounded resubmits; the instance died or the TTL expired)
     *
     * Terminal states are COMPLETED (text, possibly "") and REJECTED. Nothing else.
     */
    private suspend fun advance(row: CloudJobEntity, voiced: FloatArray): Step {
        var current = row
        if (current.state == CloudJobState.NEW) {
            when (val submitted = submit(current, voiced)) {
                is Step.Done -> current = jobs.forChunk(current.chunkId)
                    .firstOrNull { it.pieceIndex == current.pieceIndex } ?: return Step.Done
                else -> return submitted
            }
            // A submit can settle a piece outright — the server understood the
            // upload and refused it. Falling through to the poll then asks about a
            // job id that was never issued, and the piece surfaces as "submitted
            // with no job id": a real, already-recorded rejection reported under a
            // reason that names the wrong problem.
            if (current.state != CloudJobState.SUBMITTED) return Step.Done
        }
        return pollToTerminal(current)
    }

    private suspend fun submit(row: CloudJobEntity, voiced: FloatArray): Step {
        val wav = wavBytes(slice(voiced, row))
        Log.i(TAG, "chunk ${row.chunkId} piece ${row.pieceIndex}: uploading ${wav.size / 1024} KB")

        val reply = transport.postAudio("/batch/transcribe", wav, row.languageSent, WAKE_STALL_MS)
        return when (val outcome = interpretSubmit(reply.code, reply.body)) {
            is SubmitOutcome.Accepted -> {
                jobs.upsert(
                    row.copy(
                        jobId = outcome.jobId,
                        state = CloudJobState.SUBMITTED,
                        submittedAt = clock(),
                        // Measures *consecutive* futile resubmits, not lifetime ones.
                        // A full round trip is proof the previous cycle worked, and a
                        // lifetime cap silently retires chunks on a service that
                        // legitimately recycles instances several times an hour.
                        resubmits = 0,
                    )
                )
                Step.Done
            }
            is SubmitOutcome.Unauthorized -> Step.Halt(outcome.reason)
            is SubmitOutcome.Misconfigured -> Step.Halt(outcome.reason)
            is SubmitOutcome.Rejected -> {
                jobs.upsert(row.copy(state = CloudJobState.REJECTED, error = outcome.reason))
                Step.Done
            }
            is SubmitOutcome.Malformed -> Step.Fatal("server sent an unrecognised reply to the upload")
            is SubmitOutcome.Overloaded -> Step.Park(outcome.reason, progressed = false)
            is SubmitOutcome.Unreachable -> Step.Park(outcome.reason, progressed = false)
        }
    }

    private suspend fun pollToTerminal(row: CloudJobEntity): Step {
        val jobId = row.jobId ?: return Step.Park("submitted with no job id", progressed = false)
        val pieceSeconds = row.durationMs / 1000.0
        val ceiling = maxOf(POLL_CEILING_FLOOR_MS, (pieceSeconds * POLL_CEILING_FACTOR * 1000).toLong())
        val deadline = clock() + ceiling

        var wait = (pieceSeconds * FIRST_POLL_FRACTION * 1000).toLong().coerceIn(POLL_MIN_MS, POLL_MAX_MS)
        var sawProcessing = false

        while (true) {
            delay(jitter(wait))
            val reply = transport.get("/batch/status/$jobId", POLL_STALL_MS)

            when (val outcome = interpretPoll(reply.code, reply.body)) {
                is PollOutcome.Completed -> {
                    jobs.upsert(row.copy(state = CloudJobState.COMPLETED, transcript = outcome.transcript))
                    val words = outcome.transcript.trim()
                    Log.i(
                        TAG,
                        "chunk ${row.chunkId} piece ${row.pieceIndex}: " +
                            if (words.isEmpty())
                                "server returned no words for ${"%.0f".format(pieceSeconds)} s of voiced audio"
                            else "${words.length} chars",
                    )
                    return Step.Done
                }

                is PollOutcome.Failed -> {
                    jobs.upsert(row.copy(state = CloudJobState.REJECTED, error = outcome.reason))
                    return Step.Done
                }

                is PollOutcome.Malformed -> {
                    jobs.upsert(row.copy(state = CloudJobState.REJECTED, error = "unrecognised server reply"))
                    return Step.Done
                }

                is PollOutcome.JobGone -> return resubmitAfterLoss(row, ceiling)

                is PollOutcome.Unauthorized -> return Step.Halt(outcome.reason)

                is PollOutcome.Unreachable ->
                    return Step.Park(outcome.reason, progressed = sawProcessing)

                // Queued and Processing are the server working. Neither is a
                // failure, and neither may escalate backoff -- a counter meant to
                // mean "the server is not coming back" must not be incremented by
                // the server behaving normally.
                PollOutcome.Queued, PollOutcome.Processing -> {
                    if (outcome == PollOutcome.Processing) sawProcessing = true
                    if (clock() > deadline) {
                        return Step.Park(
                            "server still working after ${ceiling / 60_000} min",
                            progressed = true,
                        )
                    }
                    wait = (wait * 3 / 2).coerceAtMost(POLL_MAX_MS)
                }
            }
        }
    }

    private suspend fun resubmitAfterLoss(row: CloudJobEntity, ceiling: Long): Step {
        val age = clock() - row.submittedAt
        // The server evicts a finished job an hour after completion. A 404 on a
        // young job is a lost instance; on an old one it is our own scheduling,
        // and saying so is the difference between a known cost and a livelock.
        if (age > 3_600_000L) {
            Log.w(TAG, "chunk ${row.chunkId} piece ${row.pieceIndex}: job expired after ${age / 60_000} min")
        } else {
            Log.i(TAG, "chunk ${row.chunkId} piece ${row.pieceIndex}: server lost the job; resubmitting")
        }

        if (row.resubmits >= MAX_CONSECUTIVE_RESUBMITS) {
            jobs.upsert(row.copy(state = CloudJobState.NEW, jobId = null))
            return Step.Park("server keeps losing the job", progressed = false)
        }
        jobs.upsert(row.copy(state = CloudJobState.NEW, jobId = null, resubmits = row.resubmits + 1))
        return Step.Park("resubmitting after the server lost the job", progressed = false)
    }

    // ---- planning ----------------------------------------------------------

    /**
     * Loads this chunk's piece rows, rebuilding them if the voiced stream they were
     * cut from is not the one in hand.
     */
    private suspend fun planPieces(
        chunkId: Long,
        voiced: FloatArray,
        language: String,
        fingerprint: Long,
    ): List<CloudJobEntity> {
        val existing = jobs.forChunk(chunkId)
        if (existing.isNotEmpty() && existing.all { it.streamFingerprint == fingerprint }) return existing
        if (existing.isNotEmpty()) {
            Log.w(TAG, "chunk $chunkId: voiced stream changed under stored pieces; discarding them")
            jobs.clearForChunk(chunkId)
        }

        val perPiece = MAX_PIECE_SECONDS * AudioFormatSpec.SAMPLE_RATE
        val sent = serverLanguage(language)
        val rows = ArrayList<CloudJobEntity>()
        var offset = 0
        var index = 0
        while (offset < voiced.size) {
            val end = minOf(offset + perPiece, voiced.size)
            rows += CloudJobEntity(
                chunkId = chunkId,
                pieceIndex = index,
                offsetMs = offset * 1000L / AudioFormatSpec.SAMPLE_RATE,
                durationMs = (end - offset) * 1000L / AudioFormatSpec.SAMPLE_RATE,
                languageSent = sent,
                streamFingerprint = fingerprint,
            )
            offset = end
            index++
        }
        rows.forEach { jobs.upsert(it) }
        return rows
    }

    private fun slice(voiced: FloatArray, row: CloudJobEntity): FloatArray {
        val from = (row.offsetMs * AudioFormatSpec.SAMPLE_RATE / 1000L).toInt()
        val to = (from + row.durationMs * AudioFormatSpec.SAMPLE_RATE / 1000L).toInt()
        return voiced.copyOfRange(from.coerceIn(0, voiced.size), to.coerceIn(from, voiced.size))
    }

    private fun toResult(row: CloudJobEntity) = PieceResult(
        offsetMs = row.offsetMs,
        durationMs = row.durationMs,
        text = row.transcript.orEmpty(),
        language = if (row.languageSent.startsWith("mr")) "mr" else "hi",
        rejected = row.state == CloudJobState.REJECTED,
    )

    private suspend fun awaken(): HealthOutcome {
        // /health is served before the API-key check and returns as soon as the
        // container is listening, so a cold start is absorbed by a request carrying
        // no payload rather than by holding 3.8 MB half-open across a container
        // boot. It proves reachability and nothing else: a green probe is not proof
        // the key is right.
        val reply = transport.get("/health", WAKE_STALL_MS)
        return interpretHealth(reply.code, reply.body)
    }

    /** +/-20%, so eighteen parked chunks do not resume in lockstep against one server. */
    private fun jitter(ms: Long): Long =
        (ms * (0.8 + Random.nextDouble() * 0.4)).toLong().coerceAtLeast(500)

    /** 16-bit PCM WAV, which is what the server accepts. */
    private fun wavBytes(samples: FloatArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        val sr = AudioFormatSpec.SAMPLE_RATE

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sr); putInt(sr * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        out.write(header.array())

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            body.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.write(body.array())
        return out.toByteArray()
    }
}
