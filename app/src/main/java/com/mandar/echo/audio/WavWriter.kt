package com.mandar.echo.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming 16-bit mono PCM WAV writer.
 *
 * The 44-byte header is written up front with zeroed sizes and patched on
 * [close], so a chunk can be streamed to disk without knowing its length in
 * advance. If the process dies mid-chunk the file is left with a zero data size;
 * [repairIfTruncated] reconstructs the header from the real file length so the
 * audio is still recoverable rather than lost.
 */
class WavWriter(val file: File) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var closed = false

    /** Bytes written to the data section so far. */
    val bytesWritten: Long get() = dataBytes

    init {
        raf.setLength(0)
        raf.write(header(0))
    }

    /** Writes [count] samples starting at [offset] from [samples]. */
    fun write(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        val bb = ByteBuffer.allocate(count * AudioFormatSpec.BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in offset until offset + count) bb.putShort(samples[i])
        raf.write(bb.array())
        dataBytes += count.toLong() * AudioFormatSpec.BYTES_PER_SAMPLE
    }

    fun flush() {
        raf.fd.sync()
    }

    /** Patches the RIFF/data sizes and closes. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        try {
            raf.seek(0)
            raf.write(header(dataBytes))
            raf.fd.sync()
        } finally {
            raf.close()
        }
    }

    private fun header(dataSize: Long): ByteArray {
        val sr = AudioFormatSpec.SAMPLE_RATE
        val ch = AudioFormatSpec.CHANNELS
        val bps = AudioFormatSpec.BITS_PER_SAMPLE
        val byteRate = sr * ch * bps / 8
        val blockAlign = ch * bps / 8

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                    // PCM subchunk size
            putShort(1)                   // format = PCM
            putShort(ch.toShort())
            putInt(sr)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bps.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
        }.array()
    }

    companion object {
        const val HEADER_BYTES = 44L

        /**
         * Reads a mono 16-bit WAV into the normalised float array whisper wants.
         * Tolerates a zeroed data size (crash mid-write) by trusting file length.
         *
         * Streamed in 64 KB blocks on purpose. A full 10-minute chunk is 9.6M
         * samples: slurping the whole file first would hold a 19 MB byte array and
         * the 38 MB float array live at the same time, and JNI may then copy the
         * float array again. That peak is enough to OOM a mid-range phone, and it
         * is invisible to a unit test that only writes a few hundred samples.
         */
        fun readAsFloats(file: File): FloatArray {
            RandomAccessFile(file, "r").use { raf ->
                val total = raf.length()
                if (total <= HEADER_BYTES) return FloatArray(0)

                val sampleCount = ((total - HEADER_BYTES) / 2).toInt()
                if (sampleCount <= 0) return FloatArray(0)

                val out = FloatArray(sampleCount)
                raf.seek(HEADER_BYTES)

                val block = ByteArray(1 shl 16)          // 64 KB, always even
                var written = 0
                try {
                    while (written < sampleCount) {
                        val want = minOf(block.size, (sampleCount - written) * 2)
                        raf.readFully(block, 0, want)
                        val bb = ByteBuffer.wrap(block, 0, want).order(ByteOrder.LITTLE_ENDIAN)
                        repeat(want / 2) { out[written++] = bb.short / 32768.0f }
                    }
                } catch (_: java.io.EOFException) {
                    // Truncated file: keep whatever was readable.
                }
                return if (written == sampleCount) out else out.copyOf(written)
            }
        }

        /** Rewrites the header of a chunk that was truncated by a crash. */
        fun repairIfTruncated(file: File): Boolean {
            if (!file.exists() || file.length() <= HEADER_BYTES) return false
            RandomAccessFile(file, "rw").use { raf ->
                val declared = run {
                    raf.seek(40)
                    val b = ByteArray(4)
                    raf.readFully(b)
                    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                }
                val actual = raf.length() - HEADER_BYTES
                if (declared == actual) return false
                raf.seek(4)
                raf.write(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt((36 + actual).toInt()).array()
                )
                raf.seek(40)
                raf.write(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(actual.toInt()).array()
                )
                return true
            }
        }
    }
}
