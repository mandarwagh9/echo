package com.mandar.echo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandar.echo.audio.EchoServiceState
import com.mandar.echo.audio.RecordingService
import com.mandar.echo.audio.VoiceActivityDetector
import com.mandar.echo.audio.WavWriter
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.stt.ModelManager
import com.mandar.echo.stt.WhisperModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * The full chain on real hardware, through real air: the phone speaks, its own
 * microphone hears it, [RecordingService] captures it, and the pipeline
 * transcribes it and deletes the audio.
 *
 * Everything else in the suite feeds whisper a file. That leaves the single most
 * failure-prone link untested — `AudioRecord` actually delivering acoustic energy
 * on a real device, under real foreground-service rules. On the emulator this link
 * was verified only against digital silence (`ratio=0.0`), which proves the
 * plumbing runs but not that anything is heard.
 *
 * Playing through the loudspeaker into the same phone's mic is a crude room, but
 * it is a genuine acoustic path, and it is reproducible without a person present.
 */
@RunWith(AndroidJUnit4::class)
class AcousticCaptureInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as EchoApp

    @After
    fun stopRecording() {
        RecordingService.stop(context)
        waitUntil(10_000) { !EchoServiceState.recording.value }
    }

    /** Polls rather than sleeps blindly, so a fast device is not held up. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun grantPermissions() {
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            ui.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
    }

    /**
     * The microphone is a while-in-use permission: a process that is not visible
     * receives silence rather than an error, and on Android 12+ a background start
     * of a microphone foreground service is refused outright. Bringing the real UI
     * up first is both what the user does and what makes the capture legal.
     */
    private fun bringAppToForeground() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        Thread.sleep(3_000)
    }

    private fun synthesise(locale: Locale, text: String): File? {
        val ready = CountDownLatch(1)
        val tts = TextToSpeech(context) { ready.countDown() }
        if (!ready.await(30, TimeUnit.SECONDS)) {
            tts.shutdown()
            return null
        }
        try {
            if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return null
            tts.setLanguage(locale)
            tts.setSpeechRate(0.9f)

            val out = File(context.cacheDir, "spoken_${locale.language}.wav")
            out.delete()
            val done = CountDownLatch(1)
            var failed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = done.countDown()
                @Deprecated("required override")
                override fun onError(utteranceId: String?) { failed = true; done.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    failed = true; done.countDown()
                }
            })
            if (tts.synthesizeToFile(text, android.os.Bundle(), out, "acoustic") !=
                TextToSpeech.SUCCESS
            ) return null
            if (!done.await(60, TimeUnit.SECONDS) || failed) return null
            return out.takeIf { it.exists() && it.length() > 4_000 }
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    /** Plays [file] out of the loudspeaker at full media volume and blocks until done. */
    private fun playAloud(file: File) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }.onFailure {
            android.util.Log.w("EchoTest", "could not raise media volume: ${it.message}")
        }

        val finished = CountDownLatch(1)
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { finished.countDown() }
            prepare()
        }
        try {
            player.start()
            finished.await(60, TimeUnit.SECONDS)
        } finally {
            player.release()
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return sqrt(acc / samples.size).toFloat()
    }

    @Test
    fun phoneHearsItselfAndTranscribesWhatItHeard() {
        grantPermissions()
        runBlocking {
            app.db.chunkDao().deleteAll()
            val models = ModelManager(context)
            if (!models.isInstalled(WhisperModel.BASE)) {
                models.download(WhisperModel.BASE).getOrThrow()
            }
        }

        val spoken = "The quick brown fox jumps over the lazy dog near the river bank."
        val clip = synthesise(Locale.US, spoken)
        org.junit.Assume.assumeTrue("device has no English TTS voice", clip != null)

        bringAppToForeground()

        val startedAt = System.currentTimeMillis()
        RecordingService.start(context)
        assertTrue(
            "RecordingService never reported it was recording — check the foreground-service " +
                "start rules and RECORD_AUDIO on this OS version",
            waitUntil(20_000) { EchoServiceState.recording.value },
        )

        // Track the live RMS the reader thread publishes while sound is in the room.
        val peak = java.util.concurrent.atomic.AtomicReference(0f)
        val sampler = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    peak.updateAndGet { maxOf(it, EchoServiceState.level.value) }
                    Thread.sleep(20)
                }
            } catch (_: InterruptedException) {
                // expected: the test interrupts us once playback is done
            }
        }.apply { isDaemon = true; start() }

        Thread.sleep(800)                 // let capture settle before making noise
        playAloud(clip!!)
        Thread.sleep(1_500)               // let the tail reach the mic and be written
        sampler.interrupt()

        android.util.Log.i("EchoTest", "peak mic level during playback: ${peak.get()}")
        assertTrue(
            "the microphone delivered no acoustic energy (peak RMS ${peak.get()}) — capture ran " +
                "but heard nothing",
            peak.get() > 0.005f,
        )
        assertEquals(
            "audio was dropped during capture",
            0L, EchoServiceState.droppedSamples.value,
        )

        RecordingService.stop(context)
        assertTrue(waitUntil(15_000) { !EchoServiceState.recording.value })

        // Stopping mid-chunk must preserve the partial chunk, not discard it.
        val chunk = runBlocking {
            waitUntil(15_000) {
                runBlocking {
                    app.db.chunkDao().chunksBetween(startedAt - 60_000, System.currentTimeMillis())
                        .any { it.status == ChunkStatus.PENDING && it.filePath != null }
                }
            }
            app.db.chunkDao().chunksBetween(startedAt - 60_000, System.currentTimeMillis())
                .first { it.status == ChunkStatus.PENDING && it.filePath != null }
        }
        android.util.Log.i(
            "EchoTest",
            "captured chunk ${chunk.id}: ${chunk.sampleCount} samples " +
                "(${"%.1f".format(chunk.sampleCount / 16_000.0)} s)",
        )
        assertTrue("captured under a second of audio", chunk.sampleCount > 16_000)

        val wav = File(chunk.filePath!!)
        val samples = WavWriter.readAsFloats(wav)
        val level = rms(samples)
        val vad = VoiceActivityDetector.analyse(samples)
        android.util.Log.i(
            "EchoTest",
            "recorded WAV: rms=$level speechRatio=${vad.speechRatio} hasSpeech=${vad.hasSpeech}",
        )
        assertTrue("the recorded WAV is digital silence — nothing reached disk", level > 0.001f)

        // Now the production path: transcribe it and delete the audio.
        assertTrue("pipeline found no work", runBlocking { app.pipeline.processNext() })

        val after = runBlocking { app.db.chunkDao().byId(chunk.id)!! }
        val text = runBlocking { app.db.segmentDao().forChunk(chunk.id) }
            .joinToString(" ") { it.text.trim() }
        android.util.Log.i("EchoTest", "heard-through-the-air transcript: '$text'")
        android.util.Log.i("EchoTest", "status=${after.status} speechRatio=${after.speechRatio}")

        assertTrue("audio was not deleted after transcription", after.audioDeleted)
        assertTrue("WAV still on disk", !wav.exists())
        assertEquals(null, after.filePath)
        assertTrue(
            "chunk ended in ${after.status} rather than a terminal state",
            after.status == ChunkStatus.DONE || after.status == ChunkStatus.SILENT,
        )

        // Loudspeaker-into-its-own-mic is a hostile recording condition, so the bar
        // is that real words came back — not a verbatim match.
        assertTrue(
            "nothing intelligible came back from the air path: '$text'",
            text.split(Regex("\\s+")).count { it.length > 2 } >= 3,
        )
    }
}
