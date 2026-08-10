package com.mandar.echo

import com.mandar.echo.stt.CODE_TRANSPORT_FAILURE
import com.mandar.echo.stt.GcsUploader
import com.mandar.echo.stt.UploadReply
import com.mandar.echo.stt.UploadTransport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The upload loop, driven through the paths a phone actually takes: a link that
 * drops mid-chunk, a session that expires, a key that is wrong.
 *
 * No socket. The transport is a scripted fake, which is the only way to reach
 * "the connection died twice and then resumed from 8 MB" reliably.
 */
class GcsUploaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun mintBody() = JSONObject()
        .put("url", "https://signed.example/upload")
        .put("object", "pending/1786358637983.wav")
        .put("bucket", "b")
        .toString()

    /** Replies in order; the last one repeats once the script runs out. */
    private class Fake(
        val mint: List<UploadReply> = listOf(UploadReply(200)),
        val start: List<UploadReply> = listOf(UploadReply(201, header = "https://session/1")),
        val put: List<UploadReply> = listOf(UploadReply(200)),
    ) : UploadTransport {
        var mints = 0
        var starts = 0
        var puts = 0
        val offsets = mutableListOf<Long>()

        private fun next(list: List<UploadReply>, i: Int) = list[minOf(i, list.size - 1)]

        override suspend fun mint(url: String, apiKey: String, body: String) =
            next(mint, mints++)

        override suspend fun startSession(signedUrl: String, contentType: String) =
            next(start, starts++)

        override suspend fun putFrom(
            sessionUri: String, file: File, offset: Long, total: Long, contentType: String,
        ): UploadReply {
            offsets += offset
            return next(put, puts++)
        }
    }

    private fun uploader(fake: UploadTransport) =
        GcsUploader(fake, "https://echo-upload.example/v1/upload-url", "key")

    private fun audio(bytes: Int = 1024): File =
        temp.newFile("chunk.wav").apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun happyPathReportsTheObjectItWrote() = runTest {
        val fake = Fake(mint = listOf(UploadReply(200, mintBody())))
        val result = uploader(fake).upload(audio(), 1786358637983L)
        assertEquals(GcsUploader.Result.Uploaded("pending/1786358637983.wav"), result)
        assertEquals(1, fake.puts)
    }

    @Test
    fun aDroppedConnectionResumesInsteadOfStartingOver() = runTest {
        // The whole point of resumable upload. GCS reports it holds 512 KB, and
        // the next PUT must continue from there -- not from zero, and not with a
        // fresh session.
        val fake = Fake(
            mint = listOf(UploadReply(200, mintBody())),
            put = listOf(
                UploadReply(CODE_TRANSPORT_FAILURE),
                UploadReply(308, header = "bytes=0-524287"),
                UploadReply(200),
            ),
        )
        val result = uploader(fake).upload(audio(), 1L)
        assertTrue(result is GcsUploader.Result.Uploaded)
        assertEquals(listOf(0L, 0L, 524_288L), fake.offsets)
        // One session throughout: a dropped connection must not re-mint.
        assertEquals(1, fake.mints)
        assertEquals(1, fake.starts)
    }

    @Test
    fun anExpiredSignedUrlIsReMintedRatherThanAbandoned() = runTest {
        val fake = Fake(
            mint = listOf(UploadReply(200, mintBody())),
            start = listOf(
                UploadReply(403),                                   // expired
                UploadReply(201, header = "https://session/2"),      // fresh URL works
            ),
        )
        val result = uploader(fake).upload(audio(), 1L)
        assertTrue(result is GcsUploader.Result.Uploaded)
        assertEquals(2, fake.mints)
    }

    @Test
    fun aDeadSessionMintsAgainInsteadOfRetryingIntoTheVoid() = runTest {
        val fake = Fake(
            mint = listOf(UploadReply(200, mintBody())),
            put = listOf(UploadReply(404), UploadReply(200)),
        )
        val result = uploader(fake).upload(audio(), 1L)
        assertTrue(result is GcsUploader.Result.Uploaded)
        assertEquals(2, fake.mints)
    }

    @Test
    fun aRejectedKeyHaltsWithoutTouchingTheNetworkAgain() = runTest {
        val fake = Fake(mint = listOf(UploadReply(401)))
        val result = uploader(fake).upload(audio(), 1L)
        assertTrue("expected Halt, got $result", result is GcsUploader.Result.Halt)
        assertEquals(0, fake.starts)
    }

    @Test
    fun anUnreachableServiceIsRetryableNotFatal() = runTest {
        // No network is the normal state of a phone in a lift. It must never
        // look like a configuration problem, because a Halt is sticky.
        val fake = Fake(mint = listOf(UploadReply(CODE_TRANSPORT_FAILURE)))
        assertTrue(uploader(fake).upload(audio(), 1L) is GcsUploader.Result.Retry)
    }

    @Test
    fun aMissingOrEmptyFileNeverReportsSuccess() = runTest {
        val fake = Fake()
        assertTrue(
            uploader(fake).upload(File(temp.root, "nope.wav"), 1L) is GcsUploader.Result.Halt
        )
        assertTrue(uploader(fake).upload(audio(bytes = 0), 1L) is GcsUploader.Result.Halt)
        assertEquals(0, fake.mints)
    }

    @Test
    fun endlessRestartsAreBounded() = runTest {
        // A permanently dead session must give the chunk back rather than spin.
        val fake = Fake(
            mint = listOf(UploadReply(200, mintBody())),
            put = listOf(UploadReply(404)),
        )
        val result = uploader(fake).upload(audio(), 1L)
        assertTrue(result is GcsUploader.Result.Retry)
        assertTrue("re-minted ${fake.mints} times, unbounded", fake.mints <= 5)
    }

    @Test
    fun progressResetsTheRetryBudget() = runTest {
        // A slow link that keeps moving is not a failing one. Six stalls with
        // progress between them must still finish.
        val script = mutableListOf<UploadReply>()
        repeat(6) {
            script += UploadReply(CODE_TRANSPORT_FAILURE)
            script += UploadReply(308, header = "bytes=0-${(it + 1) * 100 - 1}")
        }
        script += UploadReply(200)
        val fake = Fake(mint = listOf(UploadReply(200, mintBody())), put = script)
        assertTrue(uploader(fake).upload(audio(), 1L) is GcsUploader.Result.Uploaded)
    }
}
