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
 * Which model should Echo ship with?
 *
 * Base is the current default and it is visibly weak on Devanagari even on clean
 * studio audio. Small is the obvious next step, but it is roughly three times the
 * compute, and a 24/7 recorder that cannot keep up with its own microphone is
 * useless regardless of how good its transcripts are. That trade-off should be
 * settled with numbers, not opinion.
 *
 * This does not assert a winner. It measures word recall against known references
 * and realtime factor for each installed model and logs a table. Read the log,
 * then choose.
 */
@RunWith(AndroidJUnit4::class)
class ModelComparisonInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private data class Case(val file: String, val lang: String, val reference: String)

    private val cases = listOf(
        Case("hindi.wav", "hi", "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"),
        Case("marathi.wav", "mr", "उद्या सकाळी आम्ही ऑफिसमध्ये कॅलिब्रेशनचे काम करणार आहोत."),
        Case("codeswitch.wav", "hi", "कल का sensor calibration अभी pending है, मैं evening तक update भेज दूंगा।"),
        Case("jfk.wav", "en", "and so my fellow americans ask not what your country can do for you " +
            "ask what you can do for your country"),
    )

    private fun samples(name: String): FloatArray {
        val out = File(context.cacheDir, name)
        if (!out.exists() || out.length() == 0L) {
            instrumentation.context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return AudioGain.normalize(WavWriter.readAsFloats(out)).samples
    }

    /** Fraction of reference words present in the hypothesis. Crude, but comparable. */
    private fun recall(reference: String, hypothesis: String): Float {
        val split = Regex("[\\s।.,?!]+")
        val ref = reference.lowercase().split(split).filter { it.isNotBlank() }
        if (ref.isEmpty()) return 0f
        val hyp = hypothesis.lowercase().split(split).filter { it.isNotBlank() }.toSet()
        return ref.count { it in hyp }.toFloat() / ref.size
    }

    @Test
    fun compareInstalledModels() = runBlocking {
        val models = ModelManager(context)
        val rows = StringBuilder("\n=== model comparison ===\n")

        for (model in listOf(WhisperModel.BASE, WhisperModel.SMALL)) {
            if (!models.isInstalled(model)) {
                val ok = models.download(model).isSuccess
                if (!ok) {
                    android.util.Log.w("EchoTest", "could not fetch ${model.label}; skipping")
                    continue
                }
            }

            val engine = WhisperEngine()
            engine.load(models.fileFor(model)).getOrThrow()
            try {
                var totalAudio = 0.0
                var totalMs = 0L
                for (c in cases) {
                    val audio = samples(c.file)
                    val r = engine.transcribe(audio, c.lang).getOrThrow()
                    totalAudio += audio.size / 16_000.0
                    totalMs += r.elapsedMs
                    rows.append(
                        "%-6s %-15s recall=%.2f  %s\n".format(
                            model.label, c.file, recall(c.reference, r.text), r.text.take(90),
                        )
                    )
                }
                val rtf = totalAudio / (totalMs / 1000.0)
                rows.append("%-6s OVERALL realtime=%.2fx (%.1f s audio in %d ms)\n\n".format(
                    model.label, rtf, totalAudio, totalMs,
                ))
            } finally {
                engine.release()
            }
        }

        android.util.Log.i("EchoTest", rows.toString())
        assertTrue("no model could be evaluated", rows.contains("OVERALL"))
    }
}
