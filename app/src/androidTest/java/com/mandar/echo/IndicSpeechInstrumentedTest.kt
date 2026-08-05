package com.mandar.echo

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandar.echo.stt.ModelManager
import com.mandar.echo.stt.WhisperEngine
import com.mandar.echo.stt.WhisperModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one claim nothing else in the suite actually tests: that the speech
 * recogniser handles Hindi and Marathi **from audio**.
 *
 * Every other transcription test uses `jfk.wav`, which is English, and the
 * Devanagari tests in [SummaryEngineInstrumentedTest] seed text straight into
 * Room — they exercise the tokeniser, not the STT. So without this, "multilingual
 * English/Hindi/Marathi STT" is an untested assertion.
 *
 * Audio comes from the phone's own text-to-speech engine, which keeps the test
 * hermetic and offline. Be clear about what that does and does not prove: TTS is
 * clean, close-miked, studio-grade speech. Passing here shows the model emits
 * correct Devanagari for these languages and that language detection works. It
 * says nothing about accented, far-field, noisy human speech in a real room —
 * that is what the on-phone live run is for.
 */
@RunWith(AndroidJUnit4::class)
class IndicSpeechInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun baseModel(): File = runBlocking {
        val models = ModelManager(context)
        if (!models.isInstalled(WhisperModel.BASE)) {
            models.download(WhisperModel.BASE).getOrThrow()
        }
        models.fileFor(WhisperModel.BASE)
    }

    // ---------------------------------------------------------------- TTS

    /**
     * Synthesises [text] in [locale] to a WAV, or returns null if the device has
     * no voice for that language. Returning null rather than failing matters:
     * a missing Marathi voice is a fact about the handset, not a bug in Echo.
     */
    private fun speak(locale: Locale, text: String): File? {
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            initStatus = status
            ready.countDown()
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
                android.util.Log.w("EchoTest", "TTS engine did not initialise")
                return null
            }

            val availability = tts.isLanguageAvailable(locale)
            android.util.Log.i("EchoTest", "TTS $locale availability=$availability")
            if (availability < TextToSpeech.LANG_AVAILABLE) return null
            if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return null

            // Slightly slow and level: closer to how someone dictates than to a
            // clipped notification read-out.
            tts.setSpeechRate(0.9f)
            tts.setPitch(1.0f)

            val out = File(context.cacheDir, "tts_${locale.language}.wav")
            out.delete()

            val done = CountDownLatch(1)
            var failed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = done.countDown()
                @Deprecated("required override")
                override fun onError(utteranceId: String?) {
                    failed = true; done.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed = true; done.countDown()
                }
            })

            val rc = tts.synthesizeToFile(text, Bundle(), out, "echo-${locale.language}")
            if (rc != TextToSpeech.SUCCESS) return null
            if (!done.await(60, TimeUnit.SECONDS) || failed) return null
            if (!out.exists() || out.length() < 4_000) return null

            android.util.Log.i("EchoTest", "synthesised ${out.length()} bytes for $locale")
            return out
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    // ------------------------------------------------------------- WAV/DSP

    private data class Pcm(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    /**
     * A real RIFF walker, not [com.mandar.echo.audio.WavWriter.readAsFloats].
     * That one assumes Echo's own 44-byte 16 kHz mono header; TTS output is
     * typically 24 kHz and may carry extra chunks such as LIST/fact before `data`.
     */
    private fun readWav(file: File): Pcm {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
                "not a RIFF/WAVE file: ${file.name}"
            }

            var sampleRate = 0
            var channels = 1
            var bits = 16
            var pcm: FloatArray? = null

            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val id = String(idBytes, Charsets.US_ASCII)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val declared = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL

                val bodyStart = raf.filePointer
                val available = raf.length() - bodyStart
                // A TTS engine streaming to a pipe can leave the data size at 0 or
                // at 0xFFFFFFFF, so never trust it past the end of the file.
                val size = if (declared <= 0L || declared > available) available else declared

                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtMost(40))
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.short                       // audio format
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int                         // byte rate
                        bb.short                       // block align
                        bits = bb.short.toInt()
                    }
                    "data" -> {
                        // TTS clips are seconds long; reading whole is fine here.
                        val raw = ByteArray(size.toInt())
                        raf.readFully(raw)
                        require(bits == 16) { "expected 16-bit PCM, got $bits" }
                        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                        pcm = FloatArray(raw.size / 2) { bb.short / 32768.0f }
                    }
                }

                // Advance from the start of the body, not from wherever the reads
                // above left the pointer -- chunks are word-aligned.
                val next = bodyStart + size + (size and 1L)
                if (next <= bodyStart || next >= raf.length()) break
                raf.seek(next)
            }

            val data = requireNotNull(pcm) { "no data chunk in ${file.name}" }
            require(sampleRate > 0) { "no fmt chunk in ${file.name}" }
            return Pcm(data, sampleRate, channels)
        }
    }

    /** Downmix to mono and linearly resample to the 16 kHz whisper requires. */
    private fun toWhisperInput(pcm: Pcm): FloatArray {
        val mono = if (pcm.channels <= 1) pcm.samples else {
            FloatArray(pcm.samples.size / pcm.channels) { i ->
                var acc = 0f
                for (c in 0 until pcm.channels) acc += pcm.samples[i * pcm.channels + c]
                acc / pcm.channels
            }
        }
        if (pcm.sampleRate == 16_000) return mono

        val ratio = 16_000.0 / pcm.sampleRate
        val out = FloatArray((mono.size * ratio).toInt())
        for (i in out.indices) {
            val src = i / ratio
            val a = src.toInt()
            val b = (a + 1).coerceAtMost(mono.size - 1)
            val t = (src - a).toFloat()
            out[i] = mono[a] * (1 - t) + mono[b] * t
        }
        return out
    }

    // --------------------------------------------------------------- tests

    private fun transcribeSpoken(locale: Locale, text: String, language: String): String? {
        val wav = speak(locale, text) ?: return null
        val samples = toWhisperInput(readWav(wav))
        android.util.Log.i(
            "EchoTest",
            "$locale: ${"%.1f".format(samples.size / 16_000.0)} s at 16 kHz",
        )
        assertTrue("synthesised clip is too short to be speech", samples.size > 16_000)

        return runBlocking {
            val engine = WhisperEngine()
            engine.load(baseModel()).getOrThrow()
            try {
                val result = engine.transcribe(samples, language).getOrThrow()
                android.util.Log.i(
                    "EchoTest",
                    "$locale (lang=$language) -> detected=${result.language} :: ${result.text}",
                )
                result.text
            } finally {
                engine.release()
            }
        }
    }

    private fun devanagariRatio(s: String): Float {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 0f
        return letters.count { it in 'ऀ'..'ॿ' }.toFloat() / letters.length
    }

    @Test
    fun hindiSpeechTranscribesToDevanagari() {
        val spoken = "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"
        val text = transcribeSpoken(Locale("hi", "IN"), spoken, "hi")
        assumeTrue("device has no Hindi TTS voice", text != null)

        assertTrue("whisper returned nothing for Hindi speech", text!!.isNotBlank())
        val ratio = devanagariRatio(text)
        android.util.Log.i("EchoTest", "Hindi devanagari ratio: $ratio")
        assertTrue(
            "Hindi came back as '$text' — not Devanagari (ratio $ratio)",
            ratio > 0.8f,
        )
    }

    @Test
    fun marathiSpeechTranscribesToDevanagari() {
        val spoken = "उद्या सकाळी आम्ही ऑफिसमध्ये कॅलिब्रेशनचे काम करणार आहोत."
        val text = transcribeSpoken(Locale("mr", "IN"), spoken, "mr")
        assumeTrue("device has no Marathi TTS voice", text != null)

        assertTrue("whisper returned nothing for Marathi speech", text!!.isNotBlank())
        val ratio = devanagariRatio(text)
        android.util.Log.i("EchoTest", "Marathi devanagari ratio: $ratio")
        assertTrue(
            "Marathi came back as '$text' — not Devanagari (ratio $ratio)",
            ratio > 0.8f,
        )
    }

    /**
     * Auto-detection is what actually runs in production: [com.mandar.echo.stt.TranscriptionPipeline]
     * passes the user's language setting, and the default is `auto` so a
     * code-switched day survives. Marathi and Hindi share a script and a great deal
     * of vocabulary, and small Whisper models routinely label Marathi as Hindi, so
     * the assertion is that detection lands in the Indic pair — not that it
     * discriminates between them.
     */
    @Test
    fun autoDetectionLandsOnAnIndicLanguage() {
        val spoken = "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"
        val wav = speak(Locale("hi", "IN"), spoken)
        assumeTrue("device has no Hindi TTS voice", wav != null)

        val samples = toWhisperInput(readWav(wav!!))
        runBlocking {
            val engine = WhisperEngine()
            engine.load(baseModel()).getOrThrow()
            try {
                val result = engine.transcribe(samples, "auto").getOrThrow()
                android.util.Log.i(
                    "EchoTest",
                    "auto-detect on Hindi speech -> ${result.language} :: ${result.text}",
                )
                assertTrue(
                    "auto-detect said '${result.language}' for Hindi speech",
                    result.language in setOf("hi", "mr"),
                )
                assertTrue(devanagariRatio(result.text) > 0.8f)
            } finally {
                engine.release()
            }
        }
    }
}
