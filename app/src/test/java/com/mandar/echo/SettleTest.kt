package com.mandar.echo

import com.mandar.echo.data.AudioHold
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.CloudJobEntity
import com.mandar.echo.data.CloudJobState
import com.mandar.echo.data.LeaseLostException
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.TranscriptDao
import com.mandar.echo.data.isTerminal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TranscriptDao.settle] — the one door every status write goes through.
 *
 * The DAOs it composes are fakes (Room cannot run on the JVM), so nothing here
 * proves the SQL. What it does prove is the composition, which is where the two
 * defects lived: which writes happen, in which order, and which are skipped when
 * the lease has been taken away. Room's rollback itself is not testable from here
 * and is not claimed — the fakes have no transaction — so the assertions are about
 * ordering: whatever runs *after* the throw provably did not run.
 */
class SettleTest {

    private companion object {
        const val LEASE = 111_000L
        const val CHUNK = 1L
    }

    private val transcripts = object : TranscriptDao() {}

    private fun chunkRow(lease: Long?, status: ChunkStatus = ChunkStatus.TRANSCRIBING) = ChunkEntity(
        id = CHUNK,
        startedAt = 1_000,
        filePath = "/data/chunks/1.wav",
        sampleCount = 16_000,
        status = status,
        claimedAt = lease,
    )

    private fun jobRow(state: CloudJobState = CloudJobState.SUBMITTED) = CloudJobEntity(
        chunkId = CHUNK,
        pieceIndex = 0,
        offsetMs = 0,
        durationMs = 30_000,
        languageSent = "hi-IN",
        streamFingerprint = 7L,
        jobId = "batch_1",
        state = state,
    )

    private fun segment(text: String) = SegmentEntity(
        id = 0, chunkId = CHUNK, startMs = 1_000, endMs = 2_000, text = text, language = "hi",
    )

    private suspend fun settle(
        chunks: FakeChunkDao,
        segs: FakeSegmentDao,
        jobs: FakeCloudJobDao,
        status: ChunkStatus,
        lease: Long = LEASE,
        segments: List<SegmentEntity>? = emptyList(),
        audioHold: String? = null,
    ) = transcripts.settle(
        chunkDao = chunks,
        segmentDao = segs,
        cloudJobDao = jobs,
        chunkId = CHUNK,
        lease = lease,
        segments = segments,
        attempts = 1,
        error = null,
        transcribeMs = 0L,
        speechRatio = 0.5f,
        wordCount = 3,
        status = status,
        transcriptSource = null,
        voicedMs = 30_000L,
        coveredMs = 30_000L,
        audioHold = audioHold,
        notBefore = 0L,
    )

    /**
     * The cloud gate every *other* chunk queues behind is a single SUBMITTED row.
     * One owed to nobody parked the whole cloud path every twelve seconds for the
     * life of the install, so a status nobody will claim again has to take those
     * rows with it — and a status somebody *will* claim again must not.
     */
    @Test
    fun `a terminal settle takes the chunk's cloud jobs with it and a non-terminal one leaves them`() = runTest {
        val expectedTerminal = setOf(
            ChunkStatus.DONE, ChunkStatus.SILENT, ChunkStatus.FAILED, ChunkStatus.DISCARDED,
        )

        for (status in ChunkStatus.values()) {
            val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
            val segs = FakeSegmentDao()
            val jobs = FakeCloudJobDao().apply { seed(jobRow()) }

            settle(chunks, segs, jobs, status)

            val terminal = status in expectedTerminal
            assertEquals(
                "ChunkStatus.isTerminal disagrees about $status",
                terminal,
                status.isTerminal,
            )
            assertEquals(
                "cloud_jobs after settling as $status",
                terminal,
                jobs.forChunk(CHUNK).isEmpty(),
            )
        }
    }

    @Test
    fun `a settle whose lease was taken away writes nothing and leaves the server work for its new owner`() = runTest {
        val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
        val segs = FakeSegmentDao()
        val jobs = FakeCloudJobDao().apply { seed(jobRow()) }

        var thrown: Throwable? = null
        try {
            settle(chunks, segs, jobs, ChunkStatus.DONE, lease = LEASE + 1)
        } catch (lost: LeaseLostException) {
            thrown = lost
        }

        assertNotNull("a worker requeued underneath must not double-commit the day", thrown)
        // Whoever holds the lease now is still going to poll these jobs; clearing
        // them here would make the new owner re-upload audio the server is holding.
        assertFalse("the new owner still needs the cloud jobs", jobs.forChunk(CHUNK).isEmpty())
        val row = chunks.rows.getValue(CHUNK)
        assertEquals(ChunkStatus.TRANSCRIBING, row.status)
        assertEquals(LEASE, row.claimedAt)
    }

    @Test
    fun `a null segment list leaves a stored transcript untouched`() = runTest {
        val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
        val segs = FakeSegmentDao().apply { stored += segment("पहले का मजकूर") }
        val jobs = FakeCloudJobDao()

        // A failed redo of a chunk that already had a transcript: the failure must
        // not take the text it was trying to improve with it.
        settle(chunks, segs, jobs, ChunkStatus.PENDING, segments = null)

        assertTrue("the segment table was touched: ${segs.ops}", segs.ops.isEmpty())
        assertEquals(1, segs.stored.size)
    }

    @Test
    fun `writing segments clears the chunk's old ones first`() = runTest {
        val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
        val segs = FakeSegmentDao().apply { stored += segment("पहले का मजकूर") }
        val jobs = FakeCloudJobDao()

        settle(chunks, segs, jobs, ChunkStatus.DONE, segments = listOf(segment("नया"), segment("मजकूर")))

        // insertAll aborts on conflict, but the primary key is autogenerated, so
        // there was never a conflict to abort on: a retry simply left the day
        // holding the same speech twice.
        assertEquals(listOf("delete($CHUNK)", "insert(2)"), segs.ops)
        assertEquals(2, segs.stored.size)
    }

    @Test
    fun `an empty transcript still clears the old segments and inserts nothing`() = runTest {
        val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
        val segs = FakeSegmentDao().apply { stored += segment("पहले का मजकूर") }
        val jobs = FakeCloudJobDao()

        settle(chunks, segs, jobs, ChunkStatus.SILENT, segments = emptyList())

        assertEquals(listOf("delete($CHUNK)"), segs.ops)
        assertTrue(segs.stored.isEmpty())
    }

    @Test
    fun `the audio decision is written in the same call as the status`() = runTest {
        val chunks = FakeChunkDao().apply { put(chunkRow(lease = LEASE)) }
        val segs = FakeSegmentDao()
        val jobs = FakeCloudJobDao()

        settle(chunks, segs, jobs, ChunkStatus.DONE, audioHold = AudioHold.USER_REQUEST)

        val row = chunks.rows.getValue(CHUNK)
        assertEquals(ChunkStatus.DONE, row.status)
        // A sweep running at startup has only the row: if the hold were written
        // separately, a kill in between leaves "settled, no decision recorded",
        // which is the state that cannot be told apart from an archive to keep.
        assertEquals(AudioHold.USER_REQUEST, row.audioHold)
        assertNull("the lease is released by the same statement", row.claimedAt)
    }

    /**
     * Claiming and fetching as two statements addressed different rows whenever a
     * stale TRANSCRIBING row existed. The stamp matters just as much: `processNext`
     * uses the returned `claimedAt` as the token every terminal write is conditional
     * on, so a row returned without one stalls that chunk permanently.
     *
     * The *ordering* inside `nextClaimableId` (chunks holding server work first) is
     * SQL and is not exercised here.
     */
    @Test
    fun `claimNext returns the row it claimed, stamped with the lease`() = runTest {
        val chunks = FakeChunkDao()
        chunks.put(ChunkEntity(startedAt = 1, status = ChunkStatus.TRANSCRIBING, claimedAt = 5))
        val pending = chunks.put(ChunkEntity(startedAt = 2, status = ChunkStatus.PENDING))

        val claimed = chunks.claimNext(now = 1_000)

        assertNotNull(claimed)
        assertEquals(pending.id, claimed!!.id)
        assertEquals(ChunkStatus.TRANSCRIBING, claimed.status)
        assertEquals(1_000L, claimed.claimedAt)
    }
}
