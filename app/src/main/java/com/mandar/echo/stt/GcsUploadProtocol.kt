package com.mandar.echo.stt

import org.json.JSONObject

/**
 * The wire contract for handing a chunk to the batch pipeline, as pure functions
 * over `(status, headers, body)`.
 *
 * Same shape as [BatchProtocol] and for the same reason: the decisions worth
 * getting right here are about status codes and headers, not about sockets, and
 * a decision table can be tested exhaustively while a transport cannot.
 *
 * The protocol is GCS resumable upload, which is three exchanges:
 *
 *  1. `POST /v1/upload-url` to Echo's minting service, which authorises and
 *     names the object. Returns a signed URL.
 *  2. `POST <signed url>` with `x-goog-resumable: start` opens a session. The
 *     session URI comes back in `Location`.
 *  3. `PUT <session uri>` with the bytes.
 *
 * Step 3 is the one that matters on a phone. An interrupted upload does not
 * start again: querying the session returns `308` with a `Range` header saying
 * how much GCS already holds, and the client continues from there. A 19 MB
 * chunk over a handover-prone radio would otherwise never finish.
 */
object GcsUploadProtocol {

    /** What the caller should do next, having seen one response. */
    sealed interface Step {
        /** Session opened; upload the bytes here. */
        data class Upload(val sessionUri: String) : Step

        /** The object is durably in the bucket. The local WAV is now redundant. */
        data object Done : Step

        /** GCS holds this many bytes already; send the rest from this offset. */
        data class Resume(val offset: Long) : Step

        /** Transient. Wait and try the same step again. */
        data class Retry(val reason: String) : Step

        /** The session is gone; go back to step 1 and mint a fresh URL. */
        data class Restart(val reason: String) : Step

        /** Configuration or credentials are wrong. Stop and surface it. */
        data class Halt(val reason: String) : Step
    }

    data class Minted(val url: String, val objectName: String, val bucket: String)

    /**
     * Parse the minting service's answer.
     *
     * Returns null rather than throwing on anything unexpected: a mint that
     * cannot be understood is a mint that failed, and the caller already has a
     * path for that.
     */
    fun parseMint(status: Int, body: String?): Minted? {
        if (status != 200 || body.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            val url = json.optString("url")
            val name = json.optString("object")
            if (url.isBlank() || name.isBlank()) return null
            Minted(url, name, json.optString("bucket"))
        }.getOrNull()
    }

    /**
     * Response to the mint request itself.
     *
     * 401/403 is the shared key being wrong, which no amount of retrying fixes
     * and which must not be allowed to look like a network problem -- that
     * confusion is what let a bad key masquerade as an outage in the path this
     * replaces.
     */
    fun afterMint(status: Int, body: String?): Step = when {
        status == 200 && parseMint(status, body) != null -> Step.Done
        status == 200 -> Step.Retry("mint response was not understood")
        status == 401 || status == 403 -> Step.Halt("upload key rejected ($status)")
        status == 413 -> Step.Halt("server refused the chunk size")
        status in 400..499 -> Step.Halt("upload service refused the request ($status)")
        else -> Step.Retry("mint failed with $status")
    }

    /**
     * Response to opening the session. GCS answers 201 with the session URI in
     * `Location`.
     */
    fun afterStart(status: Int, location: String?): Step = when {
        status == 201 && !location.isNullOrBlank() -> Step.Upload(location)
        status == 201 -> Step.Retry("session opened without a Location header")
        // A signed URL has an expiry. Past it the answer is 400/403, and the
        // fix is a new URL rather than another attempt at this one.
        status == 400 || status == 403 -> Step.Restart("signed URL expired or invalid ($status)")
        status in 400..499 -> Step.Halt("GCS refused the session ($status)")
        else -> Step.Retry("session start failed with $status")
    }

    /**
     * Response to sending bytes.
     *
     * 308 is not an error here: it is GCS saying "still open, I have this much".
     * Treating it as one is the classic way to turn a resumable upload into an
     * infinite loop of full re-uploads.
     */
    fun afterUpload(status: Int, range: String?, total: Long): Step = when {
        status == 200 || status == 201 -> Step.Done
        status == 308 -> Step.Resume(nextOffset(range))
        // The session is single-use and expires; 404 means it is gone entirely.
        status == 404 -> Step.Restart("upload session no longer exists")
        status == 410 -> Step.Restart("upload session was cancelled")
        status == 429 -> Step.Retry("rate limited")
        status in 400..499 -> Step.Halt("GCS refused the upload ($status)")
        else -> Step.Retry("upload failed with $status")
    }

    /**
     * Where to continue from, given GCS's `Range: bytes=0-<lastByteReceived>`.
     *
     * The header is inclusive of the last byte held, so the next offset is one
     * past it. An absent header means nothing was stored, which is offset 0 --
     * GCS omits it entirely rather than sending `bytes=0--1`.
     */
    fun nextOffset(range: String?): Long {
        val value = range?.substringAfter("bytes=", "")?.substringAfter('-', "") ?: ""
        return value.trim().toLongOrNull()?.plus(1) ?: 0L
    }

    /** The request body for the minting call. */
    fun mintRequest(startedAtMs: Long, ext: String, contentType: String, sizeBytes: Long): String =
        JSONObject()
            .put("startedAtMs", startedAtMs)
            .put("ext", ext)
            .put("contentType", contentType)
            .put("sizeBytes", sizeBytes)
            .toString()
}
