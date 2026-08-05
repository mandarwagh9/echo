package com.mandar.echo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandar.echo.audio.AudioGain
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.stt.ModelManager
import com.mandar.echo.stt.WhisperEngine
import com.mandar.echo.stt.WhisperModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The claim the whole app rests on and that nothing else tests: that speech in
 * Hindi and Marathi comes back as Hindi and Marathi.
 *
 * Every other transcription test uses `jfk.wav`, which is English, and the
 * Devanagari tests in [SummaryEngineInstrumentedTest] seed text straight into
 * Room -- they exercise the tokeniser, not the recogniser.
 *
 * The fixtures are 16 kHz mono clips synthesised by Google Cloud Text-to-Speech,
 * committed so the test is hermetic and gives the same answer on any device. An
 * earlier version drove the handset's own TTS engine, which made the result
 * depend on which voices happened to be installed.
 *
 * Be clear about what this does and does not prove. These are clean, close-miked,
 * studio-grade clips. Passing shows the model emits correct Devanagari for these
 * languages and that language selection works. It says nothing about accented,
 * far-field, noisy speech in a real room -- and on the developer's own phone,
 * that harder case is exactly where Base struggles.
 */
@RunWith(AndroidJUnit4::class)
class IndicSpeechInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun fixture(name: String): FloatArray {
        val out = File(context.cacheDir, name)
        if (!out.exists() || out.length() == 0L) {
            instrumentation.context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        val samples = WavWriter.readAsFloats(out)
        assertTrue("$name read as ${samples.size} samples", samples.size > 16_000)
        return AudioGain.normalize(samples).samples
    }

    private fun engine(): WhisperEngine = runBlocking {
        val models = ModelManager(context)
        if (!models.isInstalled(WhisperModel.BASE)) {
            models.download(WhisperModel.BASE).getOrThrow()
        }
        // vadModelPath is left empty on purpose: that is the shipping
        // configuration. Enabling Silero here measured 0.231 recall against 0.385
        // without it — see the note in TranscriptionPipeline.
        WhisperEngine().also { it.load(models.fileFor(WhisperModel.BASE)).getOrThrow() }
    }

    private fun devanagariRatio(s: String): Float {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return 0f
        return letters.count { it in 'ऀ'..'ॿ' }.toFloat() / letters.length
    }

    /** Fraction of the reference's words that appear in the hypothesis. */
    private fun recall(reference: String, hypothesis: String): Float {
        val split = Regex("[\\s।.,?!]+")
        val ref = reference.split(split).filter { it.isNotBlank() }
        if (ref.isEmpty()) return 0f
        val hyp = hypothesis.split(split).filter { it.isNotBlank() }.toSet()
        return ref.count { it in hyp }.toFloat() / ref.size
    }

    private fun transcribe(fixture: String, language: String): Pair<String, String> =
        runBlocking {
            val e = engine()
            try {
                val r = e.transcribe(fixture(fixture), language).getOrThrow()
                android.util.Log.i(
                    "EchoTest",
                    "$fixture lang=$language -> detected=${r.language} :: ${r.text}",
                )
                r.text to r.language
            } finally {
                e.release()
            }
        }

    @Test
    fun hindiComesBackInDevanagari() {
        val reference = "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"
        val (text, _) = transcribe("hindi.wav", "hi")
        val ratio = devanagariRatio(text)
        val rec = recall(reference, text)
        android.util.Log.i("EchoTest", "hindi: devanagari=$ratio recall=$rec")

        assertTrue("nothing returned", text.isNotBlank())
        assertTrue("came back as '$text' — not Devanagari (ratio $ratio)", ratio > 0.8f)

        // 0.30 is a regression floor, not a quality bar. Base measures 0.38 on this
        // clip -- it recovers barely a third of the words of clean, studio-recorded
        // Hindi, and that is the honest ceiling of the shipping default. The point
        // of pinning it is to notice if a change makes it worse; the point of
        // writing the number down is to stop anyone believing Base is good at this.
        assertTrue("only $rec of the reference words survived: '$text'", rec >= 0.30f)
    }

    @Test
    fun marathiComesBackInDevanagari() {
        val reference = "उद्या सकाळी आम्ही ऑफिसमध्ये कॅलिब्रेशनचे काम करणार आहोत."
        val (text, _) = transcribe("marathi.wav", "mr")
        val ratio = devanagariRatio(text)
        android.util.Log.i("EchoTest", "marathi: devanagari=$ratio recall=${recall(reference, text)}")

        assertTrue("nothing returned", text.isNotBlank())
        assertTrue("came back as '$text' — not Devanagari (ratio $ratio)", ratio > 0.8f)
    }

    /**
     * The regression guard for the bug that produced Cyrillic. Auto-detection must
     * only ever choose from the languages the app supports.
     */
    @Test
    fun autoDetectionStaysWithinTheSupportedLanguages() {
        for (fixture in listOf("hindi.wav", "marathi.wav", "codeswitch.wav")) {
            val (text, detected) = transcribe(fixture, "auto")
            assertTrue(
                "$fixture auto-detected as '$detected', outside en/hi/mr",
                detected in setOf("en", "hi", "mr"),
            )
            assertTrue(
                "$fixture auto-detected as '$detected' but produced '$text'",
                devanagariRatio(text) > 0.8f,
            )
        }
    }

    /** English must not regress now that a prompt is being injected. */
    @Test
    fun englishIsUnaffectedByThePrompt() {
        val (text, detected) = transcribe("jfk.wav", "auto")
        assertTrue("detected $detected", detected == "en")
        assertTrue("transcript was '$text'", text.lowercase().contains("country"))
        assertTrue("Devanagari leaked into English: '$text'", devanagariRatio(text) < 0.05f)
    }
}
