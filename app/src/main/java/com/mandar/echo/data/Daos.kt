package com.mandar.echo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {

    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    @Query("UPDATE chunks SET endedAt = :endedAt, sampleCount = :sampleCount, status = :status WHERE id = :id")
    suspend fun closeChunk(id: Long, endedAt: Long, sampleCount: Long, status: ChunkStatus)

    /**
     * Claims the oldest transcribable chunk. Marking it TRANSCRIBING in the same
     * statement means a crash mid-run leaves it visibly stuck rather than silently
     * double-processed; [requeueStuck] recovers those on next start.
     */
    @Query(
        """
        UPDATE chunks SET status = 'TRANSCRIBING'
        WHERE id = (
            SELECT id FROM chunks WHERE status = 'PENDING' ORDER BY startedAt ASC LIMIT 1
        )
        """
    )
    suspend fun claimOldestPending(): Int

    @Query("SELECT * FROM chunks WHERE status = 'TRANSCRIBING' ORDER BY startedAt ASC LIMIT 1")
    suspend fun currentlyTranscribing(): ChunkEntity?

    @Query("UPDATE chunks SET status = 'PENDING' WHERE status = 'TRANSCRIBING'")
    suspend fun requeueStuck(): Int

    @Query("SELECT * FROM chunks WHERE id = :id")
    suspend fun byId(id: Long): ChunkEntity?

    @Query("SELECT COUNT(*) FROM chunks WHERE status IN ('PENDING','TRANSCRIBING')")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks WHERE status IN ('PENDING','TRANSCRIBING')")
    suspend fun pendingCountNow(): Int

    @Query("SELECT * FROM chunks WHERE status = 'FAILED' ORDER BY startedAt DESC")
    fun failedChunks(): Flow<List<ChunkEntity>>

    @Query("UPDATE chunks SET status = 'PENDING', attempts = 0, error = NULL WHERE status = 'FAILED'")
    suspend fun retryAllFailed(): Int

    @Query(
        """
        UPDATE chunks
        SET status = :status, attempts = :attempts, error = :error,
            transcribeMs = :transcribeMs, speechRatio = :speechRatio, wordCount = :wordCount
        WHERE id = :id
        """
    )
    suspend fun finishChunk(
        id: Long,
        status: ChunkStatus,
        attempts: Int,
        error: String?,
        transcribeMs: Long?,
        speechRatio: Float,
        wordCount: Int,
    )

    @Query("UPDATE chunks SET filePath = NULL, audioDeleted = 1 WHERE id = :id")
    suspend fun markAudioDeleted(id: Long)

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    suspend fun chunksBetween(from: Long, to: Long): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt ASC")
    fun chunksBetweenFlow(from: Long, to: Long): Flow<List<ChunkEntity>>

    /** Every WAV still on disk, so orphans can be reconciled at startup. */
    @Query("SELECT * FROM chunks WHERE filePath IS NOT NULL")
    suspend fun chunksWithAudio(): List<ChunkEntity>

    @Query("SELECT COALESCE(SUM(sampleCount),0) FROM chunks WHERE startedAt BETWEEN :from AND :to")
    suspend fun samplesBetween(from: Long, to: Long): Long

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()
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

    @Transaction
    open suspend fun commitTranscript(
        chunkDao: ChunkDao,
        segmentDao: SegmentDao,
        chunkId: Long,
        segments: List<SegmentEntity>,
        attempts: Int,
        transcribeMs: Long,
        speechRatio: Float,
        wordCount: Int,
        status: ChunkStatus,
    ) {
        if (segments.isNotEmpty()) segmentDao.insertAll(segments)
        chunkDao.finishChunk(
            id = chunkId,
            status = status,
            attempts = attempts,
            error = null,
            transcribeMs = transcribeMs,
            speechRatio = speechRatio,
            wordCount = wordCount,
        )
    }
}
