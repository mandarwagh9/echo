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
     *
     * [ChunkEntity.abandonedClaims] *is* cleared: reaching a park means the run got
     * far enough to record an outcome, which is exactly what that counter says did
     * not happen. It counts consecutive silent deaths, not lifetime ones.
     *
     * Conditional on the lease like every other write that ends a run. Without it a
     * worker whose chunk was requeued — or which was cancelled after committing —
     * could drag a chunk somebody else has already settled back to PENDING.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', claimedAt = NULL, notBefore = :notBefore,
            abandonedClaims = 0, transientFailures = :transientFailures, error = :error
        WHERE id = :id AND claimedAt = :lease
        """
    )
    abstract suspend fun deferChunk(
        id: Long,
        lease: Long,
        notBefore: Long,
        transientFailures: Int,
        error: String?,
    ): Int

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
     * Give up waiting on the batch pipeline and queue the chunk again.
     *
     * An UPLOADED chunk is claimed by nothing and matched by neither recovery
     * button -- "Retry failed" takes FAILED and "Redo" takes REDOABLE -- so
     * without this a transcript that never arrives strands the recording for
     * ever, holding audio no sweep may release. Which is not hypothetical: the
     * bucket's lifecycle rule deletes the uploaded copy on a timer whether or
     * not anything read it.
     *
     * Safe to re-run because the object name is derived from `startedAt`, so a
     * second upload overwrites the first rather than duplicating it, and the
     * audio it needs is still on this device -- that is the whole reason the WAV
     * was kept.
     */
    @Query(
        """
        UPDATE chunks SET status = 'PENDING', audioHold = NULL, notBefore = 0,
            transientFailures = transientFailures + 1
        WHERE status = 'UPLOADED' AND filePath IS NOT NULL AND startedAt < :cutoff
        """
    )
    abstract suspend fun reclaimStuckUploads(cutoff: Long): Int

    /**
     * Chunks the batch pipeline has and this device is waiting on.
     *
     * Deliberately not folded into [pendingCount]. "In queue" means work this
     * phone will do; this is work happening somewhere else, and the two want
     * different words on screen. Counted at all because an uploaded chunk is
     * otherwise invisible -- not queued, not transcribed, not failed -- and a
     * recorder whose state cannot be seen is how eighteen chunks once sat in
     * silence with nothing on screen to say so.
     */
    @Query("SELECT COUNT(*) FROM chunks WHERE status = 'UPLOADED'")
    abstract fun awaitingRemoteCount(): Flow<Int>

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
     * Chunks the run itself marked as re-transcribable, so they can be redone
     * against the good model once the server is reachable again.
     *
     * Keyed on the recorded *reasons* ([AudioHold.REDOABLE]), not on
     * `audioHold IS NOT NULL AND transcriptSource = 'device'`. That conjunction was
     * a proxy for the reason, and it was about to start matching things it does not
     * mean: an on-device chunk whose audio is only on disk because the user asked
     * for it would have been requeued forever to be re-transcribed by the identical
     * engine that already transcribed it.
     */
    @Query(
        """
        UPDATE chunks
        SET status = 'PENDING', attempts = 0, abandonedClaims = 0, transientFailures = 0,
            notBefore = 0, claimedAt = NULL, error = NULL
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold IN (:holds)
        """
    )
    abstract suspend fun requeueRedoable(holds: List<String>): Int

    @Query(
        """
        SELECT COUNT(*) FROM chunks
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold IN (:holds)
        """
    )
    abstract fun redoableCount(holds: List<String>): Flow<Int>

    /**
     * Audio the disk valve may trade for space, oldest first.
     *
     * Deliberately narrow: one recorded hold ([AudioHold.DEGRADED]), which is the
     * only one that means "a transcript of this already exists and only its accuracy
     * is at stake". [AudioHold.HOLE] and [AudioHold.USER_REQUEST] are not in reach
     * of this query at any pressure, because releasing them destroys speech that was
     * never transcribed, or a copy the user asked Echo to keep. Oldest first so a
     * long outage sacrifices the audio the user is least likely to still want.
     *
     * `wordCount > 0` is what makes that premise true rather than merely stated. A
     * DEGRADED row is by definition Whisper's work, and Whisper is measured at 0.00
     * word recall on Marathi — so on the language this app exists for, the typical
     * DEGRADED row holds no words at all. Trading its WAV away is not sacrificing
     * accuracy, it is deleting the recording and keeping an empty string. A chunk
     * with no words is worth exactly as much as its audio, so it stays.
     */
    @Query(
        """
        SELECT * FROM chunks
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold = :hold
          AND wordCount > 0
        ORDER BY startedAt ASC
        """
    )
    abstract suspend fun tradeableAudio(hold: String): List<ChunkEntity>

    /**
     * Terminal writes are conditional on still holding the lease.
     *
     * Not called directly: it is one statement of [TranscriptDao.settle], which is
     * where the writes that must land together are grouped.
     *
     * [notBefore] matters only on the one write that is not terminal — a failure
     * with attempts left. Zeroing it there let all three attempts burn back to back
     * in under a second, so a fault that would have cleared in half a minute
     * consumed the whole budget and the chunk landed in FAILED.
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
            audioHold = :audioHold, claimedAt = NULL, notBefore = :notBefore, abandonedClaims = 0
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
        notBefore: Long,
    ): Int

    /**
     * Land a chunk whose transcript arrived from the batch pipeline.
     *
     * Clears `audioHold` in the same statement that sets the status: the hold
     * said "waiting for its transcript", and that is now false. Releasing the
     * WAV itself is left to the ordinary sweep, which is the only thing that
     * unlinks files.
     */
    @Query(
        "UPDATE chunks SET status = :status, wordCount = :wordCount, " +
            "coveredMs = :coveredMs, audioHold = NULL, error = NULL " +
            "WHERE id = :id AND status = 'UPLOADED'"
    )
    abstract suspend fun completeRemote(
        id: Long,
        status: ChunkStatus,
        wordCount: Int,
        coveredMs: Long,
    ): Int

    /**
     * Chunks handed to the batch pipeline whose transcripts have not come back.
     *
     * Oldest first, and deliberately not lease-aware: an UPLOADED chunk is not
     * claimable by the worker, so nothing else is competing for it.
     */
    @Query(
        "SELECT * FROM chunks WHERE status = 'UPLOADED' " +
            "ORDER BY startedAt ASC LIMIT :limit"
    )
    abstract suspend fun uploadedChunks(limit: Int): List<ChunkEntity>

    @Query("UPDATE chunks SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: ChunkStatus)

    /** Clears [ChunkEntity.audioHold] with the file: nothing can still be keeping audio that is gone. */
    @Query("UPDATE chunks SET filePath = NULL, audioDeleted = 1, audioHold = NULL WHERE id = :id")
    abstract suspend fun markAudioDeleted(id: Long)

    /**
     * Terminal chunks the committing run marked [AudioHold.UNLINK_PENDING] whose
     * file is somehow still on disk — the service was killed in the window between
     * committing the transcript and unlinking. Nothing else ever revisits a terminal
     * chunk, so without this sweep the WAV stays forever and Echo quietly breaks its
     * own promise to delete the audio.
     *
     * Matches the marker rather than `audioHold IS NULL`. Absence of a hold is not
     * evidence of a release decision: every row written before this column existed
     * has NULL there too, and those are exactly the WAVs an earlier version was
     * asked to keep. The sweep is only allowed to finish a job it can see was
     * started.
     */
    @Query(
        """
        SELECT * FROM chunks
        WHERE status IN ('DONE','SILENT') AND filePath IS NOT NULL AND audioHold = :hold
        """
    )
    abstract suspend fun releasableLeftovers(hold: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    abstract suspend fun chunksBetween(from: Long, to: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    abstract fun chunksBetweenFlow(from: Long, to: Long): Flow<List<ChunkEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM chunks
        WHERE startedAt BETWEEN :from AND :to
          AND status IN ('RECORDING','PENDING','TRANSCRIBING','UPLOADED')
        """
    )
    abstract suspend fun unsettledBetween(from: Long, to: Long): Int

    /** Every WAV still on disk, so orphans can be reconciled at startup. */
    @Query("SELECT * FROM chunks WHERE filePath IS NOT NULL")
    abstract suspend fun chunksWithAudio(): List<ChunkEntity>

    /**
     * Bytes of WAV Echo is *holding back* from deletion, computed from the sample
     * count rather than by stat-ing the disk. Feeds the backlog escape valve: a
     * park-don't-fall-back policy releases no audio at all, so without a cap the
     * recorder's own low-space pause would never lift.
     *
     * Counts only rows that reached a terminal status still holding their audio.
     * Summing every row with a filePath conflated two different things: audio kept
     * *by a decision*, which the valve can trade, and the ordinary PENDING backlog
     * of a server outage, which it cannot — that audio has no transcript at all.
     * Because backlog is exactly what grows during an outage, the wrong sum crossed
     * the cap on pure queue depth (~52 chunks, under nine hours) and then opened a
     * valve that could do nothing about it, permanently routing every new chunk past
     * the good engine for the rest of the outage.
     */
    @Query(
        """
        SELECT COALESCE(SUM(sampleCount * 2 + 44), 0) FROM chunks
        WHERE filePath IS NOT NULL AND audioHold IS NOT NULL AND audioHold <> :unlinkPending
        """
    )
    abstract suspend fun retainedAudioBytes(unlinkPending: String): Long

    @Query("SELECT COALESCE(SUM(sampleCount),0) FROM chunks WHERE startedAt BETWEEN :from AND :to")
    abstract suspend fun samplesBetween(from: Long, to: Long): Long

    /**
     * The local days that actually hold a recording, newest first, as epoch days.
     *
     * Buckets in SQL rather than loading every chunk and grouping in Kotlin: a
     * month of 10-minute chunks is a few thousand rows and this list only needs
     * the distinct days.
     *
     * [utcOffsetMs] is the device's *current* offset from UTC. That is exact for
     * every day in a zone without daylight saving, and can misplace a recording
     * made within an hour of a DST boundary into the neighbouring day. It is
     * only ever used to build the browse list; opening a day still resolves its
     * bounds through ZoneId, so what you read is correct even when the row you
     * tapped was listed a day out.
     */
    @Query(
        """
        SELECT DISTINCT CAST((startedAt + :utcOffsetMs) / 86400000 AS INTEGER) AS day
        FROM chunks
        WHERE sampleCount > 0
        ORDER BY day DESC
        LIMIT :limit
        """
    )
    abstract fun recordedDays(utcOffsetMs: Long, limit: Int = 180): Flow<List<Long>>

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
     *
     * The join is the point. This one row is the gate every other chunk queues
     * behind, so it must name a chunk somebody will actually come back for: a
     * SUBMITTED row whose chunk has gone terminal is owed to nobody, and trusting
     * the row rather than the chunk let one such orphan park the entire cloud path
     * every twelve seconds for the life of the install.
     */
    @Query(
        """
        SELECT j.chunkId FROM cloud_jobs j
        JOIN chunks c ON c.id = j.chunkId
        WHERE j.state = 'SUBMITTED' AND c.status IN ('PENDING','TRANSCRIBING')
        LIMIT 1
        """
    )
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
     * Commit a transcript that arrived out of band, from the batch pipeline.
     *
     * Not lease-conditional, unlike [settle], because there is no lease to hold:
     * an UPLOADED chunk was released by the worker when it was handed off, and
     * `nextClaimableId` only takes PENDING, so nothing else can be writing to it.
     *
     * One transaction, because the two writes must not be observable apart. A
     * chunk that reached DONE without its segments would have its audio released
     * by the ordinary path and its words lost with it -- and the audio is the
     * only copy, because the bucket deletes its own on a lifecycle timer.
     */
    @Transaction
    open suspend fun commitRemote(
        chunkDao: ChunkDao,
        segmentDao: SegmentDao,
        chunkId: Long,
        segments: List<SegmentEntity>,
        wordCount: Int,
        coveredMs: Long,
    ) {
        segmentDao.deleteForChunk(chunkId)
        if (segments.isNotEmpty()) segmentDao.insertAll(segments)
        chunkDao.completeRemote(
            id = chunkId,
            status = if (segments.isEmpty()) ChunkStatus.SILENT else ChunkStatus.DONE,
            wordCount = wordCount,
            coveredMs = coveredMs,
        )
    }

    /**
     * Writes everything a finished run owns — its segments, the chunk's new status
     * and audio decision, and the server work it no longer needs — together, or not
     * at all. Every status write the pipeline makes goes through here.
     *
     * Three things make this safe that did not used to be. The segments for the
     * chunk are cleared first, so a retry, a partially-committed run, or a
     * superseded worker cannot leave the day holding the same speech twice —
     * `insertAll` aborts on conflict, but the primary key is autogenerated, so
     * there was never a conflict to abort on. The status write is conditional
     * on the lease, so a worker that was requeued underneath (a Whisper run
     * stretched past the watchdog by Doze) rolls the whole thing back instead of
     * double-committing. And a status nobody will claim again takes the chunk's
     * `cloud_jobs` rows with it: [CloudJobDao.outstandingChunkId] is the gate every
     * *other* chunk queues behind, so a SUBMITTED row owed to nobody used to stall
     * the entire cloud path. Clearing at each terminal call site was the version of
     * this that had five sites and covered four.
     *
     * @param segments null leaves any stored transcript alone. A failed redo of a
     *   chunk that already had a transcript must not erase the transcript it was
     *   redoing.
     *
     * @throws LeaseLostException if the lease had already been taken away, in which
     *   case nothing was written and the caller must not touch the audio.
     */
    @Transaction
    open suspend fun settle(
        chunkDao: ChunkDao,
        segmentDao: SegmentDao,
        cloudJobDao: CloudJobDao,
        chunkId: Long,
        lease: Long,
        segments: List<SegmentEntity>?,
        attempts: Int,
        error: String?,
        transcribeMs: Long?,
        speechRatio: Float,
        wordCount: Int,
        status: ChunkStatus,
        transcriptSource: String?,
        voicedMs: Long,
        coveredMs: Long,
        audioHold: String?,
        notBefore: Long,
    ) {
        if (segments != null) {
            segmentDao.deleteForChunk(chunkId)
            if (segments.isNotEmpty()) segmentDao.insertAll(segments)
        }
        val updated = chunkDao.finishChunk(
            id = chunkId,
            lease = lease,
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
            notBefore = notBefore,
        )
        // Rolls back the segment writes too: a lost lease means another worker owns
        // this chunk and ours is the copy that must disappear.
        if (updated == 0) throw LeaseLostException(chunkId)
        if (status.isTerminal) cloudJobDao.clearForChunk(chunkId)
    }
}

/** Thrown inside [TranscriptDao.settle] purely to roll the transaction back. */
class LeaseLostException(chunkId: Long) :
    IllegalStateException("chunk $chunkId was requeued while it was being transcribed")
