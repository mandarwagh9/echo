package com.mandar.echo

import com.mandar.echo.audio.AudioGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gain has one hard requirement: it must never clip. A normaliser that pushes
 * samples past full scale trades a quiet recording for a distorted one, which is
 * strictly worse for the recogniser and impossible to notice in a transcript.
 */
class AudioGainTest {

    private fun tone(amplitude: Float, n: Int = 16_000) =
        FloatArray(n) { i -> amplitude * sin(2.0 * Math.PI * 220.0 * i / 16_000).toFloat() }

    private fun rms(s: FloatArray): Float {
        if (s.isEmpty()) return 0f
        var acc = 0.0
        for (v in s) acc += v.toDouble() * v
        return sqrt(acc / s.size).toFloat()
    }

    @Test
    fun quietSpeechIsBroughtUp() {
        val quiet = tone(0.01f)
        val r = AudioGain.normalize(quiet)
        assertTrue("gain was ${r.gain}", r.gain > 1f)
        assertTrue("output rms ${rms(r.samples)}", rms(r.samples) > rms(quiet) * 2)
    }

    @Test
    fun outputNeverClips() {
        for (amp in listOf(0.0005f, 0.005f, 0.05f, 0.5f, 0.95f)) {
            val r = AudioGain.normalize(tone(amp))
            val peak = r.samples.maxOf { if (it < 0) -it else it }
            assertTrue("amplitude $amp produced peak $peak", peak <= 1.0f)
        }
    }

    @Test
    fun veryQuietAudioIsNotAmplifiedWithoutLimit() {
        // Near-silence must not be multiplied until the noise floor becomes a roar.
        val r = AudioGain.normalize(tone(0.00001f))
        assertTrue("gain was ${r.gain}", r.gain <= 12f)
    }

    @Test
    fun loudAudioIsLeftAlone() {
        val loud = tone(0.5f)
        val r = AudioGain.normalize(loud)
        assertEquals(1f, r.gain, 1e-6f)
        assertTrue(r.samples.contentEquals(loud))
    }

    @Test
    fun silenceAndEmptyInputAreSafe() {
        val silent = AudioGain.normalize(FloatArray(16_000))
        assertEquals(1f, silent.gain, 1e-6f)
        assertEquals(0f, silent.inputRms, 1e-6f)

        val empty = AudioGain.normalize(FloatArray(0))
        assertEquals(1f, empty.gain, 1e-6f)
    }

    /**
     * The case that made the normaliser useless in production: quiet speech under
     * one loud transient. Measured on a real chunk as rms=0.041 with gain=1.0x,
     * because a single near-full-scale sample consumed all the headroom.
     */
    @Test
    fun oneLoudTransientDoesNotBlockTheGain() {
        val audio = tone(0.02f, n = 160_000)   // 10 s of quiet speech
        audio[80_000] = 0.99f                  // a door slam
        audio[80_001] = -0.99f

        val r = AudioGain.normalize(audio)
        assertTrue("a single transient still blocked the gain (${r.gain}x)", r.gain > 2f)
        assertTrue("output escaped full scale", r.samples.all { it >= -1f && it <= 1f })

        // The quiet part is what had to come up.
        assertTrue("speech level barely moved: ${rms(r.samples)}", rms(r.samples) > 0.05f)
    }

    @Test
    fun reportedRmsDescribesTheInputNotTheOutput() {
        val quiet = tone(0.01f)
        val r = AudioGain.normalize(quiet)
        assertEquals(rms(quiet), r.inputRms, 1e-4f)
    }
}
