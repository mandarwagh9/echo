package com.mandar.echo.stt

import android.util.Log
import com.mandar.echo.audio.AudioFormatSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "CloudTranscriber"

/**
 * Transcribes through a self-hosted [vexyl-stt](https://github.com/vexyl-ai/vexyl-stt)
 * server, which wraps AI4Bharat's IndicConformer-600M.
 *
 * This exists because the on-device path cannot do the job. Measured on the same
 * fixtures: Whisper Base recovers 0.23 of Hindi words and 0.00 of Marathi, Small
 * manages 0.54 and 0.13, and IndicConformer returns both verbatim. A 600M
 * conformer will not run on a phone, so the trade is explicit — accuracy for
 * sending audio to a server the user owns.
 *
 * Failure is never fatal here. Every error returns [Result.failure] so
 * [TranscriptionPipeline] can fall back to the local engine: a recorder that
 * stops working in a tunnel is worse than one that transcribes badly there.
 */
class CloudTranscriber(
    private val baseUrl: String,
    private val apiKey: String,
) {

    private companion object {
        /** The server rejects anything longer; Echo's chunks are 10 minutes. */
        const val MAX_SEGMENT_SECONDS = 4 * 60

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000

        /** Whole-chunk ceiling. Beyond this we give up and let the phone try. */
        const val JOB_TIMEOUT_MS = 15 * 60_000L

        const val POLL_INTERVAL_MS = 3_000L
    }

    val isConfigured: Boolean get() = baseUrl.startsWith("http")

    /** Cheap reachability probe, so the UI can say something truthful. */
    suspend fun health(): Result<String> = runCatching {
        val conn = open("$baseUrl/health", "GET")
        conn.inputStream.use { it.readBytes().decodeToString() }
    }

    /**
     * Transcribes [samples] (16 kHz mono, -1..1), splitting into server-sized
     * pieces and concatenating the results.
     *
     * Timestamps come back relative to each piece, so they are shifted by the
     * piece's offset before being returned — without that, every segment after
     * the first would claim to have happened at the start of the chunk.
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result.failure<TranscriptionResult>(
                IllegalStateException("no server configured")
            )
        }
        if (samples.isEmpty()) {
            return@withContext Result.success(TranscriptionResult(emptyList(), language, 0))
        }

        val started = System.currentTimeMillis()
        val perSegment = MAX_SEGMENT_SECONDS * AudioFormatSpec.SAMPLE_RATE
        val segments = ArrayList<WhisperSegment>()
        var detected = language

        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + perSegment, samples.size)
            val piece = samples.copyOfRange(offset, end)
            val offsetMs = offset * 1000L / AudioFormatSpec.SAMPLE_RATE

            val result = runCatching { transcribeOne(piece, language) }
                .getOrElse { return Result.failure(it) }
                ?: return Result.failure(IllegalStateException("server returned no transcript"))

            if (result.text.isNotBlank()) {
                segments += WhisperSegment(
                    startMs = offsetMs,
                    endMs = offsetMs + piece.size * 1000L / AudioFormatSpec.SAMPLE_RATE,
                    text = result.text.trim(),
                )
            }
            result.language?.takeIf { it.isNotBlank() }?.let { detected = it.substringBefore('-') }
            offset = end
        }

        val elapsed = System.currentTimeMillis() - started
        Log.i(TAG, "cloud transcript: ${segments.size} segments in $elapsed ms")
        return Result.success(TranscriptionResult(segments, detected, elapsed))
    }

    private data class Piece(val text: String, val language: String?)

    private suspend fun transcribeOne(samples: FloatArray, language: String): Piece? {
        val jobId = submit(samples, language) ?: return null
        return withTimeoutOrNull(JOB_TIMEOUT_MS) {
            while (true) {
                poll(jobId)?.let { return@withTimeoutOrNull it }
                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    /** POST /batch/transcribe as multipart, since the server expects a file part. */
    private fun submit(samples: FloatArray, language: String): String? {
        val boundary = "----echo${System.nanoTime()}"
        val wav = wavBytes(samples)
        val conn = open("$baseUrl/batch/transcribe", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n")
            out.writeBytes("Content-Type: audio/wav\r\n\r\n")
            out.write(wav)
            out.writeBytes("\r\n--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"language_code\"\r\n\r\n")
            out.writeBytes(serverLanguage(language))
            out.writeBytes("\r\n--$boundary--\r\n")
        }

        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            throw IllegalStateException("submit failed ${conn.responseCode}: ${err.take(200)}")
        }
        val body = conn.inputStream.use { it.readBytes().decodeToString() }
        return JSONObject(body).optString("job_id").takeIf { it.isNotBlank() }
    }

    /** Returns null while the job is still queued or running. */
    private fun poll(jobId: String): Piece? {
        val conn = open("$baseUrl/batch/result/$jobId", "GET")
        val code = conn.responseCode
        if (code == 202) return null
        if (code !in 200..299) {
            val err = conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
            throw IllegalStateException("poll failed $code: ${err.take(200)}")
        }
        val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
        // Tolerate either a completed-with-status body or a bare transcript.
        val status = json.optString("status")
        if (status.isNotBlank() && status !in setOf("completed", "done", "finished")) {
            if (status == "failed" || status == "error") {
                throw IllegalStateException("server job failed: ${json.optString("error")}")
            }
            return null
        }
        val text = listOf("transcript", "text", "result")
            .firstNotNullOfOrNull { json.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: return null
        return Piece(text, json.optString("language").takeIf { it.isNotBlank() })
    }

    /** Echo speaks "hi"/"mr"/"en"; the server wants BCP-47-ish codes. */
    private fun serverLanguage(code: String): String = when (code) {
        "hi" -> "hi-IN"
        "mr" -> "mr-IN"
        "en" -> "en-IN"
        // The server has no auto mode; Hindi is the best single default for a
        // code-switched day, and it is what whisper's own detector picks anyway.
        else -> "hi-IN"
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Echo/1.0")
            if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey)
        }

    /** 16-bit PCM WAV, which is what the server accepts. */
    private fun wavBytes(samples: FloatArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        val sr = AudioFormatSpec.SAMPLE_RATE

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sr); putInt(sr * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        out.write(header.array())

        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            body.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.write(body.array())
        return out.toByteArray()
    }
}
