package com.mandar.echo

import android.content.Context
import android.content.ContextWrapper
import com.mandar.echo.audio.DiskSpace
import com.mandar.echo.stt.CloudGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one answer to "is the server worth trying right now", shared by every chunk.
 *
 * Everything here is the timer half of the gate: under
 * `unitTests.isReturnDefaultValues = true` the mockable android.jar hands back a
 * null ConnectivityManager, so the gate takes its own no-network fallback path.
 * That makes the radio-dependent arms of [CloudGate.blockedReason] — wi-fi-only on
 * a metered link, and the unreachable backoff — unreachable from a JVM test; they
 * are called out in the report rather than faked into a green assertion.
 *
 * What is left is what actually caused a lost day: a halt outranking everything,
 * and a backoff that has to grow, cap, and genuinely reset.
 */
class CloudGateTest {

    /**
     * The minimum Context [CloudGate] needs. Only `getApplicationContext` is
     * overridden; `getSystemService` is final on the real Context and returns a
     * default here, which is the whole point.
     */
    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun gate() = CloudGate(StubContext())

    @Test
    fun `a halt outranks every other reason and persists until it is cleared`() {
        val gate = gate()
        assertNull(gate.haltReason)
        val networkReason = gate.blockedReason(now = 0, wifiOnly = false)
        assertNotNull("the gate always has an answer when it is blocked", networkReason)

        gate.halt("server rejected the API key")

        assertEquals("server rejected the API key", gate.haltReason)
        // Bad credentials are configuration, not weather. Nothing about time or the
        // radio may make this reason go away, because the alternative is grinding a
        // backlog of Marathi through an engine measured at 0.00 word recall.
        assertEquals("server rejected the API key", gate.blockedReason(now = 0, wifiOnly = false))
        assertEquals("server rejected the API key", gate.blockedReason(now = 9_999_999, wifiOnly = false))
        assertEquals("server rejected the API key", gate.blockedReason(now = 9_999_999, wifiOnly = true))

        gate.clearHalt()

        assertNull(gate.haltReason)
        assertEquals(networkReason, gate.blockedReason(now = 0, wifiOnly = false))
    }

    @Test
    fun `unreachable backoff grows with each consecutive failure and is capped`() {
        val gate = gate()

        val retryAt = (1..8).map {
            gate.noteUnreachable(now = 0, reason = "probe $it")
            gate.retryServerAt
        }

        // One minute per consecutive failure, capped at five. A dead network then
        // costs one probe per interval instead of one wake budget per queued chunk.
        assertEquals(
            listOf(60_000L, 120_000L, 180_000L, 240_000L, 300_000L, 300_000L, 300_000L, 300_000L),
            retryAt,
        )
    }

    @Test
    fun `a reachable probe resets the escalation, not just the timer`() {
        val gate = gate()
        repeat(5) { gate.noteUnreachable(now = 0, reason = "down") }
        assertEquals(300_000L, gate.retryServerAt)

        gate.noteReachable()

        assertEquals(0L, gate.retryServerAt)
        assertEquals(0L, gate.unreachableSince)
        // The next outage has to start at one minute, not at six. A dropped reset of
        // the counter is invisible for months and then makes the first probe after a
        // single blip wait five minutes.
        gate.noteUnreachable(now = 1_000, reason = "down again")
        assertEquals(61_000L, gate.retryServerAt)
    }

    @Test
    fun `unreachableSince marks the start of the outage, not the latest probe`() {
        val gate = gate()

        gate.noteUnreachable(now = 1_000, reason = "first")
        gate.noteUnreachable(now = 2_000, reason = "second")

        // This is what the UI counts "unreachable for N minutes" from, so a plain
        // set() here would keep resetting the outage to zero at every probe.
        assertEquals(1_000L, gate.unreachableSince)
        assertEquals(122_000L, gate.retryServerAt)
    }

    @Test
    fun `with no validated network the cloud path is blocked rather than attempted`() {
        val gate = gate()

        // The stub reports no network at all, which is the same shape as a phone in
        // flight mode. The reason string is what reaches the notification.
        assertTrue(gate.offline())
        assertEquals("waiting for a network", gate.blockedReason(now = 0, wifiOnly = false))
    }

    @Test
    fun `a volume that cannot be measured never reads as full`() {
        // filesDir is null under the stubs, so the StatFs call throws. Both callers
        // act on a *low* reading — one stops recording, the other stops keeping
        // audio — so a failed measurement must not be able to trigger either.
        assertEquals(Long.MAX_VALUE, DiskSpace.freeBytes(StubContext()))
    }
}
