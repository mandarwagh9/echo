package com.mandar.echo.data

import android.content.Context
import android.media.MediaRecorder
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "echo_settings")

enum class SttLanguage(val code: String, val label: String) {
    AUTO("auto", "Auto-detect"),
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी Hindi"),
    MARATHI("mr", "मराठी Marathi");

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Where transcription happens.
 *
 * On-device Whisper is private and works with no network, and it is not good
 * enough at Devanagari: measured against known references, Base recovers 0.23 of
 * Hindi words and 0.00 of Marathi, Small 0.54 and 0.13. IndicConformer-600M --
 * an AI4Bharat model built for Indian languages rather than one that tolerates
 * them -- scores 1.00 on both, and beats Google's Chirp 2 on Marathi. It is far
 * too large for a phone, so it runs on a server the user controls.
 *
 * The choice is a real trade, which is why it is a setting rather than a
 * default: [CLOUD] sends audio off the device.
 */
enum class SttBackend(val label: String, val note: String) {
    ON_DEVICE("On device", "Private, works offline. Weak on Hindi/Marathi"),
    CLOUD("Your server", "Accurate on Hindi/Marathi. Audio leaves the phone"),

    /**
     * Upload to the batch pipeline and let Chirp 3 transcribe it there.
     *
     * The trade against [CLOUD] is latency for everything else: no self-hosted
     * model to keep warm, no 300 s cap to split chunks around, no job to lose,
     * and about a fifth of the cost. The transcript arrives later rather than
     * within a poll, which a journal read in the evening can afford.
     */
    BATCH("Batch (Chirp 3)", "Cheapest and most accurate. Audio leaves the phone"),
}

data class Settings(
    val recordingEnabled: Boolean = false,
    val modelFile: String = "ggml-base-q5_1.bin",
    val sttBackend: SttBackend = SttBackend.ON_DEVICE,
    /** Base URL of a vexyl-stt server, e.g. https://vexyl-stt-xxxx.run.app */
    val sttServerUrl: String = "",
    val sttApiKey: String = "",
    /** Base URL of the echo-upload service, e.g. https://echo-upload-xxxx.run.app */
    val uploadUrl: String = "",
    val uploadKey: String = "",
    val language: SttLanguage = SttLanguage.AUTO,
    /** MediaRecorder.AudioSource. MIC is correct for far-field ambient capture. */
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val summaryHour: Int = 23,
    val summaryMinute: Int = 0,
    val skipSilentChunks: Boolean = true,
    /** Exposed mainly so the pipeline can be exercised end-to-end without waiting 10 minutes. */
    val chunkMinutes: Int = 10,
    val keepAudioAfterTranscription: Boolean = false,
    /**
     * Whether the first-run flow has been completed.
     *
     * Not merely cosmetic. Onboarding is where consent is given, where the
     * microphone and notification permissions are asked for, where the battery
     * optimisation exemption is requested (without which 24/7 capture dies in
     * Doze overnight), and where the speech model is downloaded. Skipping it
     * leaves an app that cannot record and cannot transcribe.
     */
    val onboardingComplete: Boolean = false,
)

class EchoSettings(private val context: Context) {

    private object Keys {
        val RECORDING = booleanPreferencesKey("recording_enabled")
        val MODEL = stringPreferencesKey("model_file")
        val LANGUAGE = stringPreferencesKey("language")
        val SOURCE = intPreferencesKey("audio_source")
        val HOUR = intPreferencesKey("summary_hour")
        val MINUTE = intPreferencesKey("summary_minute")
        val SKIP_SILENT = booleanPreferencesKey("skip_silent")
        val CHUNK_MIN = intPreferencesKey("chunk_minutes")
        val KEEP_AUDIO = booleanPreferencesKey("keep_audio")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val BACKEND = stringPreferencesKey("stt_backend")
        val SERVER_URL = stringPreferencesKey("stt_server_url")
        val API_KEY = stringPreferencesKey("stt_api_key")
        val UPLOAD_URL = stringPreferencesKey("upload_url")
        val UPLOAD_KEY = stringPreferencesKey("upload_key")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            recordingEnabled = p[Keys.RECORDING] ?: false,
            modelFile = p[Keys.MODEL] ?: "ggml-base-q5_1.bin",
            sttBackend = runCatching { SttBackend.valueOf(p[Keys.BACKEND] ?: "") }
                .getOrDefault(SttBackend.ON_DEVICE),
            // Falls back to the build-time default so the server does not have to
            // be typed into a phone by hand. An explicit setting always wins.
            sttServerUrl = (p[Keys.SERVER_URL] ?: com.mandar.echo.BuildConfig.STT_URL)
                .trim().trimEnd('/'),
            sttApiKey = p[Keys.API_KEY] ?: com.mandar.echo.BuildConfig.STT_KEY,
            uploadUrl = (p[Keys.UPLOAD_URL] ?: com.mandar.echo.BuildConfig.UPLOAD_URL)
                .trim().trimEnd('/'),
            uploadKey = p[Keys.UPLOAD_KEY] ?: com.mandar.echo.BuildConfig.UPLOAD_KEY,
            language = SttLanguage.fromCode(p[Keys.LANGUAGE] ?: "auto"),
            audioSource = p[Keys.SOURCE] ?: MediaRecorder.AudioSource.MIC,
            summaryHour = p[Keys.HOUR] ?: 23,
            summaryMinute = p[Keys.MINUTE] ?: 0,
            skipSilentChunks = p[Keys.SKIP_SILENT] ?: true,
            chunkMinutes = p[Keys.CHUNK_MIN] ?: 10,
            keepAudioAfterTranscription = p[Keys.KEEP_AUDIO] ?: false,
            onboardingComplete = p[Keys.ONBOARDED] ?: false,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun setRecordingEnabled(enabled: Boolean) =
        edit { it[Keys.RECORDING] = enabled }

    suspend fun setModel(file: String) = edit { it[Keys.MODEL] = file }

    suspend fun setSttBackend(backend: SttBackend) = edit { it[Keys.BACKEND] = backend.name }

    /**
     * Blank means "use the build-time default", and the build-time default is
     * reached by the key being *absent* — see [flow].
     *
     * Writing "" instead would override `BuildConfig.STT_URL` with an empty
     * string, which reads as "no server configured" with no way back to the
     * built-in one from inside the app.
     */
    suspend fun setSttServer(url: String, apiKey: String) = edit { p ->
        val u = url.trim().trimEnd('/')
        val k = apiKey.trim()
        if (u.isEmpty()) p.remove(Keys.SERVER_URL) else p[Keys.SERVER_URL] = u
        if (k.isEmpty()) p.remove(Keys.API_KEY) else p[Keys.API_KEY] = k
    }

    /** Blank falls back to the build-time default; see [setSttServer]. */
    suspend fun setUploadService(url: String, key: String) = edit { p ->
        val u = url.trim().trimEnd('/')
        val k = key.trim()
        if (u.isEmpty()) p.remove(Keys.UPLOAD_URL) else p[Keys.UPLOAD_URL] = u
        if (k.isEmpty()) p.remove(Keys.UPLOAD_KEY) else p[Keys.UPLOAD_KEY] = k
    }

    suspend fun setLanguage(language: SttLanguage) = edit { it[Keys.LANGUAGE] = language.code }

    suspend fun setAudioSource(source: Int) = edit { it[Keys.SOURCE] = source }

    suspend fun setSummaryTime(hour: Int, minute: Int) = edit {
        it[Keys.HOUR] = hour.coerceIn(0, 23)
        it[Keys.MINUTE] = minute.coerceIn(0, 59)
    }

    suspend fun setSkipSilent(skip: Boolean) = edit { it[Keys.SKIP_SILENT] = skip }

    suspend fun setChunkMinutes(minutes: Int) = edit {
        it[Keys.CHUNK_MIN] = minutes.coerceIn(1, 30)
    }

    suspend fun setKeepAudio(keep: Boolean) = edit { it[Keys.KEEP_AUDIO] = keep }

    suspend fun setOnboardingComplete(done: Boolean) = edit { it[Keys.ONBOARDED] = done }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
