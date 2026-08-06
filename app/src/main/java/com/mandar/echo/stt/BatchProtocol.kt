package com.mandar.echo.stt

import org.json.JSONObject

/**
 * The vexyl-stt wire contract, as pure functions over `(statusCode, body)`.
 *
 * This file exists because the live hang was a *parsing* bug that no amount of
 * integration testing had caught: `poll()` used blankness as a readiness test, so
 * a job that completed with an empty transcript — an ordinary success on the
 * server, which returns `""` for audio it hears nothing in — was read as "not
 * ready yet" and polled until a 15-minute ceiling. Nothing failed, nothing
 * retried, nothing progressed.
 *
 * Two rules make that unrepresentable:
 *
 *  1. **HTTP status carries transport meaning; the body's `status` field carries
 *     job meaning.** Never both. That is why polling targets `/batch/status/{id}`
 *     rather than `/batch/result/{id}`: `/result` splits job state across the HTTP
 *     code (202 for queued/processing) *and* the body, giving two sources of truth
 *     for one fact. `/status` answers 200 for every known job and 404 only for an
 *     unknown id, so there is exactly one decision site.
 *  2. **Every outcome is a named case, and each is classified transient or
 *     permanent exactly once — here.** No blankness tests, no guessing at field
 *     names, no nullable standing in for a state.
 *
 * Everything is `internal` and I/O-free so the whole contract is table-testable
 * without a socket. See BatchProtocolTest.
 */

/** The server's closed job enum (vexyl_stt_server.py JobStatus). Anything else is a contract violation. */
private const val STATUS_QUEUED = "queued"
private const val STATUS_PROCESSING = "processing"
private const val STATUS_COMPLETED = "completed"
private const val STATUS_FAILED = "failed"

/**
 * Whether trying again later could plausibly succeed.
 *
 * The distinction is load-bearing rather than cosmetic: a permanent outcome
 * eventually routes a chunk to on-device Whisper, which is measured at 0.23 word
 * recall on Hindi and 0.00 on Marathi against IndicConformer's 1.00. Calling
 * something permanent when it is really a bad hotel wifi is how a day of Marathi
 * gets silently destroyed, so anything ambiguous is transient by default.
 */
internal sealed interface Transientable {
    val transient: Boolean
}

internal sealed interface SubmitOutcome : Transientable {

    /** 201 Created with a job_id. `audioSeconds` is the server's own measure, used to time the first poll. */
    data class Accepted(val jobId: String, val audioSeconds: Double) : SubmitOutcome {
        override val transient = false
    }

    /** 400/413: the server understood the request and refused it. Identical bytes will be refused again. */
    data class Rejected(val reason: String) : SubmitOutcome {
        override val transient = false
    }

    /**
     * 403: the API key is wrong or stale.
     *
     * Never a per-chunk failure. `/health` is served before the key check, so the
     * wake probe stays green while every upload is refused — a misconfiguration
     * that, treated as a chunk failure, would chew through a whole backlog at
     * Whisper quality. The caller halts the cloud path instead and leaves the
     * audio alone; doing nothing is strictly correct here.
     */
    data class Unauthorized(val reason: String) : SubmitOutcome {
        override val transient = false
    }

    /**
     * 3xx or 404: the URL is wrong, not the request.
     *
     * An `http://` URL makes Cloud Run answer with a redirect, and
     * HttpURLConnection will not replay a POST body across one, so the client sees
     * the 3xx directly. A trailing slash yields `//batch/transcribe`, which the
     * server answers 404 "Unknown endpoint". Both are one-character configuration
     * mistakes that must halt the cloud path, not fail chunks forever.
     */
    data class Misconfigured(val reason: String) : SubmitOutcome {
        override val transient = false
    }

    /** 429: too many pending jobs right now. */
    data class Overloaded(val reason: String) : SubmitOutcome {
        override val transient = true
    }

    /**
     * 5xx, a timeout, a dead socket, or a 2xx body that is not JSON.
     *
     * That last case is the captive portal: a hotel login page answering 200 with
     * HTML. A body we cannot parse from a code that claims success is far more
     * likely to be an interceptor than a server contract change, so it is
     * transient — a portal must not be able to permanently downgrade a day.
     */
    data class Unreachable(val reason: String) : SubmitOutcome {
        override val transient = true
    }

    /**
     * A 2xx JSON body from something that answers like our server but does not
     * carry a job_id. A genuine contract violation, so it must surface rather than
     * spin.
     */
    data class Malformed(val body: String) : SubmitOutcome {
        override val transient = false
    }
}

internal sealed interface PollOutcome : Transientable {

    /** The server holds the job and the worker has not started it. This is progress, not a failure. */
    data object Queued : PollOutcome {
        override val transient = true
    }

    /** The worker is decoding it. Progress. */
    data object Processing : PollOutcome {
        override val transient = true
    }

    /**
     * Terminal success.
     *
     * [transcript] is a non-null `String` that MAY be `""`, and that is normal: the
     * server marks a job COMPLETED with an empty transcript whenever the model
     * produced no words. It is read by key *presence*, never by content — the one
     * line that makes the original hang unrepresentable.
     */
    data class Completed(val transcript: String) : PollOutcome {
        override val transient = false
    }

    /** Terminal failure for this job. The reason comes from `error_message`, the key the server actually writes. */
    data class Failed(val reason: String) : PollOutcome {
        override val transient = false
    }

    /**
     * 404: the server does not know this job id.
     *
     * Expected rather than exceptional on Cloud Run. `_batch_jobs` is an in-process
     * dict with no persistence, `--min-instances=0` permits reclamation, session
     * affinity is cookie-based and HttpURLConnection sends no cookies, and jobs are
     * evicted an hour after completion. The caller resubmits.
     */
    data object JobGone : PollOutcome {
        override val transient = true
    }

    /** 403 while polling: same treatment as on submit — halt, do not fail the chunk. */
    data class Unauthorized(val reason: String) : PollOutcome {
        override val transient = false
    }

    /** 5xx, a timeout, a dead socket, or a 2xx body that is not JSON (captive portal). */
    data class Unreachable(val reason: String) : PollOutcome {
        override val transient = true
    }

    /**
     * 200 with parseable JSON carrying a `status` we do not recognise, or a
     * completed job with no `transcript` key at all.
     *
     * Permanent on purpose: unlike an unparseable body, this came from something
     * speaking our protocol badly, and silently polling on would be exactly the
     * bug this file replaces.
     */
    data class Malformed(val body: String) : PollOutcome {
        override val transient = false
    }
}

/**
 * Whether a `/health` response proves we are talking to vexyl-stt rather than to
 * whatever intercepted the connection.
 */
internal sealed interface HealthOutcome : Transientable {
    data class Awake(val model: String) : HealthOutcome {
        override val transient = false
    }

    /** Reached something, but not our server: a captive portal, a proxy error page, the wrong host. */
    data class NotOurServer(val reason: String) : HealthOutcome {
        override val transient = true
    }

    data class Unreachable(val reason: String) : HealthOutcome {
        override val transient = true
    }
}

/** POST /batch/transcribe. [code] is the HTTP status, or a negative sentinel for a transport failure. */
internal fun interpretSubmit(code: Int, body: String): SubmitOutcome = when {
    code == CODE_TRANSPORT_FAILURE -> SubmitOutcome.Unreachable(body.ifBlank { "connection failed" })

    // 201 is what the server documents and sends. Accepting the whole 2xx range
    // is deliberate: pinning this to == 200 would break every upload, so the test
    // suite pins 201 explicitly to stop anyone "tightening" it.
    code in 200..299 -> {
        val json = parseOrNull(body)
        when {
            json == null -> SubmitOutcome.Unreachable(notOurServer(code, body))
            else -> {
                val jobId = json.optString("job_id")
                if (jobId.isBlank()) SubmitOutcome.Malformed(body.take(200))
                else SubmitOutcome.Accepted(jobId, json.optDouble("audio_duration", 0.0))
            }
        }
    }

    // HttpURLConnection will not replay a POST body across a redirect, so a 3xx
    // arrives here rather than being followed.
    code in 300..399 -> SubmitOutcome.Misconfigured(
        "server URL redirects ($code) — it probably needs https://"
    )

    code == 400 || code == 413 -> SubmitOutcome.Rejected(errorOf(body) ?: "server refused the audio ($code)")
    code == 403 -> SubmitOutcome.Unauthorized(errorOf(body) ?: "server rejected the API key")
    code == 404 -> SubmitOutcome.Misconfigured("no transcription endpoint at this URL (404)")
    code == 429 -> SubmitOutcome.Overloaded(errorOf(body) ?: "server is busy")
    code in 500..599 -> SubmitOutcome.Unreachable("server error $code")
    else -> SubmitOutcome.Unreachable("unexpected status $code")
}

/** GET /batch/status/{id}. */
internal fun interpretPoll(code: Int, body: String): PollOutcome = when {
    code == CODE_TRANSPORT_FAILURE -> PollOutcome.Unreachable(body.ifBlank { "connection failed" })

    code == 404 -> PollOutcome.JobGone

    // 403 on this path is served by websockets' connection.respond(), so the body
    // is plain text, not JSON. Classified by code, never by body shape.
    code == 403 -> PollOutcome.Unauthorized(body.trim().take(120).ifBlank { "server rejected the API key" })

    code in 200..299 -> {
        val json = parseOrNull(body)
        if (json == null) {
            PollOutcome.Unreachable(notOurServer(code, body))
        } else when (json.optString("status")) {
            STATUS_QUEUED -> PollOutcome.Queued
            STATUS_PROCESSING -> PollOutcome.Processing
            // Presence of the key, never its content. `""` is a first-class,
            // expected value: this line is the live bug's grave.
            STATUS_COMPLETED ->
                if (json.has("transcript")) PollOutcome.Completed(json.optString("transcript"))
                else PollOutcome.Malformed(body.take(200))
            STATUS_FAILED -> PollOutcome.Failed(
                json.optString("error_message").ifBlank { "server reported failure with no reason" }
            )
            else -> PollOutcome.Malformed(body.take(200))
        }
    }

    code in 300..399 -> PollOutcome.Unreachable("server URL redirects ($code)")
    code in 500..599 -> PollOutcome.Unreachable("server error $code")
    else -> PollOutcome.Unreachable("unexpected status $code")
}

/**
 * GET /health.
 *
 * The body is checked, not the status code. `/health` is unauthenticated and
 * served before the API-key check, so a green probe proves reachability and
 * nothing else — and a captive portal answers 200 to everything. The server's
 * fingerprint is `status: "ok"` plus a `model` field; the model *name* is not
 * pinned, because a legitimate redeploy may change it and "the user upgraded
 * their server" must not present as "unreachable".
 */
internal fun interpretHealth(code: Int, body: String): HealthOutcome = when {
    code == CODE_TRANSPORT_FAILURE -> HealthOutcome.Unreachable(body.ifBlank { "connection failed" })
    code !in 200..299 -> HealthOutcome.Unreachable("health returned $code")
    else -> {
        val json = parseOrNull(body)
        val model = json?.optString("model").orEmpty()
        when {
            json == null -> HealthOutcome.NotOurServer(notOurServer(code, body))
            json.optString("status") != "ok" -> HealthOutcome.NotOurServer(notOurServer(code, body))
            model.isBlank() -> HealthOutcome.NotOurServer(notOurServer(code, body))
            else -> HealthOutcome.Awake(model)
        }
    }
}

/** Not a real HTTP status: what the transport reports when it never got one. */
internal const val CODE_TRANSPORT_FAILURE = -1

private fun parseOrNull(body: String): JSONObject? =
    runCatching { JSONObject(body) }.getOrNull()

private fun errorOf(body: String): String? =
    parseOrNull(body)?.optString("error")?.takeIf { it.isNotBlank() }

private fun notOurServer(code: Int, body: String): String =
    "got $code from something that is not the transcription server " +
        "(${body.trim().replace(Regex("\\s+"), " ").take(60)})"
