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

    /** Speech shorter than this is almost always a door or a keyboard, not a word. */
    private const val MIN_RUN_MS = 400

    /** Voiced runs closer together than this belong to the same utterance. */
    private const val MERGE_GAP_MS = 700

    /** Kept either side of a run so words are not clipped at the boundary. */
    private const val PAD_MS = 300

    data class Result(val speechRatio: Float, val hasSpeech: Boolean)

    fun analyse(samples: FloatArray): Result {
        if (samples.size < FRAME_SAMPLES * 4) return Result(0f, false)
        val rms = frameRms(samples)
        val threshold = threshold(rms)
        val ratio = rms.count { it > threshold }.toFloat() / rms.size
        return Result(ratio, ratio >= MIN_SPEECH_RATIO)
    }

    private fun frameRms(samples: FloatArray): FloatArray {
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
        return rms
    }

    /**
     * 20th percentile approximates the noise floor without being skewed by the
     * long silent stretches that dominate an ambient recording.
     */
    private fun threshold(rms: FloatArray): Float {
        val sorted = rms.copyOf().also { it.sort() }
        val noiseFloor = sorted[(sorted.size * 0.20f).toInt().coerceIn(0, sorted.size - 1)]
        return maxOf(noiseFloor * SNR_FACTOR, ABSOLUTE_FLOOR)
    }

    /**
     * The voiced parts of [samples], concatenated, plus the map back to where each
     * one came from.
     *
     * Passing a whole chunk to whisper was the original design and it is wrong for
     * ambient audio. A chunk that is 3% speech is 97% invitation to hallucinate:
     * the model was trained on clips that always contain speech, so given ten
     * minutes of room tone it produces confident, fluent, entirely invented text
     * and then loops on it. Cutting the silence out removes the opportunity, and
     * as a side effect makes a typical chunk many times cheaper to transcribe.
     */
    fun extractVoiced(samples: FloatArray): Voiced {
        if (samples.size < FRAME_SAMPLES * 4) return Voiced(FloatArray(0), emptyList())

        val rms = frameRms(samples)
        val threshold = threshold(rms)

        val minRun = MIN_RUN_MS / FRAME_MS
        val mergeGap = MERGE_GAP_MS / FRAME_MS
        val pad = PAD_MS / FRAME_MS

        // Contiguous runs of voiced frames, in frame indices.
        val runs = ArrayList<IntRange>()
        var start = -1
        for (f in rms.indices) {
            val voiced = rms[f] > threshold
            if (voiced && start < 0) start = f
            if (!voiced && start >= 0) {
                runs += start until f
                start = -1
            }
        }
        if (start >= 0) runs += start until rms.size

        // Merge across short gaps first, then reject what is still too brief --
        // doing it in this order keeps a normal sentence, whose inter-word pauses
        // would otherwise split it into sub-threshold fragments.
        val merged = ArrayList<IntRange>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.last <= mergeGap) {
                merged[merged.size - 1] = last.first..r.last
            } else {
                merged += r
            }
        }

        val regions = merged
            .filter { it.last - it.first + 1 >= minRun }
            .map { r ->
                val from = ((r.first - pad).coerceAtLeast(0)) * FRAME_SAMPLES
                val to = ((r.last + 1 + pad).coerceAtMost(rms.size)) * FRAME_SAMPLES
                from until to
            }
            // Padding can make neighbours overlap; fold those together so the
            // output stream never repeats a sample.
            .fold(ArrayList<IntRange>()) { acc, r ->
                val last = acc.lastOrNull()
                if (last != null && r.first <= last.last) {
                    acc[acc.size - 1] = last.first until maxOf(last.last + 1, r.last + 1)
                } else {
                    acc += r
                }
                acc
            }

        val total = regions.sumOf { it.last - it.first + 1 }
        val out = FloatArray(total)
        var w = 0
        for (r in regions) {
            val n = r.last - r.first + 1
            System.arraycopy(samples, r.first, out, w, n)
            w += n
        }
        return Voiced(out, regions)
    }

    /**
     * Voiced audio with the bookkeeping needed to put whisper's timestamps back
     * where they belong. Without this, every segment's time would refer to the
     * compacted stream and the day's timeline would be nonsense.
     */
    class Voiced(val samples: FloatArray, val regions: List<IntRange>) {

        /** Compacted-stream start offset of each region. */
        private val offsets = IntArray(regions.size).also { arr ->
            var acc = 0
            regions.forEachIndexed { i, r ->
                arr[i] = acc
                acc += r.last - r.first + 1
            }
        }

        val isEmpty: Boolean get() = samples.isEmpty()

        /** Fraction of the original audio that survived the gate. */
        fun ratioOf(originalSamples: Int): Float =
            if (originalSamples <= 0) 0f else samples.size.toFloat() / originalSamples

        /** Maps a millisecond offset in the compacted stream back to the original. */
        fun originalMs(compactedMs: Long): Long {
            if (regions.isEmpty()) return compactedMs
            val target = (compactedMs * AudioFormatSpec.SAMPLE_RATE / 1000L)
                .coerceIn(0L, (samples.size - 1).coerceAtLeast(0).toLong())

            // Last region whose compacted start is <= target.
            var lo = 0
            var hi = regions.size - 1
            var idx = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (offsets[mid] <= target) { idx = mid; lo = mid + 1 } else { hi = mid - 1 }
            }
            val within = target - offsets[idx]
            return (regions[idx].first + within) * 1000L / AudioFormatSpec.SAMPLE_RATE
        }
    }
}
