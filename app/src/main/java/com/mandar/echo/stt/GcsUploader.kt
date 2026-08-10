package com.mandar.echo.stt

import android.util.Log
import com.mandar.echo.stt.GcsUploadProtocol.Step
import java.io.File

private const val TAG = "GcsUploader"

/**
 * One request's outcome: a real HTTP status, or [CODE_TRANSPORT_FAILURE].
 *
 * Reuses the sentinel BatchProtocol already defines rather than declaring a
 * second one — two constants meaning "the socket failed" is how they drift.
 * [header] carries the single response header each step cares about: `Location`
 * when opening a session, `Range` when resuming.
 */
internal data class UploadReply(val code: Int, val body: String = "", val header: String? = null)

/**
 * The one seam between the upload loop and a socket, so the loop below can be
 * driven through every branch — expiry, resume, a dead session, a rejected key —
 * without a network. Same reason [BatchTransport] exists.
 */
internal interface UploadTransport {
    suspend fun mint(url: String, apiKey: String, body: String): UploadReply

    /** Opens a resumable session. The session URI comes back in `Location`. */
    suspend fun startSession(signedUrl: String, contentType: String): UploadReply

    /** Sends `file` from `offset` to the end. `total` is the whole file's size. */
    suspend fun putFrom(sessionUri: String, file: File, offset: Long, total: Long, contentType: String): UploadReply
}

/**
 * Hands one recorded chunk to the batch pipeline.
 *
 * The loop is small because [GcsUploadProtocol] holds the decisions. What is
 * left here is the shape of a resumable upload over a radio that drops: keep the
 * session, keep the offset, and only ever go back to minting when the session is
 * genuinely gone.
 *
 * It reports success only when GCS has acknowledged the whole object. That
 * matters more than it looks: a caller is entitled to treat success as "the
 * durable copy has moved to the bucket", and anything weaker turns into deleting
 * the only recording of an hour of someone's life.
 */
internal class GcsUploader(
    private val transport: UploadTransport,
    private val mintUrl: String,
    private val apiKey: String,
) {

    private companion object {
        /** A signed URL outlives this many session attempts before it is re-minted. */
        const val MAX_RESTARTS = 3

        /** Consecutive transient failures on one session before giving the chunk back. */
        const val MAX_RETRIES = 5

        /** Sentinel: the session died and the caller should mint a fresh URL. */
        const val RESTART = "__restart__"
    }

    sealed interface Result {
        /** The bytes are in the bucket under [objectName]. */
        data class Uploaded(val objectName: String) : Result

        /** Transient. The chunk keeps its audio and is offered again later. */
        data class Retry(val reason: String) : Result

        /** Configuration is wrong; retrying cannot fix it. */
        data class Halt(val reason: String) : Result
    }

    suspend fun upload(file: File, startedAtMs: Long, contentType: String = "audio/wav"): Result {
        if (!file.exists()) return Result.Halt("audio file is gone")
        val total = file.length()
        if (total <= 0L) return Result.Halt("audio file is empty")
        val ext = file.extension.ifBlank { "wav" }

        var restarts = 0
        while (restarts <= MAX_RESTARTS) {
            val mintBody = GcsUploadProtocol.mintRequest(startedAtMs, ext, contentType, total)
            val mintReply = transport.mint(mintUrl, apiKey, mintBody)
            if (mintReply.code == CODE_TRANSPORT_FAILURE) {
                return Result.Retry("could not reach the upload service")
            }
            when (val step = GcsUploadProtocol.afterMint(mintReply.code, mintReply.body)) {
                is Step.Halt -> return Result.Halt(step.reason)
                is Step.Retry -> return Result.Retry(step.reason)
                else -> Unit
            }
            val minted = GcsUploadProtocol.parseMint(mintReply.code, mintReply.body)
                ?: return Result.Retry("mint response was not understood")

            when (val outcome = session(minted, file, total, contentType)) {
                is Result.Uploaded, is Result.Halt -> return outcome
                is Result.Retry -> {
                    // Only a genuinely dead session comes back here; everything
                    // else already returned. Mint again and start over.
                    if (outcome.reason == RESTART) {
                        restarts += 1
                        Log.i(TAG, "session gone; re-minting (${restarts}/$MAX_RESTARTS)")
                        continue
                    }
                    return outcome
                }
            }
        }
        return Result.Retry("gave up after $MAX_RESTARTS session restarts")
    }

    /** Drives one signed URL to completion, resuming within it as needed. */
    private suspend fun session(
        minted: GcsUploadProtocol.Minted,
        file: File,
        total: Long,
        contentType: String,
    ): Result {
        val start = transport.startSession(minted.url, contentType)
        if (start.code == CODE_TRANSPORT_FAILURE) return Result.Retry("could not open the upload session")

        val sessionUri = when (val step = GcsUploadProtocol.afterStart(start.code, start.header)) {
            is Step.Upload -> step.sessionUri
            is Step.Restart -> return Result.Retry(RESTART)
            is Step.Halt -> return Result.Halt(step.reason)
            else -> return Result.Retry("could not open the upload session")
        }

        var offset = 0L
        var retries = 0
        while (true) {
            val reply = transport.putFrom(sessionUri, file, offset, total, contentType)
            if (reply.code == CODE_TRANSPORT_FAILURE) {
                if (++retries > MAX_RETRIES) return Result.Retry("upload kept failing")
                // Do not restart the session on a dropped connection -- that is
                // exactly what resumable upload exists to avoid. Ask GCS what it
                // has and continue from there.
                continue
            }
            when (val step = GcsUploadProtocol.afterUpload(reply.code, reply.header, total)) {
                is Step.Done -> return Result.Uploaded(minted.objectName)
                is Step.Resume -> {
                    // Progress resets the retry budget: a slow link that keeps
                    // moving is not a failing one.
                    if (step.offset > offset) retries = 0
                    offset = step.offset
                }
                is Step.Restart -> return Result.Retry(RESTART)
                is Step.Halt -> return Result.Halt(step.reason)
                is Step.Retry -> if (++retries > MAX_RETRIES) return Result.Retry(step.reason)
                is Step.Upload -> return Result.Retry("unexpected session response mid-upload")
            }
        }
    }
}
