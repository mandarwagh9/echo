package com.mandar.echo

import com.mandar.echo.stt.CODE_TRANSPORT_FAILURE
import com.mandar.echo.stt.HealthOutcome
import com.mandar.echo.stt.PollOutcome
import com.mandar.echo.stt.SubmitOutcome
import com.mandar.echo.stt.interpretHealth
import com.mandar.echo.stt.interpretPoll
import com.mandar.echo.stt.interpretSubmit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract, pinned against the bodies vexyl-stt actually emits.
 *
 * Every row here is copied from the server source rather than imagined, because
 * the bug this replaces was a client that guessed at three field names and used
 * blankness as a readiness test. The load-bearing row is the first one: a job
 * that completed with an empty transcript must be a terminal success, not a
 * reason to keep polling.
 */
class BatchProtocolTest {

    // ---- poll: the live bug ------------------------------------------------

    @Test
    fun `a completed job with an empty transcript is a terminal success`() {
        // Exactly what the phone was being sent, every three seconds, for 15 minutes.
        val outcome = interpretPoll(200, """{"job_id":"batch_1","status":"completed","transcript":""}""")
        assertEquals(PollOutcome.Completed(""), outcome)
        assertFalse("an empty transcript is a finished job, not a transient one", outcome.transient)
    }

    @Test
    fun `a whitespace-only transcript is also completed`() {
        assertEquals(PollOutcome.Completed("   "), interpretPoll(200, """{"status":"completed","transcript":"   "}"""))
    }

    @Test
    fun `a completed job with no transcript key at all is malformed`() {
        // Presence of the key is the test. Absence is a contract violation and
        // must surface; it must never be confused with an empty value.
        val outcome = interpretPoll(200, """{"job_id":"batch_1","status":"completed"}""")
        assertTrue(outcome is PollOutcome.Malformed)
        assertFalse(outcome.transient)
    }

    @Test
    fun `a real transcript comes through intact`() {
        assertEquals(
            PollOutcome.Completed("आज मीटिंग होती"),
            interpretPoll(200, """{"status":"completed","transcript":"आज मीटिंग होती"}"""),
        )
    }

    // ---- poll: the rest of the closed enum ---------------------------------

    @Test
    fun `queued and processing are progress, not failure`() {
        assertEquals(PollOutcome.Queued, interpretPoll(200, """{"status":"queued","duration":120.0}"""))
        assertEquals(PollOutcome.Processing, interpretPoll(200, """{"status":"processing","duration":120.0}"""))
    }

    @Test
    fun `a failed job reports the reason from error_message`() {
        // The old client read "error"; the server writes "error_message", so every
        // real server-side failure logged as "server job failed: " with nothing
        // after the colon — invisible in the field.
        val outcome = interpretPoll(200, """{"status":"failed","error_message":"Transcription failed"}""")
        assertEquals(PollOutcome.Failed("Transcription failed"), outcome)
    }

    @Test
    fun `a failed job with no reason still says something`() {
        val outcome = interpretPoll(200, """{"status":"failed"}""") as PollOutcome.Failed
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun `an unrecognised status is malformed rather than polled forever`() {
        val outcome = interpretPoll(200, """{"status":"almost_done"}""")
        assertTrue("unknown status was $outcome", outcome is PollOutcome.Malformed)
    }

    @Test
    fun `a missing job is JobGone, which is resubmittable`() {
        val outcome = interpretPoll(404, """{"error":"Job not found","job_id":"batch_1"}""")
        assertEquals(PollOutcome.JobGone, outcome)
        assertTrue("scale-to-zero must not be permanent", outcome.transient)
    }

    @Test
    fun `403 while polling is a credentials problem, not an unreachable server`() {
        // This path answers with plain text, not JSON, so it must be classified by
        // code. Reading it by body shape would land it in "malformed".
        val outcome = interpretPoll(403, "Invalid or missing API key")
        assertTrue(outcome is PollOutcome.Unauthorized)
        assertFalse(outcome.transient)
    }

    @Test
    fun `server errors and transport failures are transient`() {
        assertTrue(interpretPoll(503, "").transient)
        assertTrue(interpretPoll(500, "").transient)
        assertTrue(interpretPoll(CODE_TRANSPORT_FAILURE, "timeout").transient)
    }

    @Test
    fun `a captive portal answering 200 with HTML is transient, not permanent`() {
        // A hotel login page must never be able to permanently downgrade a chunk
        // from IndicConformer to Whisper Base and consume its audio.
        val outcome = interpretPoll(200, "<html><body>Please sign in to continue</body></html>")
        assertTrue("portal HTML was $outcome", outcome is PollOutcome.Unreachable)
        assertTrue(outcome.transient)
    }

    // ---- submit ------------------------------------------------------------

    @Test
    fun `201 with a job id is accepted`() {
        // Pinned so nobody "tightens" the success check to == 200 and breaks every
        // upload: the server documents and sends 201 Created.
        val body = """{"job_id":"batch_9f2c1a0b","status":"queued","language":"hi-IN","audio_duration":118.4}"""
        assertEquals(SubmitOutcome.Accepted("batch_9f2c1a0b", 118.4), interpretSubmit(201, body))
    }

    @Test
    fun `a 2xx with no job id is malformed`() {
        assertTrue(interpretSubmit(200, """{"status":"queued"}""") is SubmitOutcome.Malformed)
    }

    @Test
    fun `a captive portal on submit is transient`() {
        val outcome = interpretSubmit(200, "<html>Sign in to WiFi</html>")
        assertTrue("portal HTML was $outcome", outcome is SubmitOutcome.Unreachable)
        assertTrue(outcome.transient)
    }

    @Test
    fun `400 and 413 are permanent refusals carrying the server reason`() {
        val tooLong = interpretSubmit(400, """{"error":"Audio too long (612.0s). Max 300s"}""")
        assertTrue(tooLong is SubmitOutcome.Rejected)
        assertTrue((tooLong as SubmitOutcome.Rejected).reason.contains("Audio too long"))
        assertFalse(tooLong.transient)

        assertTrue(interpretSubmit(413, """{"error":"File exceeds 25MB limit"}""") is SubmitOutcome.Rejected)
    }

    @Test
    fun `403 halts on credentials rather than failing a chunk`() {
        val outcome = interpretSubmit(403, """{"error":"Invalid or missing API key"}""")
        assertTrue(outcome is SubmitOutcome.Unauthorized)
        assertFalse(outcome.transient)
    }

    @Test
    fun `a redirect is a configuration error, not a chunk failure`() {
        // An http:// URL makes Cloud Run redirect, and a POST body is not replayed
        // across one. Without this row it fell through to "malformed" and every
        // chunk was permanently downgraded from one missing "s".
        listOf(301, 302, 307, 308).forEach { code ->
            assertTrue("$code was ${interpretSubmit(code, "")}", interpretSubmit(code, "") is SubmitOutcome.Misconfigured)
        }
    }

    @Test
    fun `404 on submit means the wrong URL`() {
        val outcome = interpretSubmit(404, """{"error":"Unknown endpoint: //batch/transcribe"}""")
        assertTrue(outcome is SubmitOutcome.Misconfigured)
    }

    @Test
    fun `429 and 5xx are transient`() {
        assertTrue(interpretSubmit(429, """{"error":"Too many pending jobs (max 1000)"}""") is SubmitOutcome.Overloaded)
        assertTrue(interpretSubmit(429, "").transient)
        assertTrue(interpretSubmit(503, "").transient)
        assertTrue(interpretSubmit(CODE_TRANSPORT_FAILURE, "broken pipe").transient)
    }

    // ---- health ------------------------------------------------------------

    @Test
    fun `health is judged by the body fingerprint, not the status code`() {
        val real = """{"status":"ok","model":"indic-conformer-600m-multilingual","device":"cpu",""" +
            """"batch_jobs_queued":0,"batch_jobs_total":1}"""
        assertEquals(HealthOutcome.Awake("indic-conformer-600m-multilingual"), interpretHealth(200, real))
    }

    @Test
    fun `a portal answering 200 to health is not an awake server`() {
        val outcome = interpretHealth(200, "<html><head><title>WiFi Login</title></head></html>")
        assertTrue("portal was $outcome", outcome is HealthOutcome.NotOurServer)
        assertTrue("a portal must not become a permanent failure", outcome.transient)
    }

    @Test
    fun `json from some other service is not an awake server`() {
        assertTrue(interpretHealth(200, """{"status":"ok"}""") is HealthOutcome.NotOurServer)
        assertTrue(interpretHealth(200, """{"ok":true}""") is HealthOutcome.NotOurServer)
    }

    @Test
    fun `a health timeout is unreachable`() {
        assertTrue(interpretHealth(CODE_TRANSPORT_FAILURE, "read timed out") is HealthOutcome.Unreachable)
    }
}
