package com.mandar.echo

import com.mandar.echo.stt.CloudTranscriber
import com.mandar.echo.stt.HttpReply
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll cadence, measured rather than asserted about.
 *
 * The transport records the scheduler's virtual time on every call, so the gap
 * between two entries in the call log *is* the delay the machine chose. Every bound
 * here is widened by the ±20% jitter the code applies deliberately (eighteen parked
 * chunks must not resume in lockstep against one server), and by nothing else.
 *
 * This matters on a phone: the server sets `Connection: close`, so every poll costs
 * a full TCP plus TLS handshake and holds the radio in RRC_CONNECTED. The old 3 s
 * cadence produced ~40 round trips per piece and nothing else.
 */
class CloudPollBackoffTest {

    private companion object {
        /** The jitter is `0.8 + random*0.4`, truncated toward zero. */
        const val JITTER_LOW = 0.8
        const val JITTER_HIGH = 1.2
    }

    private fun lowerBound(ms: Double) = (ms * JITTER_LOW).toLong()
    private fun upperBound(ms: Double) = (ms * JITTER_HIGH).toLong() + 1

    /** [upload → poll 1, poll 1 → poll 2, ...] in virtual milliseconds. */
    private fun gapsOf(transport: ScriptedTransport): List<Long> {
        val stamps = listOf(transport.uploads.last().atMs) + transport.polls.map { it.atMs }
        return stamps.zipWithNext { a, b -> b - a }
    }

    @Test
    fun `the first poll waits a fraction of the piece, not a flat minimum`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("ठीक")) }

        // 30 s * 0.35 = 10.5 s, which sits inside [POLL_MIN_MS, POLL_MAX_MS], so the
        // fraction is the only thing that can produce this number. IndicConformer
        // runs at 2-3x realtime on the server's CPU instance: a piece cannot possibly
        // be ready before about a third of its own duration, and asking anyway is
        // ~40 pointless handshakes per piece.
        r.cloud.run(7, voicedSeconds(30), "hi", 1L)

        val first = gapsOf(r.transport).first()
        val expected = 30_000 * CloudTranscriber.FIRST_POLL_FRACTION
        assertTrue(
            "first poll after $first ms, expected about ${expected.toLong()} ms",
            first in lowerBound(expected)..upperBound(expected),
        )
    }

    @Test
    fun `a short piece is still never polled faster than the floor`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("ठीक")) }

        // 5 s * 0.35 = 1.75 s. The floor is what stops a queue of short chunks from
        // pinning the radio awake on a device that runs 24/7.
        r.cloud.run(7, voicedSeconds(5), "hi", 1L)

        val first = gapsOf(r.transport).first()
        val floor = CloudTranscriber.POLL_MIN_MS.toDouble()
        assertTrue(
            "polled after $first ms, below the ${CloudTranscriber.POLL_MIN_MS} ms floor",
            first >= lowerBound(floor),
        )
        assertTrue("polled after $first ms, which is more than the floor plus jitter", first <= upperBound(floor))
    }

    @Test
    fun `a long piece is never left unpolled for longer than the ceiling`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, _ -> HttpReply(200, completedBody("ठीक")) }

        // 50 s * 0.35 = 17.5 s, past POLL_MAX_MS. The cap is not politeness: on a
        // --min-instances=0 service the poll *is* the keep-alive, and an instance
        // with no in-flight request is a scale-in candidate whose in-process job
        // dict does not survive reclamation.
        r.cloud.run(7, voicedSeconds(50), "hi", 1L)

        val first = gapsOf(r.transport).first()
        assertTrue(
            "waited $first ms, past the ${CloudTranscriber.POLL_MAX_MS} ms cap",
            first <= upperBound(CloudTranscriber.POLL_MAX_MS.toDouble()),
        )
    }

    @Test
    fun `backoff grows while the server works and then stops at the cap`() = runTest {
        val r = rig()
        r.transport.onPoll = { _, n ->
            if (n <= 6) HttpReply(200, queuedBody()) else HttpReply(200, completedBody("ठीक"))
        }

        r.cloud.run(7, voicedSeconds(5), "hi", 1L)

        val gaps = gapsOf(r.transport)
        assertTrue("only ${gaps.size} polls", gaps.size >= 5)
        assertTrue(
            "waits did not grow: $gaps",
            gaps.last() > gaps.first(),
        )
        assertTrue(
            "a wait exceeded the cap: $gaps",
            gaps.all { it <= upperBound(CloudTranscriber.POLL_MAX_MS.toDouble()) },
        )
        assertTrue(
            "a wait fell below the floor: $gaps",
            gaps.all { it >= lowerBound(CloudTranscriber.POLL_MIN_MS.toDouble()) },
        )
        // 5000 -> 7500 -> 11250 -> 15000: at the cap by the fourth wait and pinned there.
        assertTrue(
            "the fourth wait should be at the cap: $gaps",
            gaps[3] >= lowerBound(CloudTranscriber.POLL_MAX_MS.toDouble()),
        )
    }
}
