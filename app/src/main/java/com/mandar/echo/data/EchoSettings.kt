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

data class Settings(
    val recordingEnabled: Boolean = false,
    val modelFile: String = "ggml-base-q5_1.bin",
    val language: SttLanguage = SttLanguage.AUTO,
    /** MediaRecorder.AudioSource. MIC is correct for far-field ambient capture. */
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val summaryHour: Int = 23,
    val summaryMinute: Int = 0,
    val skipSilentChunks: Boolean = true,
    /** Exposed mainly so the pipeline can be exercised end-to-end without waiting 10 minutes. */
    val chunkMinutes: Int = 10,
    val keepAudioAfterTranscription: Boolean = false,
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
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            recordingEnabled = p[Keys.RECORDING] ?: false,
            modelFile = p[Keys.MODEL] ?: "ggml-base-q5_1.bin",
            language = SttLanguage.fromCode(p[Keys.LANGUAGE] ?: "auto"),
            audioSource = p[Keys.SOURCE] ?: MediaRecorder.AudioSource.MIC,
            summaryHour = p[Keys.HOUR] ?: 23,
            summaryMinute = p[Keys.MINUTE] ?: 0,
            skipSilentChunks = p[Keys.SKIP_SILENT] ?: true,
            chunkMinutes = p[Keys.CHUNK_MIN] ?: 10,
            keepAudioAfterTranscription = p[Keys.KEEP_AUDIO] ?: false,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun setRecordingEnabled(enabled: Boolean) =
        edit { it[Keys.RECORDING] = enabled }

    suspend fun setModel(file: String) = edit { it[Keys.MODEL] = file }

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

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
