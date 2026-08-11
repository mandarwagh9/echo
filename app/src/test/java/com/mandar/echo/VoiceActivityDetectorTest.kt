package com.mandar.echo

import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.VoiceActivityDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class VoiceActivityDetectorTest {

    private val oneMinute = AudioFormatSpec.SAMPLE_RATE * 60

    @Test
    fun `digital silence has no speech`() {
        val result = VoiceActivityDetector.analyse(FloatArray(oneMinute))
        assertFalse(result.hasSpeech)
    }

    @Test
    fun `quiet room tone is not speech`() {
        val rng = Random(42)
        // Very low-level noise, like an empty room at night.
        val samples = FloatArray(oneMinute) { (rng.nextFloat() - 0.5f) * 0.0008f }
        val result = VoiceActivityDetector.analyse(samples)
        assertFalse("room tone should not trigger transcription", result.hasSpeech)
    }

    @Test
    fun `loud bursts over quiet background register as speech`() {
        val rng = Random(7)
        val samples = FloatArray(oneMinute) { (rng.nextFloat() - 0.5f) * 0.0006f }
        // Insert ~12 s of strong signal, well above the noise floor.
        for (i in 0 until AudioFormatSpec.SAMPLE_RATE * 12) {
            samples[i] = (sin(i * 0.05) * 0.35).toFloat()
        }
        val result = VoiceActivityDetector.analyse(samples)
        assertTrue("sustained loud audio should be treated as speech", result.hasSpeech)
        assertTrue(
            "12 s of signal reported as ${result.rawVoicedMs} ms voiced",
            result.rawVoicedMs > VoiceActivityDetector.MIN_VOICED_MS,
        )
    }

    @Test
    fun `the silence bar does not move with chunk length`() {
        // The gate used to want 2% of frames, so the same utterance passed in a
        // 1-minute chunk and was deleted in a 10-minute one — data loss that arrived
        // purely by reconfiguring chunk length (AUDIT-2026-08-06 §D).
        val rng = Random(3)
        fun utteranceIn(seconds: Int): FloatArray {
            val samples = FloatArray(AudioFormatSpec.SAMPLE_RATE * seconds) {
                (rng.nextFloat() - 0.5f) * 0.0006f
            }
            // 3 s of speech-level signal, placed in the middle.
            val at = samples.size / 2
            for (i in 0 until AudioFormatSpec.SAMPLE_RATE * 3) {
                samples[at + i] = (sin(i * 0.05) * 0.3).toFloat()
            }
            return samples
        }

        listOf(60, 300, 600).forEach { seconds ->
            val r = VoiceActivityDetector.analyse(utteranceIn(seconds))
            assertTrue(
                "3 s of speech in a ${seconds}s chunk was gated out (${r.rawVoicedMs} ms voiced)",
                r.hasSpeech,
            )
        }
    }

    @Test
    fun `a fragment at a chunk boundary is kept, the same fragment in the middle is not`() {
        // Chunking cuts the day at a fixed interval, not at pauses, so a sentence
        // spanning a boundary leaves a ~500 ms tail in one file and a head in the
        // next. Judged by a duration it was cut out of, that tail is under the 1 s
        // bar and its WAV is released -- and at the 1-minute chunk length currently
        // configured, boundaries come round sixty times an hour.
        val rng = Random(5)
        fun chunkWith(fragmentAt: Int): FloatArray {
            val samples = FloatArray(oneMinute) { (rng.nextFloat() - 0.5f) * 0.0006f }
            for (i in 0 until AudioFormatSpec.SAMPLE_RATE / 2) {          // 500 ms
                val at = fragmentAt + i
                if (at in samples.indices) samples[at] = (sin(i * 0.05) * 0.3).toFloat()
            }
            return samples
        }

        val trailing = VoiceActivityDetector.analyse(chunkWith(oneMinute - AudioFormatSpec.SAMPLE_RATE / 2))
        assertTrue(
            "a 500 ms fragment running off the end of the chunk was deleted",
            trailing.hasSpeech,
        )
        assertTrue(trailing.truncatedAtEdge)

        // The same 500 ms with silence either side is not a truncated sentence, it is
        // a short noise. It stays below the bar, and that is the bar doing its job.
        val isolated = VoiceActivityDetector.analyse(chunkWith(oneMinute / 2))
        assertFalse(
            "an isolated 500 ms blip was treated as speech (${isolated.rawVoicedMs} ms)",
            isolated.hasSpeech,
        )
        assertFalse(isolated.truncatedAtEdge)
    }

    @Test
    fun `continuous modulated noise is not voiced end to end`() {
        // A television in the next room fills every frame, so the 20th percentile
        // lands inside the noise and 2.5x it clears most of the signal. One real
        // chunk shipped 53 s of this to the decoder and looped it.
        val rng = Random(13)
        val samples = FloatArray(oneMinute) { i ->
            val envelope = 0.55f + 0.45f * sin(2.0 * Math.PI * 3.5 * i / AudioFormatSpec.SAMPLE_RATE).toFloat()
            (rng.nextFloat() - 0.5f) * 2f * 0.05f * envelope
        }
        val r = VoiceActivityDetector.analyse(samples)
        assertFalse("a television produced ${r.rawVoicedMs} ms of speech", r.hasSpeech)
    }

    @Test
    fun `speech over that same noise survives`() {
        // The other half of the trade, and the one with no evidence in the field: a
        // chunk wrongly called silent has its WAV deleted, so this is the only place
        // the recall direction is checked at all.
        val rng = Random(13)
        val samples = FloatArray(oneMinute) { i ->
            val envelope = 0.55f + 0.45f * sin(2.0 * Math.PI * 3.5 * i / AudioFormatSpec.SAMPLE_RATE).toFloat()
            (rng.nextFloat() - 0.5f) * 2f * 0.05f * envelope
        }
        // Someone talking over it, clearly above the bed.
        val at = AudioFormatSpec.SAMPLE_RATE * 20
        for (i in 0 until AudioFormatSpec.SAMPLE_RATE * 4) {
            samples[at + i] += (sin(i * 0.05) * 0.3).toFloat()
        }
        val r = VoiceActivityDetector.analyse(samples)
        assertTrue("speech over a television was deleted as silence", r.hasSpeech)
    }

    @Test
    fun `very short buffers are rejected rather than guessed at`() {
        assertFalse(VoiceActivityDetector.analyse(FloatArray(100)).hasSpeech)
    }

    @Test
    fun `speech ratio stays within bounds`() {
        val rng = Random(1)
        val samples = FloatArray(oneMinute) { (rng.nextFloat() - 0.5f) * 0.5f }
        val ratio = VoiceActivityDetector.analyse(samples).speechRatio
        assertTrue(ratio in 0f..1f)
    }
}
