package com.mandar.echo.stt

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "HttpUploadTransport"

/**
 * The socket half of [GcsUploader].
 *
 * Same discipline as [HttpUrlTransport], for the same reasons: a phone radio
 * stalls rather than fails, and `HttpURLConnection` cannot be interrupted by
 * coroutine cancellation — only by closing the socket. Timeouts here are set on
 * the connection itself, and the upload writes in slices so a dead link is
 * noticed within one write timeout rather than at the end of 19 MB.
 *
 * Confined to two IO threads. Losing the microphone because an upload wedged the
 * shared dispatcher is not a trade this app may make.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HttpUploadTransport(
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
) : UploadTransport {

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000

        /** Small enough that a stalled socket is noticed promptly, large enough to be efficient. */
        const val SLICE = 256 * 1024
    }

    override suspend fun mint(url: String, apiKey: String, body: String): UploadReply =
        withContext(io) {
            request(url, "POST") { conn ->
                conn.setRequestProperty("X-Echo-Key", apiKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
        }

    override suspend fun startSession(signedUrl: String, contentType: String): UploadReply =
        withContext(io) {
            request(signedUrl, "POST", headerToRead = "Location") { conn ->
                // The header that makes the URL a session opener rather than a
                // single-shot PUT. Without it GCS treats this as the upload
                // itself and the signature will not match.
                conn.setRequestProperty("x-goog-resumable", "start")
                conn.setRequestProperty("Content-Type", contentType)
                conn.doOutput = true
                conn.outputStream.use { it.write(ByteArray(0)) }
            }
        }

    override suspend fun putFrom(
        sessionUri: String,
        file: File,
        offset: Long,
        total: Long,
        contentType: String,
    ): UploadReply = withContext(io) {
        request(sessionUri, "PUT", headerToRead = "Range") { conn ->
            val remaining = total - offset
            conn.setRequestProperty("Content-Type", contentType)
            // Inclusive on both ends, and the total is the whole object, not the
            // remainder -- GCS is being told where this fragment sits in the
            // finished file, not how much is coming.
            if (offset > 0) {
                conn.setRequestProperty(
                    "Content-Range", "bytes $offset-${total - 1}/$total",
                )
            }
            conn.setFixedLengthStreamingMode(remaining)
            conn.doOutput = true

            RandomAccessFile(file, "r").use { source ->
                source.seek(offset)
                BufferedOutputStream(conn.outputStream).use { sink ->
                    val buffer = ByteArray(SLICE)
                    var sent = 0L
                    while (sent < remaining) {
                        val want = minOf(SLICE.toLong(), remaining - sent).toInt()
                        val read = source.read(buffer, 0, want)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                    }
                    sink.flush()
                }
            }
        }
    }

    private fun request(
        url: String,
        method: String,
        headerToRead: String? = null,
        write: (HttpURLConnection) -> Unit,
    ): UploadReply {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            write(conn)

            // responseCode is where the request is actually sent, so it throws
            // for transport failures -- and 4xx/5xx arrive here as a code, not
            // an exception, which is what the protocol table wants.
            val code = conn.responseCode
            val header = headerToRead?.let { conn.getHeaderField(it) }
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() }
                .orEmpty()
            UploadReply(code, body, header)
        } catch (t: Throwable) {
            Log.w(TAG, "$method failed: ${t.message}")
            UploadReply(CODE_TRANSPORT_FAILURE, t.message.orEmpty())
        } finally {
            // Closes the descriptor. Without this a stalled upload leaks one per
            // attempt, which over a day of retries is how a process runs out.
            runCatching { conn?.disconnect() }
        }
    }
}
