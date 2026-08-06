package com.mandar.echo.stt

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "BatchTransport"

/** What a request produced: a real HTTP status, or [CODE_TRANSPORT_FAILURE] and the reason. */
internal data class HttpReply(val code: Int, val body: String)

/**
 * The one seam between the state machine and a socket.
 *
 * Everything above this line is pure or database-only, so the piece machine can be
 * driven through every branch in a unit test without a network.
 */
internal interface BatchTransport {
    suspend fun get(path: String, stallMs: Long): HttpReply

    /** Multipart POST of a WAV. Bounded on *stall*, not on elapsed time — see the implementation. */
    suspend fun postAudio(path: String, wav: ByteArray, languageCode: String, replyStallMs: Long): HttpReply
}

/**
 * HttpURLConnection with bounds that actually hold.
 *
 * Coroutine cancellation cannot interrupt a blocked HttpURLConnection read or
 * write — `withTimeout` around one is decoration. The only thing that unblocks a
 * stuck connection is closing its socket, so every request here holds the
 * connection in an [AtomicReference] and a watchdog calls `disconnect()` on it,
 * which makes the blocked call throw IOException. That is also what makes
 * `pipeline.stop()` genuinely interruptible, and it closes the descriptor leak
 * that left one undrained connection per poll for the life of a hung job.
 *
 * Everything is bounded on *stall* rather than on elapsed time. A fixed deadline
 * over a fixed-size payload cannot distinguish a slow link from a dead one: a
 * 3.84 MB piece needs about a minute at 500 kbit/s, and any wall-clock budget
 * tight enough to catch a dead socket also kills every transfer that would have
 * succeeded — then re-uploads it into the same wall, forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HttpUrlTransport(
    private val baseUrl: String,
    private val apiKey: String,
    // Confined to two threads so a slow or leaked connection can never starve the
    // shared IO dispatcher that also carries mic restart and the disk monitor.
    // Losing the microphone because an upload is wedged is not a trade worth making.
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    private val clock: () -> Long = System::currentTimeMillis,
) : BatchTransport {

    private companion object {
        /** TCP to the Cloud Run frontend, which is always up. A cold start is not a connect cost. */
        const val CONNECT_TIMEOUT_MS = 15_000

        /** No byte accepted by the socket for this long while uploading means the link is gone. */
        const val WRITE_STALL_MS = 30_000L

        /** Absolute ceiling, so nothing waits forever even while trickling. */
        const val CEILING_MS = 10 * 60_000L

        const val WRITE_SLICE = 64 * 1024
    }

    override suspend fun get(path: String, stallMs: Long): HttpReply =
        perform(path, "GET", stallMs, stallMs) { _, _ -> }

    override suspend fun postAudio(
        path: String,
        wav: ByteArray,
        languageCode: String,
        replyStallMs: Long,
    ): HttpReply {
        val boundary = "----echo${System.nanoTime()}"
        val head = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray()
        val tail = ("\r\n--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"language_code\"\r\n\r\n" +
            languageCode +
            "\r\n--$boundary--\r\n").toByteArray()

        return perform(
            path = path,
            method = "POST",
            writeStallMs = WRITE_STALL_MS,
            // The reply can legitimately take a Cloud Run cold start, because the
            // wake probe's result is not transferable: session affinity is
            // cookie-based and HttpURLConnection sends no cookies, and
            // --min-instances=0 permits reclamation between two connections.
            replyStallMs = replyStallMs,
            configure = { conn ->
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                // Never setChunkedStreamingMode. With no Content-Length this server
                // initialises content_length = 0, slices an empty body, and answers
                // "400 Missing 'file' field" -- a silent 100% upload failure behind
                // a misleading error. Fixed-length also stops the WAV being copied
                // a fourth time inside a service that must not be OOM-killed.
                conn.setFixedLengthStreamingMode(head.size.toLong() + wav.size + tail.size)
            },
        ) { conn, progress ->
            BufferedOutputStream(conn.outputStream, WRITE_SLICE).use { out ->
                out.write(head)
                var off = 0
                while (off < wav.size) {
                    val n = minOf(WRITE_SLICE, wav.size - off)
                    out.write(wav, off, n)
                    out.flush()
                    off += n
                    // A slice that returned means the socket accepted those bytes.
                    // One that blocks leaves this stamp alone and the watchdog fires.
                    progress.set(clock())
                }
                out.write(tail)
                out.flush()
                progress.set(clock())
            }
        }
    }

    private suspend fun perform(
        path: String,
        method: String,
        writeStallMs: Long,
        replyStallMs: Long,
        configure: (HttpURLConnection) -> Unit = {},
        writeBody: (HttpURLConnection, AtomicLong) -> Unit,
    ): HttpReply = withContext(io) {
        val holder = AtomicReference<HttpURLConnection?>(null)
        val progress = AtomicLong(clock())
        val allowance = AtomicLong(writeStallMs)
        val started = clock()
        var watchdog: Job? = null

        try {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                // A per-read backstop only. The watchdog is the real bound, because
                // a server that dribbles one byte a second resets this one forever.
                readTimeout = replyStallMs.coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
                // A 3xx must reach interpretSubmit as a named configuration error.
                // Following it silently would also lose a POST body, since
                // HttpURLConnection will not replay one.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Echo/1.0")
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey)
            }
            holder.set(conn)
            configure(conn)

            watchdog = CoroutineScope(io).launch {
                while (true) {
                    delay(1_000)
                    val now = clock()
                    val stalled = now - progress.get() > allowance.get()
                    val overrun = now - started > CEILING_MS
                    if (stalled || overrun) {
                        Log.w(TAG, "$method $path aborted (${if (stalled) "stalled" else "ceiling"})")
                        runCatching { holder.get()?.disconnect() }
                        return@launch
                    }
                }
            }

            writeBody(conn, progress)

            // The body is delivered; from here the server has the job whether or
            // not this coroutine survives. Reading the reply is what turns that
            // into a job id we can poll, so a cancellation landing in this window
            // must not orphan it -- an orphan pins ~7.7 MB of PCM server-side for
            // an hour and gets re-uploaded on the next claim.
            allowance.set(replyStallMs)
            progress.set(clock())
            withContext(NonCancellable) {
                val code = conn.responseCode
                val stream = runCatching { conn.errorStream }.getOrNull() ?: conn.inputStream
                HttpReply(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            HttpReply(CODE_TRANSPORT_FAILURE, t.message ?: t::class.java.simpleName)
        } finally {
            watchdog?.cancel()
            // Drain-and-disconnect on every path, including 202/404/5xx.
            runCatching { holder.get()?.disconnect() }
        }
    }
}
