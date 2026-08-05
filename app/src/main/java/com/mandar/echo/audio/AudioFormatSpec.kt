package com.mandar.echo.audio

/** Whisper's native input format. Recording here means zero resampling later. */
object AudioFormatSpec {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** 10 minutes, expressed in samples so chunk rotation can be sample-exact. */
    const val CHUNK_SECONDS = 600
    const val CHUNK_SAMPLES = SAMPLE_RATE.toLong() * CHUNK_SECONDS  // 9,600,000

    /** Samples per handoff buffer between the reader and writer threads (~256 ms). */
    const val BUFFER_SAMPLES = 4096

    /** ~16 s of slack absorbs a disk stall without ever blocking the mic read. */
    const val QUEUE_CAPACITY = 64

    fun samplesToMs(samples: Long): Long = samples * 1000L / SAMPLE_RATE
    fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000L
}

/**
 * The arithmetic behind sample-exact chunk rotation.
 *
 * Extracted from the writer loop so the one guarantee the app actually promises —
 * that no captured sample is ever dropped at a chunk boundary — is covered by
 * tests rather than by inspection.
 */
object ChunkMath {

    /**
     * How to divide a buffer of [bufferLen] samples across chunk boundaries, given
     * [written] samples already in the current chunk.
     *
     * The returned sizes always sum to exactly [bufferLen]: a buffer straddling a
     * boundary is split, never truncated.
     */
    fun splits(written: Long, chunkSamples: Long, bufferLen: Int): IntArray {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        if (bufferLen <= 0) return IntArray(0)

        val out = ArrayList<Int>(2)
        var inChunk = written.coerceIn(0, chunkSamples)
        var remaining = bufferLen

        while (remaining > 0) {
            if (inChunk >= chunkSamples) inChunk = 0        // rotation happened
            val space = chunkSamples - inChunk
            val take = minOf(remaining.toLong(), space).toInt()
            out.add(take)
            inChunk += take
            remaining -= take
        }
        return out.toIntArray()
    }
}
