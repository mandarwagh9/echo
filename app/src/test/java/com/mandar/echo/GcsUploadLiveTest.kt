package com.mandar.echo

import com.mandar.echo.stt.GcsUploader
import com.mandar.echo.stt.HttpUploadTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Drives the *shipping* transport against the *live* upload service.
 *
 * Everything else about the upload path is tested against a fake, which is
 * right: a fake is the only way to reach a link that drops mid-chunk or a
 * session that expires. But a fake cannot tell you that `HttpURLConnection`
 * actually speaks GCS resumable upload correctly -- that the session-start POST
 * needs an empty body and `x-goog-resumable: start`, that `setFixedLengthStreamingMode`
 * and a `Content-Range` agree, that the signature covers the headers actually
 * sent. Those are exactly the mistakes a unit test cannot see and a phone
 * discovers at 3am.
 *
 * So this runs the real class against the real service, once, deliberately.
 *
 * Skipped unless ECHO_LIVE_UPLOAD_URL and ECHO_LIVE_UPLOAD_KEY are set, so the
 * ordinary suite stays hermetic and offline:
 *
 *     ECHO_LIVE_UPLOAD_URL=... ECHO_LIVE_UPLOAD_KEY=... \
 *       ./gradlew :app:testReleaseUnitTest --tests '*GcsUploadLiveTest'
 */
class GcsUploadLiveTest {

    @Test
    fun theRealTransportUploadsToTheRealService() {
        val url = System.getenv("ECHO_LIVE_UPLOAD_URL")
        val key = System.getenv("ECHO_LIVE_UPLOAD_KEY")
        assumeTrue("live upload test not configured", !url.isNullOrBlank() && !key.isNullOrBlank())

        // A real WAV, because the service and Chirp 3 both care what this is.
        val wav = File("src/androidTest/assets/marathi.wav")
        assumeTrue("fixture missing at ${wav.absolutePath}", wav.exists())

        val uploader = GcsUploader(
            transport = HttpUploadTransport(),
            mintUrl = "$url/v1/upload-url",
            apiKey = key!!,
        )

        val startedAt = System.currentTimeMillis()
        val result = runBlocking { uploader.upload(wav, startedAt, contentType = "audio/wav") }

        assertTrue(
            "expected the object to land in the bucket, got $result",
            result is GcsUploader.Result.Uploaded,
        )
        val objectName = (result as GcsUploader.Result.Uploaded).objectName
        assertTrue(
            "object should be named for the chunk's start, was $objectName",
            objectName == "pending/$startedAt.wav",
        )
        println("live upload OK -> $objectName")
    }
}
