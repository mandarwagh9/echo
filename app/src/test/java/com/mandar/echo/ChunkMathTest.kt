package com.mandar.echo

import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.ChunkMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist for one reason: the user's requirement that *every minute is
 * recorded*. If a buffer straddling a chunk boundary were ever truncated instead
 * of split, audio would vanish silently with no error anywhere.
 */
class ChunkMathTest {

    private val chunk = AudioFormatSpec.CHUNK_SAMPLES        // 9,600,000
    private val buffer = AudioFormatSpec.BUFFER_SAMPLES      // 4096

    @Test
    fun `chunk is exactly ten minutes of 16 kHz audio`() {
        assertEquals(9_600_000L, chunk)
        assertEquals(600_000L, AudioFormatSpec.samplesToMs(chunk))
    }

    @Test
    fun `buffer well inside a chunk is not split`() {
        val splits = ChunkMath.splits(written = 0, chunkSamples = chunk, bufferLen = buffer)
        assertEquals(1, splits.size)
        assertEquals(buffer, splits[0])
    }

    @Test
    fun `buffer straddling the boundary is split not truncated`() {
        // 100 samples of room left, 4096 arriving.
        val splits = ChunkMath.splits(chunk - 100, chunk, buffer)
        assertEquals(2, splits.size)
        assertEquals(100, splits[0])
        assertEquals(buffer - 100, splits[1])
        assertEquals(buffer, splits.sum())
    }

    @Test
    fun `buffer landing exactly on the boundary produces one full piece`() {
        val splits = ChunkMath.splits(chunk - buffer, chunk, buffer)
        assertEquals(1, splits.size)
        assertEquals(buffer, splits[0])
    }

    @Test
    fun `starting at a boundary rotates and consumes the whole buffer`() {
        val splits = ChunkMath.splits(chunk, chunk, buffer)
        assertEquals(buffer, splits.sum())
    }

    @Test
    fun `no sample is ever lost across a full chunk at any offset`() {
        // Walk a whole chunk one buffer at a time and assert the running total is
        // exact at every step — this is the real invariant.
        var written = 0L
        var totalWritten = 0L
        var rotations = 0

        repeat(4000) {
            val splits = ChunkMath.splits(written, chunk, buffer)
            assertEquals("buffer must be fully consumed", buffer, splits.sum())
            for (n in splits) {
                written += n
                totalWritten += n
                if (written >= chunk) {
                    written = 0
                    rotations++
                }
            }
        }

        assertEquals(4000L * buffer, totalWritten)
        assertTrue("should have rotated at least once", rotations >= 1)
    }

    @Test
    fun `works when chunk size is not a multiple of buffer size`() {
        // 1-minute test mode: 960,000 samples, which 4096 does not divide evenly.
        val oddChunk = AudioFormatSpec.SAMPLE_RATE.toLong() * 60
        assertTrue(oddChunk % buffer != 0L)

        var written = 0L
        var total = 0L
        repeat(500) {
            val splits = ChunkMath.splits(written, oddChunk, buffer)
            assertEquals(buffer, splits.sum())
            for (n in splits) {
                written += n
                total += n
                if (written >= oddChunk) written = 0
            }
        }
        assertEquals(500L * buffer, total)
    }

    @Test
    fun `a buffer larger than a whole chunk is split across several`() {
        val splits = ChunkMath.splits(0, chunkSamples = 1000, bufferLen = 3500)
        assertEquals(3500, splits.sum())
        assertEquals(intArrayOf(1000, 1000, 1000, 500).toList(), splits.toList())
    }

    @Test
    fun `empty and negative buffers are no-ops`() {
        assertEquals(0, ChunkMath.splits(0, chunk, 0).size)
        assertEquals(0, ChunkMath.splits(0, chunk, -5).size)
    }
}
