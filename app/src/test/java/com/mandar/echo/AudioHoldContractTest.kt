package com.mandar.echo

import com.mandar.echo.audio.DiskSpace
import com.mandar.echo.data.AudioHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values a WAV's fate is decided by, and the thresholds the disk valve is
 * denominated in.
 *
 * These are constants, and that is exactly why they are pinned: each one encodes a
 * decision whose cost only shows up months later, on someone else's phone, with no
 * second copy of the audio. Changing one should require reading why it is what it
 * is.
 */
class AudioHoldContractTest {

    private val everyHold = listOf(
        AudioHold.UNLINK_PENDING,
        AudioHold.USER_REQUEST,
        AudioHold.HOLE,
        AudioHold.DEGRADED,
        AudioHold.FAILED,
    )

    @Test
    fun `every hold is distinguishable from every other`() {
        // The column stores the string, and a startup sweep has nothing else to go
        // on: two holds that collide are one decision the sweep cannot resume.
        assertEquals(everyHold.size, everyHold.toSet().size)
        everyHold.forEach { assertTrue("a hold must say something", it.isNotBlank()) }
    }

    @Test
    fun `only holds a better transcript could fix are offered for redo`() {
        assertEquals(listOf(AudioHold.HOLE, AudioHold.DEGRADED), AudioHold.REDOABLE)

        // Kept at the user's request says nothing about the quality of the
        // transcript. Requeueing those would re-transcribe a finished day with the
        // identical engine that already transcribed it.
        assertFalse(AudioHold.USER_REQUEST in AudioHold.REDOABLE)
        // The one value that permits a delete must never also mean "do this again".
        assertFalse(AudioHold.UNLINK_PENDING in AudioHold.REDOABLE)
        // FAILED chunks are behind the retry button, which is a different act with a
        // different query: it resets `attempts`, and redo deliberately does not.
        assertFalse(AudioHold.FAILED in AudioHold.REDOABLE)
    }

    @Test
    fun `the disk valve opens in the band where recording is paused or about to be`() {
        // Hysteresis, or the recorder flaps on and off at one threshold.
        assertTrue(DiskSpace.MIN_FREE_BYTES < DiskSpace.RESUME_FREE_BYTES)

        // Keyed to the *resume* point, not the pause point. Opening the valve at
        // MIN_FREE_BYTES means it only ever opens after recording has already
        // stopped, which is the failure it exists to prevent.
        assertEquals(DiskSpace.RESUME_FREE_BYTES, DiskSpace.PRESSURE_FREE_BYTES)

        // Releasing a hoard smaller than the gap the recorder must climb to resume
        // spends audio and buys nothing.
        assertEquals(
            DiskSpace.RESUME_FREE_BYTES - DiskSpace.MIN_FREE_BYTES,
            DiskSpace.RELIEVABLE_BYTES,
        )
        assertTrue(DiskSpace.RELIEVABLE_BYTES > 0)
    }
}
