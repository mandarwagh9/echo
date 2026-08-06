package com.mandar.echo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChunkDao {

    @Insert
    abstract suspend fun insert(chunk: ChunkEntity): Long

    @Query("UPDATE chunks SET endedAt = :endedAt, sampleCount = :sampleCount, status = :status WHERE id = :id")
    abstract suspend fun closeChunk(id: Long, endedAt: Long, sampleCount: Long, status: ChunkStatus)

    /**
     * Claims the next eligible chunk and returns the row that was claimed.
     *
     * One transaction, on purpose. Claiming and fetching as two statements can
     * address different rows whenever a stale TRANSCRIBING row exists: the update
     * claims the oldest PENDING chunk while the fetch returns the oldest
     * TRANSCRIBING one. That leaks an unowned claim every iteration and, once the
     * backlog drains, strands a pre-claimed chunk in TRANSCRIBING forever behind a
     * permanent phantom backlog in the UI.
     */
    @Transaction
    open suspend fun claimNext(now: Long): ChunkEntity? {
        val id = nextClaimableId(now) ?: return null
        markClaimed(id, now)
        return byId(id)
    }

    /**
     * Chunks holding a job the server is already running come first, then oldest.
     *
     * That ordering is not a nicety. The server evicts a finished job an hour after
     * it completes, so a client that wanders off to start new uploads can arrive
     * back to a 404, re-upload, and lengthen the sweep — a livelock in which the
     * server keeps producing correct transcripts and none is ever collected.
     * Results are always collected before new work is started.
     */
    @Query(
        """
        SELECT id FROM chunks
        WHERE status = 'PENDING' AND notBefore <= :now
        ORDER BY
            (SELECT COUNT(*) FROM cloud_jobs
             WHERE cloud_jobs.chunkId = chunks.id AND cloud_jobs.state = 'SUBMITTED') DESC,
            startedAt ASC
        LIMIT 1
        """
    )
    abstract suspend fun nextClaimableId(now: Long): Long?

    @Query("UPDATE chunks SET status = 'TRANSCRIBING', claimedAt = :now, claims = claims + 1 WHERE id = :id")
    abstract suspend fun markClaimed(id: Long, now: Long)

    /**
     * Parks a chunk: back to PENDING, ineligible until [notBefore], lease released.
     *
     * `attempts` is deliberately untouched. A park is not an attempt at anything —
     * treating a cold server or a tunnel as a failed attempt is what let three
     * network blips permanently downgrade a chunk to the on-device engine.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', claimedAt = NULL, notBefore = :notBefore,
            transientFailures = :transientFailures, error = :error
        WHERE id = :id
        """
    )
    abstract suspend fun deferChunk(id: Long, notBefore: Long, transientFailures: Int, error: String?)

    /**
     * Recovers leases that are provably abandoned: nobody can still be holding a
     * claim taken before [cutoff]. Bumps [ChunkEntity.abandonedClaims], never
     * `attempts`, because this only ever fires when a run ended without recording
     * any outcome.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', claimedAt = NULL, abandonedClaims = abandonedClaims + 1
        WHERE status = 'TRANSCRIBING' AND (claimedAt IS NULL OR claimedAt < :cutoff)
        """
    )
    abstract suspend fun requeueStale(cutoff: Long): Int

    /** Rows the chunker was mid-write on when the process died. */
    @Query("SELECT * FROM chunks WHERE status = 'RECORDING' AND startedAt < :before")
    abstract suspend fun abandonedRecording(before: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id = :id")
    abstract suspend fun byId(id: Long): ChunkEntity?

    @Query("SELECT COUNT(*) FROM chunks WHERE status IN ('PENDING','TRANSCRIBING')")
    abstract fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks WHERE status IN ('PENDING','TRANSCRIBING')")
    abstract suspend fun pendingCountNow(): Int

    /**
     * Work the loop can actually start right now.
     *
     * Distinct from [pendingCountNow], which feeds the UI badge. Gating the loop on
     * the badge would spin it every four seconds over rows that are parked.
     */
    @Query("SELECT COUNT(*) FROM chunks WHERE status = 'PENDING' AND notBefore <= :now")
    abstract suspend fun claimableCountNow(now: Long): Int

    /** Earliest moment any parked chunk becomes claimable, for the "next try in" countdown. */
    @Query("SELECT MIN(notBefore) FROM chunks WHERE status = 'PENDING' AND notBefore > :now")
    abstract suspend fun nextClaimableAt(now: Long): Long?

    @Query("SELECT * FROM chunks WHERE status = 'FAILED' ORDER BY startedAt DESC")
    abstract fun failedChunks(): Flow<List<ChunkEntity>>

    /**
     * Only chunks whose audio is still on disk. Matching on status alone requeued
     * audio-missing chunks to fail forever, one attempt at a time, with no way for
     * the user to clear them.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', attempts = 0, claims = 0, abandonedClaims = 0,
            transientFailures = 0, notBefore = 0, claimedAt = NULL, error = NULL
        WHERE status = 'FAILED' AND filePath IS NOT NULL
        """
    )
    abstract suspend fun retryAllFailed(): Int

    /** A FAILED chunk with no audio can only ever fail again; retiring it is the honest outcome. */
    @Query("UPDATE chunks SET status = 'DISCARDED' WHERE status = 'FAILED' AND filePath IS NULL")
    abstract suspend fun discardFailedWithoutAudio(): Int

    /**
     * Chunks transcribed by the fallback engine whose audio was kept, so they can be
     * redone against the good model once the server is reachable again. Without
     * [ChunkEntity.transcriptSource] there was no query that could find them.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', attempts = 0, transientFailures = 0, notBefore = 0,
            claimedAt = NULL, error = NULL
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL
          AND audioHold IS NOT NULL AND transcriptSource = 'device'
        """
    )
    abstract suspend fun requeueDegraded(): Int

    @Query(
        """
        SELECT COUNT(*) FROM chunks
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL
          AND audioHold IS NOT NULL AND transcriptSource = 'device'
        """
    )
    abstract fun degradedCount(): Flow<Int>

    /**
     * Terminal writes are conditional on still holding the lease.
     *
     * @return rows updated: 0 means the chunk was requeued underneath this worker
     *   and its result must be discarded rather than committed twice.
     */
    @Query(
        """
        UPDATE chunks
        SET status = :status, attempts = :attempts, error = :error,
            transcribeMs = :transcribeMs, speechRatio = :speechRatio, wordCount = :wordCount,
            transcriptSource = :transcriptSource, voicedMs = :voicedMs, coveredMs = :coveredMs,
            audioHold = :audioHold, claimedAt = NULL, notBefore = 0
        WHERE id = :id AND claimedAt = :lease
        """
    )
    abstract suspend fun finishChunk(
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
    ): Int

    @Query("UPDATE chunks SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: ChunkStatus)

    @Query("UPDATE chunks SET filePath = NULL, audioDeleted = 1 WHERE id = :id")
    abstract suspend fun markAudioDeleted(id: Long)

    /**
     * Terminal chunks whose audio was cleared for release but is somehow still on
     * disk — the service was killed in the window between committing the transcript
     * and unlinking the file. Nothing else ever revisits a terminal chunk, so
     * without this sweep the WAV stays forever and Echo quietly breaks its own
     * promise to delete the audio.
     */
    @Query(
        """
        SELECT * FROM chunks
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold IS NULL
        """
    )
    abstract suspend fun releasableLeftovers(): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    abstract suspend fun chunksBetween(from: Long, to: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    abstract fun chunksBetweenFlow(from: Long, to: Long): Flow<List<ChunkEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM chunks
        WHERE startedAt BETWEEN :from AND :to AND status IN ('RECORDING','PENDING','TRANSCRIBING')
        """
    )
    abstract suspend fun unsettledBetween(from: Long, to: Long): Int

    /** Every WAV still on disk, so orphans can be reconciled at startup. */
    @Query("SELECT * FROM chunks WHERE filePath IS NOT NULL")
    abstract suspend fun chunksWithAudio(): List<ChunkEntity>

    /**
     * Bytes of WAV Echo is holding back from deletion, computed from the sample
     * count rather than by stat-ing the disk. Feeds the backlog escape valve: a
     * park-don't-fall-back policy releases no audio at all, so without a cap the
     * recorder's own low-space pause would never lift.
     */
    @Query("SELECT COALESCE(SUM(sampleCount * 2 + 44), 0) FROM chunks WHERE filePath IS NOT NULL")
    abstract suspend fun retainedAudioBytes(): Long

    @Query("SELECT COALESCE(SUM(sampleCount),0) FROM chunks WHERE startedAt BETWEEN :from AND :to")
    abstract suspend fun samplesBetween(from: Long, to: Long): Long

    @Query("DELETE FROM chunks")
    abstract suspend fun deleteAll()
}

@Dao
interface CloudJobDao {

    @Upsert
    suspend fun upsert(job: CloudJobEntity)

    @Query("SELECT * FROM cloud_jobs WHERE chunkId = :chunkId ORDER BY pieceIndex ASC")
    suspend fun forChunk(chunkId: Long): List<CloudJobEntity>

    @Query("DELETE FROM cloud_jobs WHERE chunkId = :chunkId")
    suspend fun clearForChunk(chunkId: Long)

    /**
     * The chunk holding an outstanding job, if any.
     *
     * The server is strictly serial — one batch worker behind a global inference
     * lock — so a second outstanding job cannot start until the first finishes and
     * only makes both look stuck. At most one exists at a time.
     */
    @Query("SELECT chunkId FROM cloud_jobs WHERE state = 'SUBMITTED' LIMIT 1")
    suspend fun outstandingChunkId(): Long?
}

@Dao
interface SegmentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(segments: List<SegmentEntity>)

    @Query("SELECT * FROM segments WHERE startMs BETWEEN :from AND :to ORDER BY startMs ASC")
    suspend fun between(from: Long, to: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE startMs BETWEEN :from AND :to ORDER BY startMs ASC")
    fun betweenFlow(from: Long, to: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE chunkId = :chunkId ORDER BY startMs ASC")
    suspend fun forChunk(chunkId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE text LIKE '%' || :query || '%' ORDER BY startMs DESC LIMIT :limit")
    fun search(query: String, limit: Int = 300): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments ORDER BY startMs DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<SegmentEntity>>

    @Query("DELETE FROM segments WHERE chunkId = :chunkId")
    suspend fun deleteForChunk(chunkId: Long)
}

@Dao
interface SummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: SummaryEntity)

    @Query("SELECT * FROM summaries WHERE dayEpochDay = :day")
    suspend fun forDay(day: Long): SummaryEntity?

    @Query("SELECT * FROM summaries WHERE dayEpochDay = :day")
    fun forDayFlow(day: Long): Flow<SummaryEntity?>

    @Query("SELECT * FROM summaries ORDER BY dayEpochDay DESC LIMIT :limit")
    fun recent(limit: Int = 60): Flow<List<SummaryEntity>>

    @Query("DELETE FROM summaries")
    suspend fun deleteAll()
}

/** Groups the writes that must not be observable out of order. */
@Dao
abstract class TranscriptDao {

    /**
     * Stores a chunk's segments and its terminal status together, or not at all.
     *
     * @return false if the lease had already been taken away, in which case
     *   nothing was written. The caller must not delete the audio.
     *
     * Two things make this safe that did not used to be. The segments for the
     * chunk are cleared first, so a retry, a partially-committed run, or a
     * superseded worker cannot leave the day holding the same speech twice —
     * `insertAll` aborts on conflict, but the primary key is autogenerated, so
     * there was never a conflict to abort on. And the status write is conditional
     * on the lease, so a worker that was requeued underneath (a Whisper run
     * stretched past the watchdog by Doze) rolls the whole thing back instead of
     * double-committing.
     */
    @Transaction
    open suspend fun commitTranscript(
        chunkDao: ChunkDao,
        segmentDao: SegmentDao,
        chunkId: Long,
        lease: Long,
        segments: List<SegmentEntity>,
        attempts: Int,
        transcribeMs: Long,
        speechRatio: Float,
        wordCount: Int,
        status: ChunkStatus,
        transcriptSource: String?,
        voicedMs: Long,
        coveredMs: Long,
        audioHold: String?,
    ): Boolean {
        segmentDao.deleteForChunk(chunkId)
        if (segments.isNotEmpty()) segmentDao.insertAll(segments)
        val updated = chunkDao.finishChunk(
            id = chunkId,
            lease = lease,
            status = status,
            attempts = attempts,
            error = null,
            transcribeMs = transcribeMs,
            speechRatio = speechRatio,
            wordCount = wordCount,
            transcriptSource = transcriptSource,
            voicedMs = voicedMs,
            coveredMs = coveredMs,
            audioHold = audioHold,
        )
        // Rolls back the segment writes too: a lost lease means another worker owns
        // this chunk and ours is the copy that must disappear.
        if (updated == 0) throw LeaseLostException(chunkId)
        return true
    }
}

/** Thrown inside [TranscriptDao.commitTranscript] purely to roll the transaction back. */
class LeaseLostException(chunkId: Long) :
    IllegalStateException("chunk $chunkId was requeued while it was being transcribed")
