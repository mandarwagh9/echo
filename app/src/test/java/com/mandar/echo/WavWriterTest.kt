package com.mandar.echo

import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

class WavWriterTest {

    private fun tempFile(name: String): File =
        File(createTempDirectory("echo-test").toFile(), name)

    @Test
    fun `header declares 16 kHz mono PCM16`() {
        val file = tempFile("h.wav")
        WavWriter(file).apply { write(ShortArray(100), 0, 100); close() }

        val header = file.readBytes().copyOfRange(0, 44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals("data", String(header, 36, 4))

        bb.position(20)
        assertEquals(1, bb.short.toInt())                              // PCM
        assertEquals(1, bb.short.toInt())                              // mono
        assertEquals(AudioFormatSpec.SAMPLE_RATE, bb.int)              // 16 kHz
        assertEquals(AudioFormatSpec.SAMPLE_RATE * 2, bb.int)          // byte rate
        assertEquals(2, bb.short.toInt())                              // block align
        assertEquals(16, bb.short.toInt())                             // bits per sample
    }

    @Test
    fun `sizes are patched on close`() {
        val file = tempFile("s.wav")
        val samples = 1_000
        WavWriter(file).apply { write(ShortArray(samples), 0, samples); close() }

        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        assertEquals(36 + samples * 2, bb.int)
        bb.position(40)
        assertEquals(samples * 2, bb.int)
        assertEquals(44L + samples * 2, file.length())
    }

    @Test
    fun `round trip preserves sample values`() {
        val file = tempFile("rt.wav")
        val input = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, -32768, 25, -25)
        WavWriter(file).apply { write(input, 0, input.size); close() }

        val out = WavWriter.readAsFloats(file)
        assertEquals(input.size, out.size)
        input.forEachIndexed { i, s ->
            assertEquals(s / 32768.0f, out[i], 1e-6f)
        }
    }

    @Test
    fun `partial writes with offset land in the right place`() {
        val file = tempFile("off.wav")
        val input = shortArrayOf(10, 20, 30, 40, 50)
        WavWriter(file).apply { write(input, 1, 3); close() }   // 20, 30, 40

        val out = WavWriter.readAsFloats(file)
        assertEquals(3, out.size)
        assertEquals(20 / 32768.0f, out[0], 1e-6f)
        assertEquals(40 / 32768.0f, out[2], 1e-6f)
    }

    @Test
    fun `crash-truncated header is repaired from real length`() {
        val file = tempFile("trunc.wav")
        val samples = 500
        val writer = WavWriter(file)
        writer.write(ShortArray(samples), 0, samples)
        writer.flush()
        // Deliberately do NOT close: this is what a process kill mid-chunk leaves.

        val before = ByteBuffer.wrap(file.readBytes().copyOfRange(40, 44))
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0, before)

        assertTrue(WavWriter.repairIfTruncated(file))

        val after = ByteBuffer.wrap(file.readBytes().copyOfRange(40, 44))
            .order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(samples * 2, after)
        assertEquals(samples, WavWriter.readAsFloats(file).size)

        // Repairing an already-correct file is a no-op.
        assertFalse(WavWriter.repairIfTruncated(file))
        writer.close()
    }

    @Test
    fun `close is idempotent`() {
        val file = tempFile("idem.wav")
        val w = WavWriter(file)
        w.write(ShortArray(10), 0, 10)
        w.close()
        w.close()
        assertEquals(44L + 20, file.length())
    }

    @Test
    fun `empty file reads as no samples`() {
        val file = tempFile("empty.wav")
        WavWriter(file).close()
        assertEquals(0, WavWriter.readAsFloats(file).size)
    }
}
