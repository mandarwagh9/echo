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
        assertTrue(result.speechRatio > VoiceActivityDetector.MIN_SPEECH_RATIO)
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
