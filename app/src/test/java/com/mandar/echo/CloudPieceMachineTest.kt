package com.mandar.echo

import com.mandar.echo.data.CloudJobEntity
import com.mandar.echo.data.CloudJobState
import com.mandar.echo.stt.CODE_TRANSPORT_FAILURE
import com.mandar.echo.stt.CloudTranscriber
import com.mandar.echo.stt.CloudTranscriber.CloudOutcome
import com.mandar.echo.stt.HttpReply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The piece state machine, driven end to end against a fake transport.
 *
 * NEW ─submit─► SUBMITTED ─poll─► COMPLETED | REJECTED, and nothing else. The
 * durable unit is a `cloud_jobs` row, so every assertion here is about two things
 * at once: the outcome the caller sees, and the row left behind for the next run
 * to resume from.
 *
 * The clock is the test scheduler's virtual clock, so `delay` inside the poll loop
 * costs nothing and the timings it produces are still observable — see
 * CloudPollBackoffTest, which reads the same call log.
 */
class CloudPieceMachineTest {

    // ---- the happy path ------------------------------------------------------

    @Test
    fun `a piece runs submit, queued, processing, completed and then settles`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, n ->
            when (n) {
                1 -> HttpReply(200, queuedBody())
                2 -> HttpReply(200, processingBody())
                else -> HttpReply(200, completedBody("आज मीटिंग होती"))
            }
        }

        val outcome = r.cloud.run(chunkId = 7, voiced = voicedSeconds(30), language = "hi", fingerprint = 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val settled = outcome as CloudOutcome.Settled
        assertEquals(1, settled.pieces.size)
        val piece = settled.pieces.single()
        assertEquals("आज मीटिंग होती", piece.text)
        assertEquals(0L, piece.offsetMs)
        assertEquals(30_000L, piece.durationMs)
        assertEquals("hi", piece.language)
        assertFalse(piece.rejected)

        assertEquals(1, r.transport.uploads.size)
        assertEquals("queued and processing are not terminal", 3, r.transport.polls.size)
        assertEquals(CloudJobState.COMPLETED, r.jobs.forChunk(7).single().state)
        assertTrue("the run took virtual time", settled.elapsedMs > 0)
    }

    /**
     * The live bug, pinned at the level above the parser.
     *
     * The server marks a job completed with `transcript: ""` whenever the model
     * produced no words. The old client used blankness as a readiness test, so this
     * exact reply was polled for fifteen minutes and then abandoned. One poll is the
     * whole interaction.
     */
    @Test
    fun `a completed job with an empty transcript settles instead of polling forever`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("")) }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val settled = outcome as CloudOutcome.Settled
        assertEquals("", settled.pieces.single().text)
        assertFalse("silence is a success, not a refusal", settled.pieces.single().rejected)
        assertEquals("the answer arrived on the first poll", 1, r.transport.polls.size)
        assertEquals(CloudJobState.COMPLETED, r.jobs.forChunk(7).single().state)
    }

    // ---- refusals ------------------------------------------------------------

    @Test
    fun `a submit-time rejection settles the piece without ever polling`() = runTest {
        val r = rig()
        r.transport.onSubmit = { HttpReply(400, """{"error":"Audio too long (612.0s). Max 300s"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val settled = outcome as CloudOutcome.Settled
        assertTrue("a refusal must reach the caller so the span can be covered", settled.pieces.single().rejected)
        // Falling through to the poll would ask about a job id that was never
        // issued, and a recorded rejection would surface as "submitted with no job
        // id" — a real refusal reported under the wrong name.
        assertEquals("nothing to poll for", 0, r.transport.polls.size)

        val row = r.jobs.forChunk(7).single()
        assertEquals(CloudJobState.REJECTED, row.state)
        assertTrue("the server's reason is kept: ${row.error}", row.error.orEmpty().contains("Audio too long"))
    }

    @Test
    fun `an unrecognised poll status is terminal, not a reason to keep polling`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, """{"status":"almost_done"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val piece = (outcome as CloudOutcome.Settled).pieces.single()
        assertTrue(piece.rejected)
        assertEquals(1, r.transport.polls.size)
        // Terminal, but not a verdict on the audio. An unrecognised status means the
        // server changed under us — a redeploy, a renamed key — so the span must stay
        // retryable and keep its WAV. Filing it as a refusal handed the span to
        // Whisper, filled coveredMs, and deleted the only copy.
        assertTrue("a reply we cannot parse says nothing about the audio", piece.retryable)
        assertEquals(CloudJobState.LOST, r.jobs.forChunk(7).single().state)
    }

    // ---- credentials ---------------------------------------------------------

    @Test
    fun `403 on submit halts the cloud path instead of failing the chunk`() = runTest {
        val r = rig()
        r.transport.onSubmit = { HttpReply(403, """{"error":"Invalid or missing API key"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Halt)
        // A rotated key must not chew a backlog through Whisper at 0.23 word recall.
        // The piece is left exactly as it was, so nothing has been decided about it.
        assertEquals(CloudJobState.NEW, r.jobs.forChunk(7).single().state)
        assertEquals(0, r.transport.polls.size)
    }

    @Test
    fun `403 while polling halts and leaves the job resumable`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(403, "Invalid or missing API key") }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Halt)
        val row = r.jobs.forChunk(7).single()
        assertEquals("the server still holds this job", CloudJobState.SUBMITTED, row.state)
        assertNotNull("so the next run polls it rather than re-uploading", row.jobId)
    }

    // ---- the server losing a job --------------------------------------------

    @Test
    fun `a 404 while polling resubmits once and parks, never loops`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(404, """{"error":"Job not found"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Park)
        assertEquals("one upload per run, whatever the server says", 1, r.transport.uploads.size)
        assertEquals(1, r.transport.polls.size)

        val row = r.jobs.forChunk(7).single()
        assertEquals("re-armed for the next run", CloudJobState.NEW, row.state)
        assertNull(row.jobId)
        assertEquals(1, row.resubmits)
    }

    /**
     * The bound the constant promises, which the code used not to have.
     *
     * `submit()` wrote `resubmits = 0` on every accepted upload and `advance()`
     * re-reads the row before polling, so `pollToTerminal` always saw
     * `resubmits == 0` and `resubmits >= MAX` was unreachable. A server that has lost
     * the job — a Cloud Run instance recycling, which is expected rather than
     * exceptional — therefore got the same 3.8 MB piece uploaded again on every park,
     * forever, on a phone's metered radio.
     *
     * The fix is in two halves and needs both: the counter survives the resubmit it
     * exists to count (reset by a *poll that answered*, not by the upload), and a
     * piece that exhausts it goes terminal, because writing `state = NEW` on
     * exhaustion is itself an instruction to upload again.
     */
    @Test
    fun `a server that keeps losing the job eventually stops re-uploading`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(404, """{"error":"Job not found"}""") }
        val audio = voicedSeconds(2)

        repeat(6) { r.cloud.run(7, audio, "hi", 1L) }

        assertTrue(
            "uploaded ${r.transport.uploads.size} times for one piece; " +
                "MAX_CONSECUTIVE_RESUBMITS=${CloudTranscriber.MAX_CONSECUTIVE_RESUBMITS} bounds nothing",
            r.transport.uploads.size <= CloudTranscriber.MAX_CONSECUTIVE_RESUBMITS + 1,
        )
        // Bounded *and* finished: a piece that stops being re-uploaded but never goes
        // terminal parks its chunk forever, which is the same stall with less traffic.
        assertEquals(CloudJobState.LOST, r.jobs.forChunk(7).single().state)
    }

    /**
     * The audio consequence, which is the half of this that cannot be seen from the
     * upload count.
     *
     * A piece the server *refused* and a piece it kept *losing* both come back with
     * no text, and both get covered by the on-device engine. They must not come back
     * indistinguishable: the caller reads [CloudTranscriber.PieceResult.retryable] to
     * decide whether the WAV is still owed a real transcript, and answering "no" for
     * a server-side loss deletes the only copy of audio that IndicConformer would
     * have transcribed at 1.00 recall — against Whisper's 0.00 on Marathi — an hour
     * later.
     */
    @Test
    fun `a lost piece is retryable and a refused one is not`() = runTest {
        val lost = rig()
        lost.transport.onPoll = { _, _ -> HttpReply(404, """{"error":"Job not found"}""") }
        val audio = voicedSeconds(2)
        var lostOutcome: CloudOutcome? = null
        repeat(4) { lostOutcome = lost.cloud.run(7, audio, "hi", 1L) }

        assertTrue("outcome was $lostOutcome", lostOutcome is CloudOutcome.Settled)
        val lostPiece = (lostOutcome as CloudOutcome.Settled).pieces.single()
        assertTrue("the span still has to be covered on device", lostPiece.rejected)
        assertTrue("nothing was decided about this audio; it is still owed", lostPiece.retryable)

        val refused = rig()
        refused.transport.onSubmit = { HttpReply(400, """{"error":"Audio too short"}""") }
        val refusedOutcome = refused.cloud.run(7, audio, "hi", 1L)

        assertTrue("outcome was $refusedOutcome", refusedOutcome is CloudOutcome.Settled)
        val refusedPiece = (refusedOutcome as CloudOutcome.Settled).pieces.single()
        assertTrue(refusedPiece.rejected)
        // Asking again gets the same answer, so holding the WAV buys nothing.
        assertFalse("a refusal is final", refusedPiece.retryable)
    }

    /**
     * The counter measures a *streak*, so a working round trip has to end one.
     *
     * Cloud Run recycles instances routinely; a lifetime cap would quietly retire the
     * cloud path on a server that is doing its job, which is the failure mode in the
     * opposite direction from the unbounded upload loop.
     */
    @Test
    fun `a piece the server loses after answering still stops re-uploading`() = runTest {
        val r = rig()
        // The documented Cloud Run failure mode, and the one that defeated the
        // previous bound: the server answers about the job, then loses it. An
        // earlier fix read the answer as proof the upload had survived and zeroed
        // the streak — so answer/lose/answer/lose reset the budget forever and one
        // 3.8 MB piece went up once a minute for as long as the outage lasted.
        r.transport.onPoll = { _, n ->
            if (n == 1) HttpReply(200, queuedBody())
            else HttpReply(404, """{"error":"Job not found"}""")
        }
        val audio = voicedSeconds(2)

        repeat(8) { r.cloud.run(7, audio, "hi", 1L) }

        assertTrue(
            "uploaded ${r.transport.uploads.size} times for one piece",
            r.transport.uploads.size <= CloudTranscriber.MAX_CONSECUTIVE_RESUBMITS + 1,
        )
        // Bounded uploads without a terminal state is the same stall with less
        // traffic, so the row has to actually come to rest.
        val row = r.jobs.forChunk(7).single()
        assertEquals(CloudJobState.LOST, row.state)
    }

    /**
     * A piece the server fumbled is retryable; one it read and refused is not. The
     * distinction decides whether the WAV survives: a non-retryable piece is covered
     * by Whisper, which fills coveredMs and lets the audio be deleted — correct for
     * audio the server will never accept, catastrophic for a Marathi chunk the server
     * merely dropped, where Whisper measures 0.00 word recall.
     */
    @Test
    fun `a server-side job failure keeps the audio retryable`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ ->
            HttpReply(200, """{"status":"failed","error_message":"CUDA out of memory"}""")
        }

        val outcome = r.cloud.run(7, voicedSeconds(2), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val piece = (outcome as CloudOutcome.Settled).pieces.single()
        assertTrue("a job the server failed must stay retryable", piece.retryable)
        assertEquals(CloudJobState.LOST, r.jobs.forChunk(7).single().state)
    }


    // ---- the server working ---------------------------------------------------

    @Test
    fun `a job the server never finishes parks at the ceiling rather than spinning`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, processingBody()) }

        val outcome = r.cloud.run(7, voicedSeconds(5), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Park)
        val park = outcome as CloudOutcome.Park
        assertTrue("a working server is progress: ${park.reason}", park.progressed)
        assertTrue("reason was ${park.reason}", park.reason.contains("still working"))
        // POLL_CEILING_FLOOR_MS / POLL_MAX_MS is the order of magnitude; the point is
        // that it terminates at all and does not poll thousands of times to get there.
        assertTrue("polled ${r.transport.polls.size} times", r.transport.polls.size in 5..60)
        assertEquals(CloudJobState.SUBMITTED, r.jobs.forChunk(7).single().state)
    }

    @Test
    fun `a dead socket after processing is progress, and after queued is not`() = runTest {
        val afterProcessing = rig()
        afterProcessing.transport.onPoll = { _, n ->
            if (n == 1) HttpReply(200, processingBody()) else HttpReply(CODE_TRANSPORT_FAILURE, "read timed out")
        }
        val one = afterProcessing.cloud.run(7, voicedSeconds(5), "hi", 1L)
        assertTrue("outcome was $one", one is CloudOutcome.Park)
        assertTrue("the worker had started; the network went away", (one as CloudOutcome.Park).progressed)

        val afterQueued = rig()
        afterQueued.transport.onPoll = { _, n ->
            if (n == 1) HttpReply(200, queuedBody()) else HttpReply(CODE_TRANSPORT_FAILURE, "read timed out")
        }
        val two = afterQueued.cloud.run(7, voicedSeconds(5), "hi", 1L)
        assertTrue("outcome was $two", two is CloudOutcome.Park)
        // Nothing was done for us, so this park must be allowed to count against the
        // gate's backoff. Reporting it as progress is how a dead server stays "busy".
        assertFalse((two as CloudOutcome.Park).progressed)
    }

    @Test
    fun `429 parks without recording anything about the piece`() = runTest {
        val r = rig()
        r.transport.onSubmit = { HttpReply(429, """{"error":"Too many pending jobs (max 1000)"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(5), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Park)
        assertEquals(CloudJobState.NEW, r.jobs.forChunk(7).single().state)
        assertEquals(0, r.transport.polls.size)
    }

    @Test
    fun `a 2xx upload reply with no job id is fatal, not an endless park`() = runTest {
        val r = rig()
        r.transport.onSubmit = { HttpReply(200, """{"status":"queued"}""") }

        val outcome = r.cloud.run(7, voicedSeconds(5), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Rejected)
    }

    // ---- the wake probe -------------------------------------------------------

    @Test
    fun `a captive portal answering health parks without uploading anything`() = runTest {
        val r = rig()
        r.transport.health = HttpReply(200, "<html><body>Please sign in to continue</body></html>")

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Park)
        assertEquals("3.8 MB must not be pushed into a hotel login page", 0, r.transport.uploads.size)
    }

    @Test
    fun `an empty voiced stream settles without touching the network`() = runTest {
        val r = rig()

        val outcome = r.cloud.run(7, FloatArray(0), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val settled = outcome as CloudOutcome.Settled
        assertTrue(settled.pieces.isEmpty())
        assertEquals(0L, settled.elapsedMs)
        assertTrue("not even a health probe", r.transport.calls.isEmpty())
    }

    // ---- planning -------------------------------------------------------------

    @Test
    fun `a stream longer than one piece is split into contiguous pieces covering all of it`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("ठीक")) }
        // Deliberately not a whole number of pieces, and not a whole number of
        // milliseconds either: the tail is where a slicing error hides.
        val samples = 16_000 * 250 + 7
        val audio = FloatArray(samples) { 0.1f }

        val outcome = r.cloud.run(7, audio, "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val rows = r.jobs.forChunk(7)
        assertEquals(3, rows.size)
        assertEquals(0L, rows.first().offsetMs)
        for (i in 1 until rows.size) {
            // Contiguity, not just a sum: an overlap and a gap of equal size add up
            // to the right total while transcribing one span twice and another never.
            assertEquals(
                "piece $i does not start where piece ${i - 1} ends",
                rows[i - 1].offsetMs + rows[i - 1].durationMs,
                rows[i].offsetMs,
            )
            assertEquals(i, rows[i].pieceIndex)
        }
        rows.forEach {
            assertTrue("piece of ${it.durationMs} ms exceeds the server's cap", it.durationMs <= 120_000L)
            assertTrue("empty piece", it.durationMs > 0)
        }

        val totalMs = samples * 1000L / 16_000
        assertEquals("the pieces must account for the whole stream", totalMs, rows.sumOf { it.durationMs })
        // What the server was actually handed, recovered from the WAV headers. The
        // pipeline records a coverage HOLE when a chunk's transcript accounts for
        // less than its voiced audio, so losing samples here would mark every chunk
        // as holed forever and put its WAV permanently beyond the disk valve.
        val lost = samples - r.transport.uploadedSamples.sum()
        assertTrue("$lost samples never reached the server", lost in 0..15)
        assertEquals(3, r.transport.uploads.size)
    }

    @Test
    fun `stored pieces are resumed, not re-uploaded, when the stream is unchanged`() = runTest {
        val r = rig()
        r.jobs.seed(
            CloudJobEntity(
                chunkId = 7, pieceIndex = 0, offsetMs = 0, durationMs = 30_000,
                languageSent = "hi-IN", streamFingerprint = 99L, jobId = "batch_old",
                state = CloudJobState.COMPLETED, transcript = "पुराना",
            )
        )

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", fingerprint = 99L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        assertEquals("पुराना", (outcome as CloudOutcome.Settled).pieces.single().text)
        assertEquals("the whole point of the durable row", 0, r.transport.uploads.size)
        assertEquals(0, r.transport.polls.size)
    }

    @Test
    fun `a changed fingerprint discards stored pieces instead of reusing them`() = runTest {
        val r = rig()
        r.jobs.seed(
            CloudJobEntity(
                chunkId = 7, pieceIndex = 0, offsetMs = 0, durationMs = 30_000,
                languageSent = "hi-IN", streamFingerprint = 99L, jobId = "batch_old",
                state = CloudJobState.COMPLETED, transcript = "पुराना",
            )
        )
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("नया")) }

        // Same chunk, different voiced stream: the gate or the gain changed, so the
        // stored offsets no longer describe this audio.
        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", fingerprint = 100L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        assertEquals(
            "text cut from a different stream must not be reused",
            "नया",
            (outcome as CloudOutcome.Settled).pieces.single().text,
        )
        assertEquals(1, r.transport.uploads.size)
        assertEquals(100L, r.jobs.forChunk(7).single().streamFingerprint)
    }

    @Test
    fun `a piece the server refused is reported alongside the pieces it returned`() = runTest {
        val r = rig()
        r.transport.onSubmit = { n ->
            if (n == 1) HttpReply(201, acceptedBody("batch_1"))
            else HttpReply(400, """{"error":"Audio too short"}""")
        }
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("पहला")) }

        val outcome = r.cloud.run(7, voicedSeconds(200), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        val pieces = (outcome as CloudOutcome.Settled).pieces
        assertEquals(2, pieces.size)
        assertFalse(pieces[0].rejected)
        assertEquals("पहला", pieces[0].text)
        // The caller covers this span with the on-device engine, so it needs the
        // span, not just the fact of the refusal. Dropping it left those seconds
        // transcribed by nothing at all.
        assertTrue(pieces[1].rejected)
        assertEquals(120_000L, pieces[1].offsetMs)
        assertEquals(80_000L, pieces[1].durationMs)
        assertEquals("", pieces[1].text)
    }

    // ---- one chunk at a time --------------------------------------------------

    @Test
    fun `a chunk holding the server's only worker parks everyone else without uploading`() = runTest {
        val r = rig()
        r.jobs.seed(
            CloudJobEntity(
                chunkId = 99, pieceIndex = 0, offsetMs = 0, durationMs = 30_000,
                languageSent = "hi-IN", streamFingerprint = 1L, jobId = "batch_other",
                state = CloudJobState.SUBMITTED,
            )
        )

        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Park)
        val park = outcome as CloudOutcome.Park
        assertTrue("reason was ${park.reason}", park.reason.contains("only worker"))
        assertEquals("a second upload only makes both look stuck", 0, r.transport.uploads.size)
    }

    // ---- language -------------------------------------------------------------

    @Test
    fun `english is never sent to the server`() {
        // The batch path does not validate language_code and coerces anything not in
        // its map to Malayalam. "en-IN" is not in the map, and the failure looks like
        // success: HTTP 200, non-empty text, stored and labelled English.
        assertFalse(CloudTranscriber.supports("en"))
        assertTrue(CloudTranscriber.supports("hi"))
        assertTrue(CloudTranscriber.supports("mr"))
        assertTrue(CloudTranscriber.supports("auto"))
    }

    @Test
    fun `auto resolves to Hindi, both on the wire and on the stored segment`() {
        assertEquals("hi-IN", CloudTranscriber.serverLanguage("auto"))
        assertEquals("hi-IN", CloudTranscriber.serverLanguage("hi"))
        assertEquals("mr-IN", CloudTranscriber.serverLanguage("mr"))
        // The server echoes the posted code back, so reading its reply as a detection
        // result was fiction written into every stored row.
        assertEquals("hi", CloudTranscriber.resolvedLanguage("auto"))
        assertEquals("mr", CloudTranscriber.resolvedLanguage("mr"))
    }

    @Test
    fun `the language a piece was posted as is frozen on the row, not re-read from settings`() = runTest {
        val r = rig()
        r.jobs.seed(
            CloudJobEntity(
                chunkId = 7, pieceIndex = 0, offsetMs = 0, durationMs = 30_000,
                languageSent = "mr-IN", streamFingerprint = 1L, jobId = "batch_mr",
                state = CloudJobState.SUBMITTED,
            )
        )
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("मराठी मजकूर")) }

        // The user switched the setting to Hindi while this chunk was parked.
        val outcome = r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        assertTrue("outcome was $outcome", outcome is CloudOutcome.Settled)
        assertEquals(
            "text the server already returned must not be relabelled",
            "mr",
            (outcome as CloudOutcome.Settled).pieces.single().language,
        )
    }

    @Test
    fun `a fresh piece is posted as the server's own code`() = runTest {
        val r = rig()
        r.cloud.run(7, voicedSeconds(5), "mr", 1L)
        assertEquals(listOf("mr-IN"), r.transport.languagesSent)
    }
}
