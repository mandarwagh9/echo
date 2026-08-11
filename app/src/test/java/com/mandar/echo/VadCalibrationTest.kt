package com.mandar.echo

import com.mandar.echo.audio.AudioFormatSpec
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the gate actually does, measured rather than asserted from intuition.
 *
 * The constants in [VoiceActivityDetector] decide whether a recording is kept or
 * deleted, and the deletion direction leaves no evidence behind -- a chunk wrongly
 * called silent has its WAV released, so there is nothing left to count. That makes
 * recall unmeasurable in the field and this file the only place it is checked at all.
 *
 * The speech here is the four TTS fixtures the STT work already uses. They are clean
 * and close-mic'd, so passing them is a floor, not a result: a gate that rejects
 * studio-clean speech is broken, while one that accepts it may still fail at three
 * metres. The noise is synthetic, and the mixes are the closest thing to the real
 * failure case that exists -- the chunk that shipped 53 s of noise to the decoder was
 * never saved (AUDIT-2026-08-06 §D, "the 88 %-voiced chunk"), so nothing here is
 * calibrated against it.
 */
class VadCalibrationTest {

    private val rate = AudioFormatSpec.SAMPLE_RATE

    // ---- fixtures ---------------------------------------------------------

    private fun fixture(name: String): FloatArray {
        val candidates = listOf(
            File("src/androidTest/assets/$name"),
            File("app/src/androidTest/assets/$name"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("fixture $name not found (looked in ${candidates.joinToString()})")
        return WavWriter.readAsFloats(f)
    }

    private fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var acc = 0.0
        for (s in x) acc += s.toDouble() * s
        return sqrt(acc / x.size).toFloat()
    }

    /** Broadband noise at a constant level: a fan, an air conditioner, road hum. */
    private fun stationaryNoise(seconds: Double, level: Float, seed: Int = 11): FloatArray {
        val rng = Random(seed)
        return FloatArray((rate * seconds).toInt()) { (rng.nextFloat() - 0.5f) * 2f * level }
    }

    /**
     * Noise with syllable-rate amplitude modulation: a television in the next room,
     * or babble from a crowd. This is the case the percentile floor collapses on --
     * the modulation puts the 20th percentile in a trough, so most frames clear
     * 2.5x it and the whole minute reads as voiced.
     */
    private fun modulatedNoise(seconds: Double, level: Float, seed: Int = 13): FloatArray {
        val rng = Random(seed)
        return FloatArray((rate * seconds).toInt()) { i ->
            val envelope = 0.55f + 0.45f * sin(2.0 * PI * 3.5 * i / rate).toFloat()
            (rng.nextFloat() - 0.5f) * 2f * level * envelope
        }
    }

    /** Low-frequency rumble: traffic through a wall, a motor. */
    private fun rumble(seconds: Double, level: Float): FloatArray =
        FloatArray((rate * seconds).toInt()) { i ->
            val t = i.toDouble() / rate
            (level * (sin(2.0 * PI * 55.0 * t) + 0.6 * sin(2.0 * PI * 90.0 * t)) / 1.6).toFloat()
        }

    /** [speech] laid into [background] at the given SNR, positioned a third of the way in. */
    private fun mix(speech: FloatArray, background: FloatArray, snrDb: Double): FloatArray {
        val scale = (rms(speech) / rms(background).coerceAtLeast(1e-9f)) /
            Math.pow(10.0, snrDb / 20.0).toFloat()
        val out = FloatArray(background.size) { background[it] * scale }
        val at = (out.size / 3).coerceAtMost((out.size - speech.size).coerceAtLeast(0))
        for (i in speech.indices) {
            if (at + i < out.size) out[at + i] += speech[i]
        }
        return out
    }

    /** Silence, then [speech], then silence -- the short-utterance-in-a-long-chunk case. */
    private fun buried(speech: FloatArray, totalSeconds: Double): FloatArray {
        val out = FloatArray((rate * totalSeconds).toInt())
        val at = (out.size / 2 - speech.size / 2).coerceAtLeast(0)
        for (i in speech.indices) if (at + i < out.size) out[at + i] = speech[i]
        return out
    }

    // ---- reporting --------------------------------------------------------

    /** [rawVoicedMs] is the unpadded voiced total, which is what the gate decides on. */
    private data class Row(val name: String, val speech: Boolean, val ratio: Float, val rawVoicedMs: Long)

    private fun measure(name: String, samples: FloatArray): Row {
        val v = VoiceActivityDetector.analyse(samples)
        return Row(name, v.hasSpeech, v.speechRatio, v.rawVoicedMs)
    }

    private fun report(title: String, rows: List<Row>) {
        println("\n=== $title ===")
        println("%-34s %-8s %8s %10s".format("case", "speech?", "ratio", "voiced ms"))
        rows.forEach {
            println("%-34s %-8s %8.3f %10d".format(it.name, it.speech, it.ratio, it.rawVoicedMs))
        }
    }

    // ---- the measurements -------------------------------------------------

    @Test
    fun `real speech is not rejected`() {
        val rows = listOf("marathi.wav", "hindi.wav", "codeswitch.wav", "jfk.wav").map {
            measure(it, fixture(it))
        }
        report("clean speech (must all pass)", rows)
        rows.forEach { assertTrue("${it.name} was gated out as silence", it.speech) }
    }

    @Test
    fun `speech survives being buried in a long quiet chunk`() {
        // The 10-minute case from AUDIT-2026-08-06 §D: one short utterance in a long
        // chunk falls under a gate denominated as a fraction of chunk length.
        val speech = fixture("marathi.wav")
        val rows = listOf(60.0, 300.0, 600.0).map {
            measure("marathi in ${it.toInt()}s of silence", buried(speech, it))
        }
        report("short utterance, long chunk (must all pass)", rows)
        rows.forEach { assertTrue("${it.name} was deleted as silent", it.speech) }
    }

    @Test
    fun `speech over noise is still speech`() {
        val speech = fixture("marathi.wav")
        val rows = listOf(20.0, 12.0, 6.0, 0.0).map { snr ->
            measure("marathi over babble @ ${snr.toInt()}dB", mix(speech, modulatedNoise(60.0, 0.05f), snr))
        } + listOf(20.0, 12.0, 6.0).map { snr ->
            measure("marathi over hum @ ${snr.toInt()}dB", mix(speech, stationaryNoise(60.0, 0.03f), snr))
        }
        report("speech in noise (recall -- must pass down to 6dB)", rows)
        rows.filter { !it.name.endsWith("@ 0dB") }.forEach {
            assertTrue("${it.name} was deleted as silent", it.speech)
        }

        // 0 dB is where this gate stops, and it is pinned rather than excused: the
        // speaker is exactly as loud as the television, 900 ms survives against a
        // 1,000 ms bar, and the chunk is dropped. A recogniser handed that audio
        // would return very little anyway, but it is a real limit and the number
        // above is 100 ms from moving. If a future change is meant to reach further
        // down, this is the assertion that should fail first.
        val atZero = rows.first { it.name.endsWith("@ 0dB") }
        assertFalse("0 dB now passes -- update this boundary deliberately", atZero.speech)
    }

    /**
     * The frame-energy distribution of each case, which is all the detector can see.
     * Printed so the constants below are chosen from numbers rather than intuition.
     */
    @Test
    fun `frame energy distributions`() {
        fun frameRms(x: FloatArray): FloatArray {
            val n = rate * 20 / 1000
            return FloatArray(x.size / n) { f ->
                var acc = 0.0
                for (i in 0 until n) { val s = x[f * n + i].toDouble(); acc += s * s }
                sqrt(acc / n).toFloat()
            }
        }
        fun pct(sorted: FloatArray, p: Float) = sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]

        val cases = linkedMapOf(
            "marathi.wav" to fixture("marathi.wav"),
            "hindi.wav" to fixture("hindi.wav"),
            "jfk.wav" to fixture("jfk.wav"),
            "marathi in 300s" to buried(fixture("marathi.wav"), 300.0),
            "quiet room tone" to stationaryNoise(60.0, 0.0004f),
            "loud fan" to stationaryNoise(60.0, 0.03f),
            "traffic rumble" to rumble(60.0, 0.05f),
            "television next door" to modulatedNoise(60.0, 0.05f),
            "loud television" to modulatedNoise(60.0, 0.15f),
            "marathi over babble 12dB" to mix(fixture("marathi.wav"), modulatedNoise(60.0, 0.05f), 12.0),
            "marathi over babble 6dB" to mix(fixture("marathi.wav"), modulatedNoise(60.0, 0.05f), 6.0),
        )

        println("\n=== frame energy (20 ms frames) ===")
        println("%-26s %9s %9s %9s %9s %8s".format("case", "p20", "p50", "p90", "max", "p90/p20"))
        cases.forEach { (name, samples) ->
            val sorted = frameRms(samples).also { it.sort() }
            val p20 = pct(sorted, 0.20f)
            val p90 = pct(sorted, 0.90f)
            println(
                "%-26s %9.5f %9.5f %9.5f %9.5f %8.1f".format(
                    name, p20, pct(sorted, 0.50f), p90, sorted.last(),
                    if (p20 > 0f) p90 / p20 else Float.POSITIVE_INFINITY,
                )
            )
        }
    }

    @Test
    fun `noise alone is not mistaken for speech`() {
        val rows = listOf(
            measure("digital silence", FloatArray(rate * 60)),
            measure("quiet room tone", stationaryNoise(60.0, 0.0004f)),
            measure("loud fan", stationaryNoise(60.0, 0.03f)),
            measure("very loud fan", stationaryNoise(60.0, 0.12f)),
            measure("traffic rumble", rumble(60.0, 0.05f)),
            measure("television next door", modulatedNoise(60.0, 0.05f)),
            measure("loud television", modulatedNoise(60.0, 0.15f)),
        )
        report("noise only (precision -- all must be false)", rows)
        rows.forEach {
            assertFalse(
                "${it.name} would be sent to a recogniser (${it.rawVoicedMs} ms voiced)",
                it.speech,
            )
        }
    }
}
