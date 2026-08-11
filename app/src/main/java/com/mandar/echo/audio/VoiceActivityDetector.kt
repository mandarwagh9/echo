package com.mandar.echo.audio

import kotlin.math.sqrt

/**
 * Cheap adaptive energy VAD, run before every backend.
 *
 * This exists for two reasons, both of which matter a lot for 24/7 ambient capture:
 *
 *  - **Cost and battery.** Most of a real day is silence. Transcribing ten minutes
 *    of room tone costs the same as ten minutes of conversation — on device that is
 *    battery, and on the batch backend it is money and mobile data.
 *  - **Hallucination.** Whisper on near-silence reliably emits phantom text
 *    ("Thank you.", subtitle credits, repeated fragments). Skipping silent chunks
 *    removes a whole class of garbage from the day's transcript.
 *
 * There is exactly one entry point, [analyse], and it makes exactly one pass. That
 * is deliberate: this file used to expose two gates that answered the same question
 * differently — a ratio test and a run-length test — and the two disagreeing on the
 * same chunk destroyed transcripts (see the `wordCount` veto in
 * `TranscriptionPipeline`, which is a patch over precisely that). One routine finds
 * the voiced regions; everything else is a summary of them, so they cannot disagree.
 *
 * **The deletion direction has no evidence.** A chunk called silent has its WAV
 * released, so a false negative leaves nothing behind to count, and the field
 * numbers in `docs/stt-health-*` can only ever measure the other direction. Every
 * threshold here is therefore biased toward keeping audio: the cost of a false
 * positive is one wasted upload, and the cost of a false negative is a recording
 * that no longer exists.
 */
object VoiceActivityDetector {

    private const val FRAME_MS = 20
    private const val FRAME_SAMPLES = AudioFormatSpec.SAMPLE_RATE * FRAME_MS / 1000  // 320

    /** Below this RMS everything is treated as silence regardless of noise floor. */
    private const val ABSOLUTE_FLOOR = 0.0025f

    /** A frame counts as speech at this multiple of the estimated noise floor. */
    private const val SNR_FACTOR = 2.5f

    /**
     * Voiced audio a chunk must contain, in milliseconds, before it is worth sending.
     *
     * Absolute, **not** a fraction of the chunk. The old gate wanted 2% of frames,
     * which meant 1.2 s of speech in a 1-minute chunk but 12 s in a 10-minute one —
     * so a lone sentence in a long chunk was deleted as silence, and the bar moved
     * every time chunk length was reconfigured (AUDIT-2026-08-06 §D). Measured in
     * `VadCalibrationTest`: a real utterance in 300 s of silence scored 0.011 and was
     * dropped, while the extractor had correctly found all 4 s of it.
     *
     * 1 s rather than the audit's suggested 2 s. [MIN_RUN_MS] already rejects
     * transients at 400 ms, so this asks for roughly one clear word or two short
     * ones; 2 s would discard a one-word reply, and a discarded chunk is unrecoverable
     * while a needless one costs a single upload.
     */
    const val MIN_VOICED_MS = 1_000L

    /** Speech shorter than this is almost always a door or a keyboard, not a word. */
    private const val MIN_RUN_MS = 400

    /** Voiced runs closer together than this belong to the same utterance. */
    private const val MERGE_GAP_MS = 700

    /** Kept either side of a run so words are not clipped at the boundary. */
    private const val PAD_MS = 300

    /**
     * Below this spread between the loud and quiet ends of a chunk, its quiet end is
     * not silence — it is noise, and the 20th percentile is measuring the noise
     * rather than the floor beneath it.
     *
     * Chosen from the frame-energy table in `VadCalibrationTest`, which is the only
     * calibration data that exists — the chunk that actually caused this bug was
     * never saved. p90/p20 measured 1.1 for room tone, 1.1 for a fan, 1.3 for traffic
     * rumble and 5.1 for a television, against 13.8 / 27.1 / 258 for the three real
     * speech fixtures. 8 sits in the gap. The synthetic noise is not the real thing,
     * so treat this as separating the cases below and not as tuned.
     */
    private const val MIN_DYNAMIC_RANGE = 8f

    /**
     * The voiced parts of a chunk, the map back to where each came from, and the
     * summary the pipeline records.
     *
     * Passing a whole chunk to a recogniser was the original design and it is wrong
     * for ambient audio. A chunk that is 3% speech is 97% invitation to hallucinate:
     * the model was trained on clips that always contain speech, so given ten minutes
     * of room tone it produces confident, fluent, entirely invented text and then
     * loops on it. Cutting the silence out removes the opportunity.
     */
    class Voiced(
        val samples: FloatArray,
        val regions: List<IntRange>,
        /**
         * Voiced duration *before* padding — what [hasSpeech] is decided on.
         *
         * [PAD_MS] adds 300 ms either side of every run, so any single run that
         * clears [MIN_RUN_MS] becomes a ~1 s region on its own. Measuring the floor
         * against padded output would make it a test of whether padding happened.
         */
        val rawVoicedMs: Long,
        private val originalSamples: Int,
    ) {

        /** Compacted-stream start offset of each region. */
        private val offsets = IntArray(regions.size).also { arr ->
            var acc = 0
            regions.forEachIndexed { i, r ->
                arr[i] = acc
                acc += r.last - r.first + 1
            }
        }

        val isEmpty: Boolean get() = samples.isEmpty()

        /** Whether this chunk is worth sending to a backend at all. */
        val hasSpeech: Boolean get() = rawVoicedMs >= MIN_VOICED_MS

        /** Voiced audio actually handed to a backend, padding included. */
        val voicedMs: Long get() = samples.size * 1000L / AudioFormatSpec.SAMPLE_RATE

        /**
         * Fraction of the chunk that survived the gate — recorded per chunk and shown
         * in the day's stats. Formerly the fraction of *frames* over the threshold,
         * which was also the gate; now that the gate is a duration this is free to
         * mean the more useful thing, and it is the only caller [ratioOf] ever had.
         */
        val speechRatio: Float get() = ratioOf(originalSamples)

        /** Fraction of [originalSamples] that survived the gate. */
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

    private val EMPTY = Voiced(FloatArray(0), emptyList(), 0L, 0)

    fun analyse(samples: FloatArray): Voiced {
        if (samples.size < FRAME_SAMPLES * 4) return EMPTY

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

        val utterances = merged.filter { it.last - it.first + 1 >= minRun }
        if (utterances.isEmpty()) return EMPTY

        val rawVoicedMs = utterances.sumOf { it.last - it.first + 1 }.toLong() * FRAME_MS

        val regions = utterances
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
        return Voiced(out, regions, rawVoicedMs, samples.size)
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
     * The level a frame must clear to count as speech.
     *
     * The 20th percentile approximates the noise floor without being skewed by the
     * long silent stretches that dominate an ambient recording — *when there is a
     * floor*. In continuous noise there is not: a television or a crowd fills every
     * frame, the percentile lands inside the noise, and 2.5x it still sits below most
     * of the signal. That is how one chunk shipped 53 s of noise to the decoder and
     * looped it, and how a synthetic television in `VadCalibrationTest` produced 60 s
     * of "voiced" audio out of 60.
     *
     * So the spread decides which end of the distribution to trust. A chunk with no
     * real floor is measured from its median instead, which sits inside the noise and
     * is cleared only by something genuinely louder than it. That distinction matters
     * because noise is bounded and speech is not — measured peak-to-p90 was 1.10 for a
     * television alone against 6.4 for speech over the same television. Re-deriving
     * rather than rejecting is what keeps that speech: the noisy branch is not a
     * verdict on the chunk, only a different question asked of it.
     */
    private fun threshold(rms: FloatArray): Float {
        val sorted = rms.copyOf().also { it.sort() }
        fun percentile(q: Float) = sorted[(sorted.size * q).toInt().coerceIn(0, sorted.size - 1)]

        val p20 = percentile(0.20f)
        val noiseFloor =
            if (p20 > 0f && percentile(0.90f) / p20 < MIN_DYNAMIC_RANGE) percentile(0.50f) else p20
        return maxOf(noiseFloor * SNR_FACTOR, ABSOLUTE_FLOOR)
    }
}
