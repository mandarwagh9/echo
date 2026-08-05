package com.mandar.echo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.stt.ModelManager
import com.mandar.echo.stt.WhisperEngine
import com.mandar.echo.stt.WhisperModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * On-device verification of the parts that unit tests cannot reach: that the JNI
 * symbols actually resolve, that whisper produces correct text, and that a chunk
 * really does get its audio deleted once transcribed.
 *
 * Uses whisper.cpp's own `jfk.wav` fixture, whose expected transcript is known.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WhisperPipelineInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as EchoApp

    /**
     * The Android emulator runs an x86_64 CPU without AVX2 (it reports only
     * SSE3/SSSE3), and ggml is heavily SIMD-bound. Throughput measured there says
     * nothing about a real arm64 phone with NEON, so the realtime assertion is
     * enforced on hardware and only reported on an emulator.
     */
    private fun isEmulator(): Boolean =
        android.os.Build.FINGERPRINT.startsWith("generic") ||
            android.os.Build.FINGERPRINT.contains("vbox") ||
            android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.PRODUCT.contains("sdk_gphone")

    private fun jfkFile(): File {
        val out = File(context.cacheDir, "jfk.wav")
        if (!out.exists() || out.length() == 0L) {
            instrumentation.context.assets.open("jfk.wav").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    private fun ensureModel(model: WhisperModel = WhisperModel.TINY): File = runBlocking {
        val models = ModelManager(context)
        if (!models.isInstalled(model)) {
            models.download(model).getOrThrow()
        }
        models.fileFor(model)
    }

    /**
     * Isolated first so a symbol-mangling mistake surfaces as its own clear
     * failure rather than an UnsatisfiedLinkError buried inside a transcription.
     */
    @Test
    fun a_jniSymbolsResolveAndLibraryLoads() {
        val info = WhisperEngine().systemInfo()
        android.util.Log.i("EchoTest", "system info: $info")
        assertTrue("getSystemInfo returned '$info'", info.contains("whisper.cpp"))
        assertFalse(info == "unavailable")
    }

    @Test
    fun b_wavFixtureReadsAsSixteenKhzMono() {
        val samples = WavWriter.readAsFloats(jfkFile())
        // jfk.wav is ~11 s of 16 kHz mono audio.
        assertTrue("got ${samples.size} samples", samples.size > 16_000 * 8)
        assertTrue(samples.any { it != 0f })
    }

    @Test
    fun c_vadDetectsSpeechInRealAudio() {
        val result = VoiceActivityDetector.analyse(WavWriter.readAsFloats(jfkFile()))
        assertTrue("speech ratio was ${result.speechRatio}", result.hasSpeech)
    }

    @Test
    fun d_transcribesKnownSampleCorrectly() = runBlocking {
        val engine = WhisperEngine()
        engine.load(ensureModel()).getOrThrow()

        val result = engine.transcribe(WavWriter.readAsFloats(jfkFile()), "en").getOrThrow()
        val text = result.text.lowercase()
        android.util.Log.i("EchoTest", "transcript: $text (${result.elapsedMs} ms)")

        assertTrue("transcript was '$text'", text.contains("country"))
        assertTrue("transcript was '$text'", text.contains("ask not") || text.contains("fellow"))
        assertTrue(result.segments.isNotEmpty())
        assertEquals("en", result.language)

        // Must beat realtime on real hardware, or a 24/7 app can never keep up.
        val audioSeconds = 11.0
        val rtf = audioSeconds / (result.elapsedMs / 1000.0)
        android.util.Log.i(
            "EchoTest",
            "realtime factor: %.2fx on %s (%s)".format(
                rtf, android.os.Build.MODEL, android.os.Build.HARDWARE,
            ),
        )
        if (isEmulator()) {
            android.util.Log.w(
                "EchoTest",
                "emulator: skipping the realtime assertion (%.2fx measured on an ".format(rtf) +
                    "emulated CPU without AVX2 — not representative of arm64)",
            )
        } else {
            assertTrue("only ${"%.2f".format(rtf)}x realtime on real hardware", rtf > 1.0)
        }

        engine.release()
    }

    /**
     * Throughput of the model the app actually ships with.
     *
     * [d_transcribesKnownSampleCorrectly] measures Tiny on an 11-second clip, which
     * flatters the result twice over: Tiny is several times faster than Base, and
     * whisper zero-pads every window to 30 s, so a short clip's cost is dominated by
     * padding. This measures **Base** over ~66 s — three full windows — which is the
     * steady-state rate that decides whether a 24/7 app can drain its queue.
     *
     * The bar is 2x, not 1x. A lab run is a cold, idle phone doing nothing else; in
     * service the same work competes with continuous capture and thermal throttling,
     * so anything near parity means falling behind in practice.
     */
    @Test
    fun d2_baseModelSustainsRealtimeThroughput() = runBlocking {
        val engine = WhisperEngine()
        engine.load(ensureModel(WhisperModel.BASE)).getOrThrow()

        val clip = WavWriter.readAsFloats(jfkFile())
        val repeats = 6
        val long = FloatArray(clip.size * repeats)
        repeat(repeats) { System.arraycopy(clip, 0, long, it * clip.size, clip.size) }
        val audioSeconds = long.size / 16_000.0

        val result = engine.transcribe(long, "en").getOrThrow()
        val rtf = audioSeconds / (result.elapsedMs / 1000.0)
        android.util.Log.i(
            "EchoTest",
            "BASE realtime factor: %.2fx  (%.1f s of audio in %d ms, %d threads) on %s / %s"
                .format(
                    rtf, audioSeconds, result.elapsedMs, engine.threadCount,
                    android.os.Build.MODEL, android.os.Build.HARDWARE,
                ),
        )
        android.util.Log.i("EchoTest", "BASE transcript: ${result.text.take(220)}")

        assertTrue("Base produced no text", result.text.contains("country", ignoreCase = true))

        // Projected cost of the shipping 10-minute chunk at this rate.
        android.util.Log.i(
            "EchoTest",
            "projected: a 10-minute chunk transcribes in %.0f s".format(600.0 / rtf),
        )

        if (isEmulator()) {
            android.util.Log.w("EchoTest", "emulator: not asserting throughput")
        } else {
            assertTrue(
                "Base managed only ${"%.2f".format(rtf)}x realtime — switch the default to Tiny",
                rtf > 2.0,
            )
        }
        engine.release()
    }

    @Test
    fun e_autoDetectFindsEnglish() = runBlocking {
        val engine = WhisperEngine()
        engine.load(ensureModel()).getOrThrow()
        val result = engine.transcribe(WavWriter.readAsFloats(jfkFile()), "auto").getOrThrow()
        android.util.Log.i("EchoTest", "auto-detected language: ${result.language}")
        assertEquals("en", result.language)
        engine.release()
    }

    /**
     * The requirement that matters most: audio is deleted once — and only once —
     * its transcript is durably stored.
     */
    @Test
    fun f_pipelineTranscribesThenDeletesAudio() = runBlocking {
        ensureModel()

        val dir = File(context.filesDir, "chunks").apply { mkdirs() }
        val wav = File(dir, "chunk_itest_${System.currentTimeMillis()}.wav")
        jfkFile().copyTo(wav, overwrite = true)
        assertTrue(wav.exists())

        val samples = WavWriter.readAsFloats(wav).size.toLong()
        val startedAt = System.currentTimeMillis()
        val id = app.db.chunkDao().insert(
            ChunkEntity(
                startedAt = startedAt,
                endedAt = startedAt + samples * 1000 / 16_000,
                filePath = wav.absolutePath,
                sampleCount = samples,
                status = ChunkStatus.PENDING,
            )
        )

        assertTrue("pipeline reported no work", app.pipeline.processNext())

        val chunk = app.db.chunkDao().byId(id)!!
        assertEquals(ChunkStatus.DONE, chunk.status)
        assertTrue("audioDeleted flag not set", chunk.audioDeleted)
        assertFalse("WAV still on disk", wav.exists())
        assertEquals(null, chunk.filePath)
        assertTrue(chunk.wordCount > 0)

        val segments = app.db.segmentDao().forChunk(id)
        assertTrue("no segments stored", segments.isNotEmpty())
        // Segment timestamps must be absolute epoch ms, not chunk-relative.
        assertTrue(segments.first().startMs >= startedAt)
        assertTrue(segments.joinToString(" ") { it.text }.lowercase().contains("country"))
    }

    /**
     * The default chunk size, at full scale.
     *
     * 10 minutes is 9,600,000 samples: 19.2 MB on disk and a single contiguous
     * 38.4 MB FloatArray when read back, before any JNI copy. Every other test
     * here uses 1-minute chunks or an 11-second fixture, so without this the
     * shipping configuration's memory path would never actually execute. Silence
     * is used deliberately — the VAD short-circuits whisper, so this measures the
     * allocation and the delete cycle rather than transcription speed.
     */
    @Test
    fun h_fullSizeTenMinuteChunkSurvivesTheMemoryPath() = runBlocking {
        ensureModel()

        val dir = File(context.filesDir, "chunks").apply { mkdirs() }
        val wav = File(dir, "chunk_full_${System.currentTimeMillis()}.wav")

        val writer = WavWriter(wav)
        val oneSecond = ShortArray(16_000)
        repeat(600) { writer.write(oneSecond, 0, oneSecond.size) }
        writer.close()

        assertEquals(44L + 9_600_000L * 2, wav.length())

        val startedAt = System.currentTimeMillis()
        val id = app.db.chunkDao().insert(
            ChunkEntity(
                startedAt = startedAt,
                endedAt = startedAt + 600_000,
                filePath = wav.absolutePath,
                sampleCount = 9_600_000L,
                status = ChunkStatus.PENDING,
            )
        )

        assertTrue("pipeline reported no work", app.pipeline.processNext())

        val chunk = app.db.chunkDao().byId(id)!!
        assertEquals(ChunkStatus.SILENT, chunk.status)
        assertTrue(chunk.audioDeleted)
        assertFalse("19 MB WAV was not reclaimed", wav.exists())
    }

    /** A silent chunk must skip whisper entirely and still have its audio removed. */
    @Test
    fun g_silentChunkIsSkippedAndCleanedUp() = runBlocking {
        val dir = File(context.filesDir, "chunks").apply { mkdirs() }
        val wav = File(dir, "chunk_silent_${System.currentTimeMillis()}.wav")
        WavWriter(wav).apply {
            write(ShortArray(16_000 * 30), 0, 16_000 * 30)   // 30 s of digital silence
            close()
        }

        val startedAt = System.currentTimeMillis()
        val id = app.db.chunkDao().insert(
            ChunkEntity(
                startedAt = startedAt,
                endedAt = startedAt + 30_000,
                filePath = wav.absolutePath,
                sampleCount = 16_000L * 30,
                status = ChunkStatus.PENDING,
            )
        )

        assertTrue(app.pipeline.processNext())

        val chunk = app.db.chunkDao().byId(id)!!
        assertEquals(ChunkStatus.SILENT, chunk.status)
        assertTrue(chunk.audioDeleted)
        assertFalse(wav.exists())
        assertTrue(app.db.segmentDao().forChunk(id).isEmpty())
    }
}
