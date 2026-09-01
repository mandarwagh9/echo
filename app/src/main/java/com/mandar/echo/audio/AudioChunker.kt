package com.mandar.echo.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

private const val TAG = "AudioChunker"

/**
 * Continuous capture split across two threads.
 *
 *   reader thread  — does nothing but AudioRecord.read() and hand off. Never
 *                    touches the filesystem, the database, or a lock that a slow
 *                    operation could hold. If it ever stalls, AudioRecord's
 *                    internal ring buffer silently overwrites and audio is lost
 *                    with no error, so this thread must stay trivial.
 *
 *   writer thread  — owns all file I/O and chunk rotation. A slow flush backs up
 *                    into the handoff queue (~16 s of slack) instead of the mic.
 *
 * Rotation is sample-exact: AudioRecord is never stopped, and a buffer straddling
 * the 10-minute boundary is split across the two files. Chunk N+1 therefore begins
 * on the sample immediately after chunk N, with no gap by construction.
 */
class AudioChunker(
    private val outputDir: File,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val chunkSamples: Long = AudioFormatSpec.CHUNK_SAMPLES,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** Returns the database id assigned to the newly opened chunk. */
        fun onChunkStarted(file: File, startedAtMs: Long): Long
        fun onChunkClosed(chunkId: Long, file: File, sampleCount: Long, endedAtMs: Long)
        fun onLevel(rms: Float)

        /**
         * Whether anything is actually going to draw [onLevel]'s value.
         *
         * Called on the reader thread, so implementations must be a plain field
         * read and nothing more. When it returns false the RMS pass is skipped
         * entirely rather than computed and thrown away — which is the normal
         * case, because a 24/7 recorder runs with the screen off almost always.
         */
        fun wantsLevels(): Boolean = true

        fun onFatalError(t: Throwable)
        /** The mic went away (call, assistant, other app). Service decides how to recover. */
        fun onCaptureLost(reason: String)
    }

    private class Buf(val data: ShortArray) { var len = 0 }

    private val running = AtomicBoolean(false)
    private val free = ArrayBlockingQueue<Buf>(AudioFormatSpec.QUEUE_CAPACITY + 16)
    private val filled = ArrayBlockingQueue<Buf>(AudioFormatSpec.QUEUE_CAPACITY)

    private val droppedSamples = AtomicLong(0)
    private val totalSamples = AtomicLong(0)

    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    @Volatile private var audioRecord: AudioRecord? = null

    /** Samples discarded because the writer could not keep up. Surfaced in the UI. */
    val dropped: Long get() = droppedSamples.get()
    val captured: Long get() = totalSamples.get()
    val isRunning: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before start()
    fun start() {
        if (running.getAndSet(true)) return
        outputDir.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            running.set(false)
            callbacks.onFatalError(IllegalStateException("getMinBufferSize returned $minBuf"))
            return
        }
        // At least 2 s of headroom so a scheduling hiccup cannot cost us audio.
        val bufferBytes = maxOf(minBuf * 4, AudioFormatSpec.SAMPLE_RATE * 2 * 2)

        val record = try {
            AudioRecord(
                audioSource,
                AudioFormatSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            running.set(false)
            callbacks.onFatalError(t)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            callbacks.onFatalError(IllegalStateException("AudioRecord not initialised"))
            return
        }

        audioRecord = record
        repeat(AudioFormatSpec.QUEUE_CAPACITY) {
            free.offer(Buf(ShortArray(AudioFormatSpec.BUFFER_SAMPLES)))
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            record.release()
            audioRecord = null
            running.set(false)
            callbacks.onFatalError(t)
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audioRecord = null
            running.set(false)
            callbacks.onFatalError(IllegalStateException("startRecording() did not take effect"))
            return
        }

        writerThread = Thread(::writerLoop, "echo-writer").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        readerThread = Thread(::readerLoop, "echo-reader").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "capture started (source=$audioSource, buffer=$bufferBytes B)")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        readerThread?.join(3_000)
        writerThread?.join(10_000)
        readerThread = null
        writerThread = null
        audioRecord?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null
        free.clear()
        filled.clear()
        Log.i(TAG, "capture stopped")
    }

    // ---- reader -----------------------------------------------------------

    private fun readerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val record = audioRecord ?: return
        var levelCounter = 0

        while (running.get()) {
            val buf = free.poll() ?: Buf(ShortArray(AudioFormatSpec.BUFFER_SAMPLES))
            val n = try {
                record.read(buf.data, 0, AudioFormatSpec.BUFFER_SAMPLES)
            } catch (t: Throwable) {
                free.offer(buf)
                callbacks.onCaptureLost("read threw: ${t.message}")
                return
            }

            when {
                n > 0 -> {
                    buf.len = n
                    totalSamples.addAndGet(n.toLong())
                    if (++levelCounter % 4 == 0 && callbacks.wantsLevels()) {
                        callbacks.onLevel(rmsOf(buf.data, n))
                    }
                    if (!filled.offer(buf)) {
                        // Writer is wedged. Drop the newest buffer and record an
                        // honest, visible gap rather than stalling the mic.
                        droppedSamples.addAndGet(n.toLong())
                        free.offer(buf)
                    }
                }
                n == 0 -> free.offer(buf)
                else -> {
                    free.offer(buf)
                    val reason = when (n) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "read error $n"
                    }
                    Log.w(TAG, "capture lost: $reason")
                    callbacks.onCaptureLost(reason)
                    return
                }
            }
        }
    }

    private fun rmsOf(data: ShortArray, n: Int): Float {
        var acc = 0.0
        var i = 0
        // Every 8th sample is plenty for a UI level meter.
        while (i < n) {
            val s = data[i] / 32768.0
            acc += s * s
            i += 8
        }
        val count = (n + 7) / 8
        return if (count == 0) 0f else sqrt(acc / count).toFloat()
    }

    // ---- writer -----------------------------------------------------------

    private fun writerLoop() {
        var writer: WavWriter? = null
        var chunkId = -1L
        var written = 0L

        fun openChunk() {
            val startedAt = System.currentTimeMillis()
            val file = File(outputDir, "chunk_$startedAt.wav")
            writer = WavWriter(file)
            written = 0
            chunkId = callbacks.onChunkStarted(file, startedAt)
        }

        fun closeChunk() {
            val w = writer ?: return
            w.close()
            if (chunkId >= 0) {
                callbacks.onChunkClosed(chunkId, w.file, written, System.currentTimeMillis())
            }
            writer = null
            chunkId = -1
            written = 0
        }

        try {
            while (running.get() || filled.isNotEmpty()) {
                val buf = filled.poll(250, TimeUnit.MILLISECONDS) ?: continue

                // Sizes sum to exactly buf.len, so a buffer landing across the
                // 10-minute boundary is split rather than truncated.
                var offset = 0
                for (n in ChunkMath.splits(written, chunkSamples, buf.len)) {
                    if (writer == null) openChunk()
                    writer!!.write(buf.data, offset, n)
                    written += n
                    offset += n
                    if (written >= chunkSamples) closeChunk()   // resets written to 0
                }
                free.offer(buf)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "writer failed", t)
            callbacks.onFatalError(t)
        } finally {
            // A partial final chunk is still worth keeping and transcribing.
            runCatching { closeChunk() }
        }
    }
}
