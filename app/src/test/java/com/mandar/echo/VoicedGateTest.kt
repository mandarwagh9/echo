package com.mandar.echo

import com.mandar.echo.audio.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * The silence gate and, more importantly, the map back out of it.
 *
 * Cutting silence out before whisper is what stops it hallucinating over room
 * tone, but it means every timestamp whisper returns refers to a compacted
 * stream that does not exist in real time. If [VoiceActivityDetector.Voiced.originalMs]
 * is wrong, transcripts land at the wrong time of day and the whole timeline --
 * and the daily summary built on it -- is quietly nonsense. That failure is
 * invisible in a transcript, so it is pinned down here.
 */
class VoicedGateTest {

    private val rate = 16_000

    private fun silence(seconds: Double) = FloatArray((rate * seconds).toInt())

    private fun tone(seconds: Double, amplitude: Float = 0.3f): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> amplitude * sin(2.0 * Math.PI * 220.0 * i / rate).toFloat() }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var w = 0
        for (p in parts) { System.arraycopy(p, 0, out, w, p.size); w += p.size }
        return out
    }

    @Test
    fun pureSilenceYieldsNothing() {
        val v = VoiceActivityDetector.extractVoiced(silence(10.0))
        assertTrue("silence produced ${v.samples.size} samples", v.isEmpty)
        assertTrue(v.regions.isEmpty())
    }

    @Test
    fun aSingleUtteranceIsIsolatedWithPadding() {
        // 3 s silence, 2 s tone, 3 s silence
        val audio = concat(silence(3.0), tone(2.0), silence(3.0))
        val v = VoiceActivityDetector.extractVoiced(audio)

        assertEquals(1, v.regions.size)
        val r = v.regions[0]

        // 300 ms of padding either side, within a frame's tolerance.
        assertTrue("region starts at ${r.first / rate.toDouble()} s", r.first <= 3 * rate - 4_000)
        assertTrue(r.first >= 3 * rate - 6_000)
        assertTrue("region ends at ${r.last / rate.toDouble()} s", r.last >= 5 * rate + 4_000)

        // Far smaller than the input: that reduction is the whole point.
        assertTrue("kept ${v.samples.size} of ${audio.size}", v.samples.size < audio.size / 2)
    }

    @Test
    fun twoUtterancesSurviveAsSeparateRegions() {
        // tone at 3-5 s and at 8-9 s, gap far wider than MERGE_GAP_MS
        val audio = concat(silence(3.0), tone(2.0), silence(3.0), tone(1.0), silence(1.0))
        val v = VoiceActivityDetector.extractVoiced(audio)
        assertEquals("regions: ${v.regions.map { it.first / rate.toDouble() }}", 2, v.regions.size)
    }

    @Test
    fun shortTransientsAreRejected() {
        // 80 ms click -- a door, a keyboard, not a word.
        val audio = concat(silence(3.0), tone(0.08), silence(3.0))
        val v = VoiceActivityDetector.extractVoiced(audio)
        assertTrue("a click became ${v.regions.size} region(s)", v.regions.isEmpty())
    }

    @Test
    fun wordsSeparatedByNormalPausesStayOneUtterance() {
        // Three 600 ms words with 300 ms gaps: one sentence, not three fragments.
        val audio = concat(
            silence(2.0),
            tone(0.6), silence(0.3), tone(0.6), silence(0.3), tone(0.6),
            silence(2.0),
        )
        val v = VoiceActivityDetector.extractVoiced(audio)
        assertEquals(1, v.regions.size)
    }

    @Test
    fun timestampsMapBackToTheOriginalRecording() {
        val audio = concat(silence(3.0), tone(2.0), silence(3.0), tone(1.0), silence(1.0))
        val v = VoiceActivityDetector.extractVoiced(audio)
        assertEquals(2, v.regions.size)

        // The start of the compacted stream is the start of the first utterance.
        val first = v.originalMs(0)
        assertTrue("first voiced audio mapped to $first ms", first in 2600..3000)

        // The first sample of the SECOND region: everything before it in the
        // compacted stream is region one, so its compacted offset is region one's
        // length. It must map past the three-second gap, not into it.
        val regionOneMs = (v.regions[0].last - v.regions[0].first + 1) * 1000L / rate
        val second = v.originalMs(regionOneMs)
        assertTrue(
            "second utterance mapped to $second ms, expected ~7700",
            second in 7400..8100,
        )

        // Monotonic: a later point in the compacted stream is never earlier in
        // the original.
        var prev = -1L
        var t = 0L
        while (t < regionOneMs * 2) {
            val mapped = v.originalMs(t)
            assertTrue("non-monotonic at $t ms: $mapped after $prev", mapped >= prev)
            prev = mapped
            t += 50
        }
    }

    @Test
    fun mappingIsClampedInsideTheAudio() {
        val audio = concat(silence(1.0), tone(1.0), silence(1.0))
        val v = VoiceActivityDetector.extractVoiced(audio)
        // Whisper can round a final timestamp past the end of what it was given.
        val beyond = v.originalMs(60_000)
        assertTrue("mapped $beyond ms, past the 3 s recording", beyond <= 3_000)
    }

    @Test
    fun compactedLengthMatchesTheRegions() {
        val audio = concat(silence(2.0), tone(1.0), silence(2.0), tone(1.5), silence(1.0))
        val v = VoiceActivityDetector.extractVoiced(audio)
        val expected = v.regions.sumOf { it.last - it.first + 1 }
        assertEquals(expected, v.samples.size)
    }

    @Test
    fun regionsAreOrderedAndDoNotOverlap() {
        val audio = concat(
            silence(1.0), tone(0.8), silence(1.2), tone(0.8), silence(1.2), tone(0.8), silence(1.0),
        )
        val v = VoiceActivityDetector.extractVoiced(audio)
        for (i in 1 until v.regions.size) {
            assertTrue(
                "region $i starts at ${v.regions[i].first} before ${v.regions[i - 1].last} ends",
                v.regions[i].first > v.regions[i - 1].last,
            )
        }
    }
}
