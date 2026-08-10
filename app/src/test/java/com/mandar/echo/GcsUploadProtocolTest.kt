package com.mandar.echo

import com.mandar.echo.stt.GcsUploadProtocol
import com.mandar.echo.stt.GcsUploadProtocol.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table tests over the upload decision surface, in the same spirit as
 * [BatchProtocolTest]: every status code this can see, and what it must do
 * about it, without opening a socket.
 *
 * The cases that matter are the ones that look like errors and are not (308),
 * and the ones that look transient and are not (401).
 */
class GcsUploadProtocolTest {

    // ---- resume offset ---------------------------------------------------

    @Test
    fun rangeHeaderIsInclusiveSoTheNextOffsetIsOnePastIt() {
        // GCS says "I have bytes 0 through 999", meaning 1000 bytes, so the
        // next byte to send is 1000. Off by one here silently corrupts every
        // resumed upload by duplicating or dropping a byte.
        assertEquals(1000L, GcsUploadProtocol.nextOffset("bytes=0-999"))
    }

    @Test
    fun absentRangeMeansNothingWasStored() {
        // GCS omits the header rather than sending an empty range.
        assertEquals(0L, GcsUploadProtocol.nextOffset(null))
        assertEquals(0L, GcsUploadProtocol.nextOffset(""))
    }

    @Test
    fun malformedRangeFallsBackToTheStartRatherThanCrashing() {
        assertEquals(0L, GcsUploadProtocol.nextOffset("bytes=oops"))
        assertEquals(0L, GcsUploadProtocol.nextOffset("garbage"))
    }

    // ---- upload ----------------------------------------------------------

    @Test
    fun `308 is progress, not failure`() {
        // The single most important case. 308 means the session is alive and
        // partially filled; treating it as an error turns a resumable upload
        // into an endless loop of full re-uploads over a metered radio.
        val step = GcsUploadProtocol.afterUpload(308, "bytes=0-524287", total = 19_200_000)
        assertEquals(Step.Resume(524_288L), step)
    }

    @Test
    fun uploadSucceedsOn200And201() {
        assertEquals(Step.Done, GcsUploadProtocol.afterUpload(200, null, 1))
        assertEquals(Step.Done, GcsUploadProtocol.afterUpload(201, null, 1))
    }

    @Test
    fun aGoneSessionRestartsRatherThanRetries() {
        // Retrying a 404 session forever is a stuck queue; the fix is a new URL.
        assertTrue(GcsUploadProtocol.afterUpload(404, null, 1) is Step.Restart)
        assertTrue(GcsUploadProtocol.afterUpload(410, null, 1) is Step.Restart)
    }

    @Test
    fun rateLimitingIsTransient() {
        assertTrue(GcsUploadProtocol.afterUpload(429, null, 1) is Step.Retry)
    }

    @Test
    fun serverErrorsAreTransientAndClientErrorsAreNot() {
        assertTrue(GcsUploadProtocol.afterUpload(500, null, 1) is Step.Retry)
        assertTrue(GcsUploadProtocol.afterUpload(503, null, 1) is Step.Retry)
        assertTrue(GcsUploadProtocol.afterUpload(400, null, 1) is Step.Halt)
    }

    // ---- session start ---------------------------------------------------

    @Test
    fun sessionStartReturnsTheLocationToUploadTo() {
        val step = GcsUploadProtocol.afterStart(201, "https://storage.googleapis.com/session/abc")
        assertEquals(Step.Upload("https://storage.googleapis.com/session/abc"), step)
    }

    @Test
    fun anExpiredSignedUrlIsRestartedNotHalted() {
        // A signed URL has a TTL. Past it the request is refused, and the only
        // fix is minting another -- which is a restart, not a configuration
        // problem the user has to be told about.
        assertTrue(GcsUploadProtocol.afterStart(403, null) is Step.Restart)
        assertTrue(GcsUploadProtocol.afterStart(400, null) is Step.Restart)
    }

    @Test
    fun a201WithoutLocationIsRetriedRatherThanTrusted() {
        assertTrue(GcsUploadProtocol.afterStart(201, null) is Step.Retry)
        assertTrue(GcsUploadProtocol.afterStart(201, "") is Step.Retry)
    }

    // ---- mint ------------------------------------------------------------

    @Test
    fun mintIsParsedFromTheServiceResponse() {
        val body = JSONObject()
            .put("url", "https://storage.googleapis.com/signed")
            .put("object", "pending/1786358637983.wav")
            .put("bucket", "agentbillboard-echo-ingest")
            .toString()
        val minted = GcsUploadProtocol.parseMint(200, body)!!
        assertEquals("pending/1786358637983.wav", minted.objectName)
        assertEquals("https://storage.googleapis.com/signed", minted.url)
    }

    @Test
    fun aMintMissingItsUrlIsNotAMint() {
        val body = JSONObject().put("object", "pending/1.wav").toString()
        assertNull(GcsUploadProtocol.parseMint(200, body))
        assertNull(GcsUploadProtocol.parseMint(200, "not json at all"))
        assertNull(GcsUploadProtocol.parseMint(200, null))
    }

    @Test
    fun aRejectedKeyHaltsAndDoesNotLookLikeAnOutage() {
        // The failure this ordering exists to prevent: a wrong key retried
        // forever as though the network were down, which is how a
        // configuration error hid as an outage in the path this replaces.
        assertTrue(GcsUploadProtocol.afterMint(401, null) is Step.Halt)
        assertTrue(GcsUploadProtocol.afterMint(403, null) is Step.Halt)
        assertTrue(GcsUploadProtocol.afterMint(500, null) is Step.Retry)
        assertTrue(GcsUploadProtocol.afterMint(503, null) is Step.Retry)
    }

    @Test
    fun anUnparseableSuccessIsRetriedNotTreatedAsSuccess() {
        assertTrue(GcsUploadProtocol.afterMint(200, "{}") is Step.Retry)
    }

    @Test
    fun mintRequestCarriesTheEpochThatAnchorsTheTranscript() {
        // The object name is derived from startedAtMs server-side, and it is
        // the only wall-clock anchor a transcript ever gets. Losing it puts a
        // whole day at midnight.
        val json = JSONObject(
            GcsUploadProtocol.mintRequest(1786358637983L, "wav", "audio/wav", 120560L)
        )
        assertEquals(1786358637983L, json.getLong("startedAtMs"))
        assertEquals("wav", json.getString("ext"))
        assertEquals(120560L, json.getLong("sizeBytes"))
    }
}
