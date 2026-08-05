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

    /**
     * Headroom is measured against this percentile of sample magnitude rather
     * than the absolute maximum.
     *
     * Using the true peak looked correct and did nothing: on a real chunk the
     * measured RMS was 0.041 -- half the target -- yet the applied gain was
     * 1.0x, because a single door slam had already put one sample near full
     * scale. Far-field room audio is exactly this shape: a low speech level under
     * occasional loud transients. Ignoring the top 0.5% lets the voice come up,
     * at the cost of flattening a handful of samples that were never speech.
     */
    private const val PEAK_PERCENTILE = 0.995f

    /** Sampling stride for the percentile estimate; exact ordering is not needed. */
    private const val PEAK_STRIDE = 64

    data class Result(val samples: FloatArray, val gain: Float, val inputRms: Float)

    fun normalize(samples: FloatArray): Result {
        if (samples.isEmpty()) return Result(samples, 1f, 0f)

        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        val rms = sqrt(acc / samples.size).toFloat()
        if (rms <= 0f) return Result(samples, 1f, rms)

        val peak = percentilePeak(samples)
        if (peak <= 0f) return Result(samples, 1f, rms)

        val gain = minOf(TARGET_RMS / rms, MAX_GAIN, PEAK_CEILING / peak)

        // Already at or above the target: leave it alone rather than attenuate,
        // since quiet-but-clean is the problem here, not loudness.
        if (gain <= 1f) return Result(samples, 1f, rms)

        // Anything the percentile ignored is clamped rather than allowed to wrap.
        val out = FloatArray(samples.size) {
            (samples[it] * gain).coerceIn(-1f, 1f)
        }
        return Result(out, gain, rms)
    }

    private fun percentilePeak(samples: FloatArray): Float {
        val n = (samples.size + PEAK_STRIDE - 1) / PEAK_STRIDE
        if (n < 8) return samples.maxOf { if (it < 0) -it else it }
        val mags = FloatArray(n)
        var w = 0
        var i = 0
        while (i < samples.size && w < n) {
            val v = samples[i]
            mags[w++] = if (v < 0) -v else v
            i += PEAK_STRIDE
        }
        mags.sort()
        return mags[((w - 1) * PEAK_PERCENTILE).toInt().coerceIn(0, w - 1)]
    }
}
