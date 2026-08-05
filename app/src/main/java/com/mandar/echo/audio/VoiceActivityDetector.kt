package com.mandar.echo.audio

import kotlin.math.sqrt

/**
 * Cheap adaptive energy VAD, run before whisper.
 *
 * This exists for two reasons, both of which matter a lot for 24/7 ambient capture:
 *
 *  - **Battery/throughput.** Most of a real day is silence. Running whisper over
 *    ten minutes of room tone costs the same as ten minutes of conversation.
 *  - **Hallucination.** Whisper on near-silence reliably emits phantom text
 *    ("Thank you.", subtitle credits, repeated fragments). Skipping silent chunks
 *    removes a whole class of garbage from the day's transcript.
 *
 * The threshold is derived from the chunk's own noise floor rather than a fixed
 * constant, so it adapts to a quiet bedroom and a noisy street alike.
 */
object VoiceActivityDetector {

    private const val FRAME_MS = 20
    private const val FRAME_SAMPLES = AudioFormatSpec.SAMPLE_RATE * FRAME_MS / 1000  // 320

    /** Below this RMS everything is treated as silence regardless of noise floor. */
    private const val ABSOLUTE_FLOOR = 0.0025f

    /** A frame counts as speech at this multiple of the estimated noise floor. */
    private const val SNR_FACTOR = 2.5f

    /** Chunks with less speech than this are not worth transcribing. */
    const val MIN_SPEECH_RATIO = 0.02f

    data class Result(val speechRatio: Float, val hasSpeech: Boolean)

    fun analyse(samples: FloatArray): Result {
        if (samples.size < FRAME_SAMPLES * 4) return Result(0f, false)

        val frameCount = samples.size / FRAME_SAMPLES
        val rms = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var acc = 0.0
            val base = f * FRAME_SAMPLES
            for (i in 0 until FRAME_SAMPLES) {
                val s = samples[base + i].toDouble()
                acc += s * s
            }
            rms[f] = sqrt(acc / FRAME_SAMPLES).toFloat()
        }

        // 20th percentile approximates the noise floor without being skewed by
        // the long silent stretches that dominate an ambient recording.
        val sorted = rms.copyOf().also { it.sort() }
        val noiseFloor = sorted[(sorted.size * 0.20f).toInt().coerceIn(0, sorted.size - 1)]
        val threshold = maxOf(noiseFloor * SNR_FACTOR, ABSOLUTE_FLOOR)

        val speechFrames = rms.count { it > threshold }
        val ratio = speechFrames.toFloat() / frameCount
        return Result(ratio, ratio >= MIN_SPEECH_RATIO)
    }
}
