package com.mandar.echo

import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.Settings
import com.mandar.echo.data.SttBackend
import com.mandar.echo.stt.BatchSync
import com.mandar.echo.stt.CODE_TRANSPORT_FAILURE
import com.mandar.echo.stt.UploadReply
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The return leg of the batch backend.
 *
 * This is the component that decides when a recording stops being the only copy
 * of something, so the cases that matter are the ones where it must do
 * *nothing*: a failed poll, a chunk the pipeline has not answered for yet, a
 * transcript that arrives empty.
 */
class BatchSyncTest {

    private val cfg = Settings(
        sttBackend = SttBackend.BATCH,
        uploadUrl = "https://upload.example",
        uploadKey = "key",
    )

    private fun uploaded(id: Long, startedAt: Long) = ChunkEntity(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 60_000,
        filePath = "/data/chunk$id.wav",
        sampleCount = 960_000,
        status = ChunkStatus.UPLOADED,
        audioHold = com.mandar.echo.data.AudioHold.AWAITING_REMOTE,
    )

    private fun reply(vararg entries: Pair<String, List<Triple<String, Long, Long>>>): UploadReply {
        val segments = JSONObject()
        for ((name, rows) in entries) {
            val arr = JSONArray()
            for ((text, start, end) in rows) {
                arr.put(
                    JSONObject()
                        .put("text", text)
                        .put("language", "mr")
                        .put("startMs", start)
                        .put("endMs", end)
                )
            }
            segments.put(name, arr)
        }
        return UploadReply(200, JSONObject().put("segments", segments).toString())
    }

    /**
     * The real fakes plus a real TranscriptDao. `commitRemote` has a body and no
     * abstract members, so the transaction under test is the shipping one rather
     * than a reimplementation of it -- which is the whole point, since the thing
     * being checked is that segments and status land together.
     */
    private class Harness {
        val chunks = FakeChunkDao()
        val segments = FakeSegmentDao()
        val transcripts = object : com.mandar.echo.data.TranscriptDao() {}
    }

    private fun harness(vararg rows: ChunkEntity) = Harness().apply {
        rows.forEach { chunks.put(it) }
    }

    private fun sync(h: Harness, post: suspend (String, String, String) -> UploadReply) =
        BatchSync(h.chunks, h.segments, h.transcripts, post)

    @Test
    fun aReturnedTranscriptLandsWithAbsoluteTimes() = runTest {
        val start = 1_786_358_637_983L
        val h = harness(uploaded(1, start))
        var asked: String? = null

        val s = sync(h) { _, _, body ->
            asked = body
            reply("$start.wav" to listOf(Triple("उद्या सकाळी", 500L, 3_360L)))
        }

        assertEquals(1, s.run(cfg))

        // Offsets inside the file become absolute epoch times, which is the whole
        // reason the raw chunk is uploaded rather than the compacted stream.
        val segment = h.segments.stored.single()
        assertEquals(start + 500, segment.startMs)
        assertEquals(start + 3_360, segment.endMs)

        val chunk = h.chunks.rows.getValue(1L)
        assertEquals(ChunkStatus.DONE, chunk.status)
        assertNull("the hold must be cleared once words are stored", chunk.audioHold)
        assertEquals(2, chunk.wordCount)

        // Asked by exact object name, derived from the chunk's own start.
        assertTrue(asked!!.contains("pending/$start.wav"))
    }

    @Test
    fun aFailedPollSettlesNothing() = runTest {
        // The case that matters most. A chunk must keep its audio and its hold
        // when the service cannot be reached -- anything else releases the only
        // copy of a recording on the strength of a network error.
        val h = harness(uploaded(1, 1_000L))
        val s = sync(h) { _, _, _ -> UploadReply(CODE_TRANSPORT_FAILURE) }

        assertEquals(0, s.run(cfg))

        val chunk = h.chunks.rows.getValue(1L)
        assertEquals(ChunkStatus.UPLOADED, chunk.status)
        assertEquals(com.mandar.echo.data.AudioHold.AWAITING_REMOTE, chunk.audioHold)
        assertTrue(h.segments.stored.isEmpty())
    }

    @Test
    fun aChunkTheServiceHasNoAnswerForIsLeftAlone() = runTest {
        // The ordinary case: batching means most polls precede the transcript.
        val h = harness(uploaded(1, 1_000L))
        val s = sync(h) { _, _, _ ->
            UploadReply(200, JSONObject().put("segments", JSONObject()).toString())
        }

        assertEquals(0, s.run(cfg))
        assertEquals(ChunkStatus.UPLOADED, h.chunks.rows.getValue(1L).status)
    }

    @Test
    fun anEmptyTranscriptIsSilenceNotAFailure() = runTest {
        // A read result with no words is a real answer about a quiet room. It
        // settles the chunk -- otherwise a silent recording is held for ever.
        val h = harness(uploaded(1, 1_000L))
        val s = sync(h) { _, _, _ -> reply("1000.wav" to emptyList()) }

        assertEquals(1, s.run(cfg))
        val chunk = h.chunks.rows.getValue(1L)
        assertEquals(ChunkStatus.SILENT, chunk.status)
        assertNull(chunk.audioHold)
    }

    @Test
    fun onlyTheChunkThatWasAnsweredIsSettled() = runTest {
        val h = harness(uploaded(1, 1_000L), uploaded(2, 2_000L))
        val s = sync(h) { _, _, _ ->
            reply("1000.wav" to listOf(Triple("काम", 0L, 500L)))
        }

        assertEquals(1, s.run(cfg))
        assertEquals(ChunkStatus.DONE, h.chunks.rows.getValue(1L).status)
        assertEquals(ChunkStatus.UPLOADED, h.chunks.rows.getValue(2L).status)
    }

    @Test
    fun anUnconfiguredServiceDoesNothingRatherThanFailing() = runTest {
        val h = harness(uploaded(1, 1_000L))
        var called = false
        val s = sync(h) { _, _, _ -> called = true; UploadReply(200) }

        assertEquals(0, s.run(cfg.copy(uploadUrl = "", uploadKey = "")))
        assertTrue("must not call out with no service configured", !called)
        assertEquals(ChunkStatus.UPLOADED, h.chunks.rows.getValue(1L).status)
    }

    @Test
    fun rubbishInTheReplyIsNotTreatedAsAnAnswer() = runTest {
        val h = harness(uploaded(1, 1_000L))
        val s = sync(h) { _, _, _ -> UploadReply(200, "not json") }

        assertEquals(0, s.run(cfg))
        assertEquals(ChunkStatus.UPLOADED, h.chunks.rows.getValue(1L).status)
    }

    @Test
    fun nothingWaitingMeansNoRequestAtAll() = runTest {
        val h = harness(uploaded(1, 1_000L).copy(status = ChunkStatus.DONE))
        var called = false
        val s = sync(h) { _, _, _ -> called = true; UploadReply(200) }

        assertEquals(0, s.run(cfg))
        assertTrue("a poll with nothing to ask about is wasted radio", !called)
    }
}
