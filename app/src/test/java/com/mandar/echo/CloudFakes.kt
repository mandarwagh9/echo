package com.mandar.echo

import com.mandar.echo.data.ChunkDao
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.CloudJobDao
import com.mandar.echo.data.CloudJobEntity
import com.mandar.echo.data.CloudJobState
import com.mandar.echo.data.SegmentDao
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.stt.BatchTransport
import com.mandar.echo.stt.CloudTranscriber
import com.mandar.echo.stt.HttpReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope

/**
 * Hand-written stand-ins for the two seams the queue was built around: the DAOs
 * and [BatchTransport].
 *
 * Room cannot run on the JVM, so these reimplement the *shape* of each query in
 * memory. They are dependencies of the tests, never the subject of one: nothing
 * here proves the SQL is right, only that the code above it composes correctly.
 * Anything that can only be verified against real SQLite is called out in the
 * report rather than faked into a green test.
 */

// ---- the transcriber under test -------------------------------------------

/**
 * A [CloudTranscriber] wired to fakes and to the test scheduler's virtual clock, so
 * `delay` inside the poll loop costs no wall clock while the cadence it produces
 * stays observable in [ScriptedTransport.calls].
 */
internal class Rig(val clock: () -> Long) {
    val jobs = FakeCloudJobDao()
    val transport = ScriptedTransport(clock)
    val cloud = CloudTranscriber(jobs, transport, clock)
}

internal fun TestScope.rig() = Rig { testScheduler.currentTime }

/** 16 kHz mono, the format the whole pipeline speaks. The content is irrelevant here. */
internal fun voicedSeconds(seconds: Int): FloatArray = FloatArray(16_000 * seconds) { 0.1f }

// ---- transport ------------------------------------------------------------

internal fun healthBody(model: String = "indic-conformer-600m-multilingual") =
    """{"status":"ok","model":"$model","device":"cpu"}"""

internal fun acceptedBody(jobId: String, seconds: Double = 1.0) =
    """{"job_id":"$jobId","status":"queued","language":"hi-IN","audio_duration":$seconds}"""

internal fun queuedBody() = """{"status":"queued"}"""

internal fun processingBody() = """{"status":"processing"}"""

internal fun completedBody(transcript: String) =
    """{"job_id":"batch_1","status":"completed","transcript":"$transcript"}"""

/**
 * A [BatchTransport] that never opens a socket, answers from scripts, and records
 * *when* it was called in the test scheduler's virtual time — which is what makes
 * the poll cadence observable at all.
 */
internal class ScriptedTransport(private val now: () -> Long) : BatchTransport {

    data class Call(val path: String, val atMs: Long, val uploadedBytes: Int)

    val calls = mutableListOf<Call>()
    val languagesSent = mutableListOf<String>()

    val healthChecks: List<Call> get() = calls.filter { it.path == "/health" }
    val uploads: List<Call> get() = calls.filter { it.path == "/batch/transcribe" }
    val polls: List<Call> get() = calls.filter { it.path.startsWith("/batch/status/") }

    /** Samples the server was actually handed, per upload, recovered from the WAV header. */
    val uploadedSamples: List<Int> get() = uploads.map { (it.uploadedBytes - 44) / 2 }

    var health: HttpReply = HttpReply(200, healthBody())

    /** Called with the 1-based upload number. */
    var onSubmit: (Int) -> HttpReply = { n -> HttpReply(201, acceptedBody("batch_$n")) }

    /** Called with the job id and the 1-based poll number *for that job*. */
    var onPoll: (String, Int) -> HttpReply = { _, _ -> HttpReply(200, completedBody("")) }

    private val pollCounts = HashMap<String, Int>()

    override suspend fun get(path: String, stallMs: Long): HttpReply {
        calls += Call(path, now(), 0)
        if (path == "/health") return health
        val jobId = path.substringAfterLast('/')
        val n = (pollCounts[jobId] ?: 0) + 1
        pollCounts[jobId] = n
        return onPoll(jobId, n)
    }

    override suspend fun postAudio(
        path: String,
        wav: ByteArray,
        languageCode: String,
        replyStallMs: Long,
    ): HttpReply {
        calls += Call(path, now(), wav.size)
        languagesSent += languageCode
        return onSubmit(uploads.size)
    }
}

// ---- cloud_jobs -----------------------------------------------------------

internal class FakeCloudJobDao : CloudJobDao {

    private val rows = LinkedHashMap<Pair<Long, Int>, CloudJobEntity>()

    /**
     * Stands in for the JOIN against `chunks`: a SUBMITTED row whose chunk has gone
     * terminal is owed to nobody and must not gate the queue.
     */
    var chunkIsLive: (Long) -> Boolean = { true }

    override suspend fun upsert(job: CloudJobEntity) {
        rows[job.chunkId to job.pieceIndex] = job
    }

    override suspend fun forChunk(chunkId: Long): List<CloudJobEntity> =
        rows.values.filter { it.chunkId == chunkId }.sortedBy { it.pieceIndex }

    override suspend fun clearForChunk(chunkId: Long) {
        rows.keys.removeAll { it.first == chunkId }
    }

    override suspend fun outstandingChunkId(): Long? =
        rows.values.firstOrNull { it.state == CloudJobState.SUBMITTED && chunkIsLive(it.chunkId) }
            ?.chunkId

    fun seed(vararg jobs: CloudJobEntity) {
        jobs.forEach { rows[it.chunkId to it.pieceIndex] = it }
    }

    fun all(): List<CloudJobEntity> = rows.values.toList()
}

// ---- chunks ---------------------------------------------------------------

/**
 * In-memory `chunks`. Only the statements the tests actually exercise are
 * faithful; each is written to mirror its WHERE clause, in particular that
 * `claimedAt = :lease` never matches a NULL lease.
 */
internal class FakeChunkDao : ChunkDao() {

    val rows = LinkedHashMap<Long, ChunkEntity>()
    private var nextId = 1L

    fun put(chunk: ChunkEntity): ChunkEntity {
        val id = if (chunk.id == 0L) nextId++ else chunk.id.also { nextId = maxOf(nextId, it + 1) }
        val stored = chunk.copy(id = id)
        rows[id] = stored
        return stored
    }

    override suspend fun insert(chunk: ChunkEntity): Long = put(chunk).id

    override suspend fun closeChunk(id: Long, endedAt: Long, sampleCount: Long, status: ChunkStatus) {
        rows[id]?.let { rows[id] = it.copy(endedAt = endedAt, sampleCount = sampleCount, status = status) }
    }

    override suspend fun reclaimStuckUploads(cutoff: Long): Int {
        val hit = rows.values.filter {
            it.status == ChunkStatus.UPLOADED && it.filePath != null && it.startedAt < cutoff
        }
        hit.forEach {
            rows[it.id] = it.copy(
                status = ChunkStatus.PENDING,
                audioHold = null,
                notBefore = 0,
                transientFailures = it.transientFailures + 1,
            )
        }
        return hit.size
    }

    override fun awaitingRemoteCount(): kotlinx.coroutines.flow.Flow<Int> =
        kotlinx.coroutines.flow.flowOf(rows.values.count { it.status == ChunkStatus.UPLOADED })

    /** Mirrors the WHERE clause: only UPLOADED rows, oldest first. */
    override suspend fun uploadedChunks(limit: Int): List<ChunkEntity> =
        rows.values
            .filter { it.status == ChunkStatus.UPLOADED }
            .sortedBy { it.startedAt }
            .take(limit)

    /**
     * Faithful to the statement, including `AND status = 'UPLOADED'` — a fake
     * that ignored that guard would hide a double-commit rather than catch it.
     */
    override suspend fun completeRemote(
        id: Long,
        status: ChunkStatus,
        wordCount: Int,
        coveredMs: Long,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.status != ChunkStatus.UPLOADED) return 0
        rows[id] = row.copy(
            status = status,
            wordCount = wordCount,
            coveredMs = coveredMs,
            audioHold = null,
            error = null,
        )
        return 1
    }

    override suspend fun nextClaimableId(now: Long): Long? =
        rows.values
            .filter { it.status == ChunkStatus.PENDING && it.notBefore <= now }
            .sortedWith(compareByDescending<ChunkEntity> { holdsSubmittedJob(it.id) }.thenBy { it.startedAt })
            .firstOrNull()
            ?.id

    /** Set by a test that wants the "collect results before starting new work" ordering. */
    var holdsSubmittedJob: (Long) -> Boolean = { false }

    override suspend fun markClaimed(id: Long, now: Long) {
        rows[id]?.let { rows[id] = it.copy(status = ChunkStatus.TRANSCRIBING, claimedAt = now, claims = it.claims + 1) }
    }

    override suspend fun deferChunk(
        id: Long,
        lease: Long,
        notBefore: Long,
        transientFailures: Int,
        error: String?,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.claimedAt != lease) return 0
        rows[id] = row.copy(
            status = ChunkStatus.PENDING,
            claimedAt = null,
            notBefore = notBefore,
            abandonedClaims = 0,
            transientFailures = transientFailures,
            error = error,
        )
        return 1
    }

    override suspend fun requeueStale(cutoff: Long): Int {
        var n = 0
        rows.values.filter { row ->
            val claimed = row.claimedAt
            row.status == ChunkStatus.TRANSCRIBING && (claimed == null || claimed < cutoff)
        }
            .forEach {
                rows[it.id] = it.copy(
                    status = ChunkStatus.PENDING,
                    claimedAt = null,
                    abandonedClaims = it.abandonedClaims + 1,
                )
                n++
            }
        return n
    }

    override suspend fun abandonedRecording(before: Long): List<ChunkEntity> =
        rows.values.filter { it.status == ChunkStatus.RECORDING && it.startedAt < before }

    override suspend fun byId(id: Long): ChunkEntity? = rows[id]

    override fun pendingCount(): Flow<Int> = flowOf(pending().size)

    override suspend fun pendingCountNow(): Int = pending().size

    override suspend fun claimableCountNow(now: Long): Int =
        rows.values.count { it.status == ChunkStatus.PENDING && it.notBefore <= now }

    override suspend fun nextClaimableAt(now: Long): Long? =
        rows.values.filter { it.status == ChunkStatus.PENDING && it.notBefore > now }
            .minOfOrNull { it.notBefore }

    override fun failedChunks(): Flow<List<ChunkEntity>> =
        flowOf(rows.values.filter { it.status == ChunkStatus.FAILED })

    override suspend fun retryAllFailed(): Int {
        var n = 0
        rows.values.filter { it.status == ChunkStatus.FAILED && it.filePath != null }.forEach {
            rows[it.id] = it.copy(
                status = ChunkStatus.PENDING, attempts = 0, claims = 0, abandonedClaims = 0,
                transientFailures = 0, notBefore = 0, claimedAt = null, error = null,
            )
            n++
        }
        return n
    }

    override suspend fun discardFailedWithoutAudio(): Int {
        var n = 0
        rows.values.filter { it.status == ChunkStatus.FAILED && it.filePath == null }.forEach {
            rows[it.id] = it.copy(status = ChunkStatus.DISCARDED)
            n++
        }
        return n
    }

    override suspend fun requeueRedoable(holds: List<String>): Int {
        var n = 0
        redoable(holds).forEach {
            rows[it.id] = it.copy(
                status = ChunkStatus.PENDING, attempts = 0, abandonedClaims = 0,
                transientFailures = 0, notBefore = 0, claimedAt = null, error = null,
            )
            n++
        }
        return n
    }

    override fun redoableCount(holds: List<String>): Flow<Int> = flowOf(redoable(holds).size)

    override suspend fun tradeableAudio(hold: String): List<ChunkEntity> =
        rows.values
            .filter { it.status.settledWithAudio() && it.filePath != null && it.audioHold == hold }
            .sortedBy { it.startedAt }

    override suspend fun finishChunk(
        id: Long,
        lease: Long,
        status: ChunkStatus,
        attempts: Int,
        error: String?,
        transcribeMs: Long?,
        speechRatio: Float,
        wordCount: Int,
        transcriptSource: String?,
        voicedMs: Long,
        coveredMs: Long,
        audioHold: String?,
        notBefore: Long,
    ): Int {
        val row = rows[id] ?: return 0
        if (row.claimedAt != lease) return 0
        rows[id] = row.copy(
            status = status,
            attempts = attempts,
            error = error,
            transcribeMs = transcribeMs,
            speechRatio = speechRatio,
            wordCount = wordCount,
            transcriptSource = transcriptSource,
            voicedMs = voicedMs,
            coveredMs = coveredMs,
            audioHold = audioHold,
            claimedAt = null,
            notBefore = notBefore,
            abandonedClaims = 0,
        )
        return 1
    }

    override suspend fun setStatus(id: Long, status: ChunkStatus) {
        rows[id]?.let { rows[id] = it.copy(status = status) }
    }

    override suspend fun markAudioDeleted(id: Long) {
        rows[id]?.let { rows[id] = it.copy(filePath = null, audioDeleted = true, audioHold = null) }
    }

    override suspend fun releasableLeftovers(hold: String): List<ChunkEntity> =
        rows.values.filter { it.status.settledWithAudio() && it.filePath != null && it.audioHold == hold }

    override suspend fun chunksBetween(from: Long, to: Long): List<ChunkEntity> =
        rows.values.filter { it.startedAt in from..to }.sortedBy { it.startedAt }

    override fun chunksBetweenFlow(from: Long, to: Long): Flow<List<ChunkEntity>> =
        flowOf(rows.values.filter { it.startedAt in from..to }.sortedBy { it.startedAt })

    override suspend fun unsettledBetween(from: Long, to: Long): Int =
        rows.values.count {
            it.startedAt in from..to &&
                it.status in setOf(ChunkStatus.RECORDING, ChunkStatus.PENDING, ChunkStatus.TRANSCRIBING)
        }

    override suspend fun chunksWithAudio(): List<ChunkEntity> = rows.values.filter { it.filePath != null }

    override suspend fun retainedAudioBytes(unlinkPending: String): Long =
        rows.values
            .filter { it.filePath != null && it.audioHold != null && it.audioHold != unlinkPending }
            .sumOf { it.sampleCount * 2 + 44 }

    override suspend fun samplesBetween(from: Long, to: Long): Long =
        rows.values.filter { it.startedAt in from..to }.sumOf { it.sampleCount }

    /**
     * Mirrors the statement, `sampleCount > 0` included: a chunk that was opened
     * and never written to is a row in the table but is not a day you recorded,
     * and a fake that listed it anyway would hide that.
     */
    override fun recordedDays(utcOffsetMs: Long, limit: Int): Flow<List<Long>> =
        flowOf(
            rows.values
                .filter { it.sampleCount > 0 }
                .map { (it.startedAt + utcOffsetMs) / 86_400_000L }
                .distinct()
                .sortedDescending()
                .take(limit)
        )

    override suspend fun deleteAll() = rows.clear()

    private fun pending() =
        rows.values.filter { it.status == ChunkStatus.PENDING || it.status == ChunkStatus.TRANSCRIBING }

    private fun redoable(holds: List<String>) =
        rows.values.filter { row ->
            row.status.settledWithAudio() && row.filePath != null && holds.any { it == row.audioHold }
        }

    private fun ChunkStatus.settledWithAudio() = this == ChunkStatus.DONE || this == ChunkStatus.SILENT
}

// ---- segments -------------------------------------------------------------

internal class FakeSegmentDao : SegmentDao {

    /** Every call, in order, so a test can pin that a delete preceded an insert. */
    val ops = mutableListOf<String>()
    val stored = mutableListOf<SegmentEntity>()

    override suspend fun insertAll(segments: List<SegmentEntity>) {
        ops += "insert(${segments.size})"
        stored += segments
    }

    override suspend fun between(from: Long, to: Long): List<SegmentEntity> =
        stored.filter { it.startMs in from..to }

    override fun betweenFlow(from: Long, to: Long): Flow<List<SegmentEntity>> =
        flowOf(stored.filter { it.startMs in from..to })

    override suspend fun forChunk(chunkId: Long): List<SegmentEntity> = stored.filter { it.chunkId == chunkId }

    override fun search(query: String, limit: Int): Flow<List<SegmentEntity>> =
        flowOf(stored.filter { it.text.contains(query) }.take(limit))

    override fun recent(limit: Int): Flow<List<SegmentEntity>> = flowOf(stored.takeLast(limit))

    override suspend fun deleteForChunk(chunkId: Long) {
        ops += "delete($chunkId)"
        stored.removeAll { it.chunkId == chunkId }
    }
}
