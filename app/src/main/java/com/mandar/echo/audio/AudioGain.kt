package com.mandar.echo.audio

import kotlin.math.sqrt

/**
 * Level normalisation applied just before transcription.
 *
 * Whisper was trained on audio at ordinary speech level. A phone capturing a room
 * from a pocket or a desk produces something far quieter — often an order of
 * magnitude below what the model expects — and a quiet signal pushes the decoder
 * toward its priors, which is exactly the condition that produces confident
 * nonsense. Bringing the level up costs one pass over the samples.
 *
 * This runs *after* the silence gate on purpose. Normalising a whole ambient
 * chunk would measure mostly room tone and amplify the noise along with it; run
 * on voiced regions only, the measurement is of speech.
 */
object AudioGain {

    /** Target RMS. Roughly the level of clearly-recorded speech. */
    private const val TARGET_RMS = 0.08f

    /** Beyond this, we would be amplifying the noise floor, not the voice. */
    private const val MAX_GAIN = 12f

    /** Headroom kept below full scale so nothing clips. */
    private const val PEAK_CEILING = 0.97f

    data class Result(val samples: FloatArray, val gain: Float, val inputRms: Float)

    fun normalize(samples: FloatArray): Result {
        if (samples.isEmpty()) return Result(samples, 1f, 0f)

        var acc = 0.0
        var peak = 0f
        for (s in samples) {
            acc += s.toDouble() * s
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
        }
        val rms = sqrt(acc / samples.size).toFloat()
        if (rms <= 0f || peak <= 0f) return Result(samples, 1f, rms)

        // Never boost past the point of clipping, whatever the RMS suggests.
        val wanted = TARGET_RMS / rms
        val gain = minOf(wanted, MAX_GAIN, PEAK_CEILING / peak)

        // Already at or above the target: leave it alone rather than attenuate,
        // since quiet-but-clean is the problem here, not loudness.
        if (gain <= 1f) return Result(samples, 1f, rms)

        val out = FloatArray(samples.size) { samples[it] * gain }
        return Result(out, gain, rms)
    }
}
