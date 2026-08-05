package com.mandar.echo.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelManager"

/**
 * Quantised multilingual Whisper models. All three natively support the three
 * languages this app targets — English, Hindi and Marathi.
 *
 * Sizes are the real Content-Length values from HuggingFace, used to verify a
 * download completed rather than trusting a truncated file.
 */
enum class WhisperModel(
    val fileName: String,
    val label: String,
    val bytes: Long,
    val note: String,
) {
    TINY("ggml-tiny-q5_1.bin", "Tiny", 32_152_673L, "Fastest. English only, in practice"),
    BASE("ggml-base-q5_1.bin", "Base", 59_707_000L, "Fast. Recovers ~1 word in 4 of Hindi"),
    SMALL("ggml-small-q5_1.bin", "Small", 190_100_000L, "2x Base on Hindi. ~2.5x slower");

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    companion object {
        fun byFileName(name: String) = entries.firstOrNull { it.fileName == name } ?: BASE
    }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val model: WhisperModel, val bytes: Long, val total: Long) : DownloadState {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Failed(val model: WhisperModel, val message: String) : DownloadState
    data class Done(val model: WhisperModel) : DownloadState
}

class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    @Volatile private var cancelRequested = false

    fun fileFor(model: WhisperModel): File = File(dir, model.fileName)

    /**
     * A model counts as installed only if the file is present and at least 90% of
     * the expected size — this rejects a partial download that was renamed by a
     * crash, which would otherwise fail deep inside whisper with a confusing error.
     */
    fun isInstalled(model: WhisperModel): Boolean {
        val f = fileFor(model)
        return f.exists() && f.length() > model.bytes * 0.9
    }

    fun installed(): List<WhisperModel> = WhisperModel.entries.filter { isInstalled(it) }

    fun resolveInstalledOrNull(preferred: String): File? {
        val model = WhisperModel.byFileName(preferred)
        if (isInstalled(model)) return fileFor(model)
        return installed().firstOrNull()?.let { fileFor(it) }
    }

    fun delete(model: WhisperModel): Boolean = fileFor(model).delete()

    fun cancel() { cancelRequested = true }

    suspend fun download(model: WhisperModel): Result<File> = withContext(Dispatchers.IO) {
        cancelRequested = false
        val target = fileFor(model)
        val part = File(dir, "${model.fileName}.part")

        if (isInstalled(model)) {
            _state.value = DownloadState.Done(model)
            return@withContext Result.success(target)
        }

        try {
            _state.value = DownloadState.Running(model, 0, model.bytes)
            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Echo/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} fetching ${model.fileName}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: model.bytes

            part.delete()
            conn.inputStream.use { input ->
                part.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Long = 0
                    while (true) {
                        if (cancelRequested) throw InterruptedException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        _state.value = DownloadState.Running(model, read, total)
                    }
                    out.flush()
                }
            }

            if (part.length() < total * 0.9) {
                throw IllegalStateException("truncated download (${part.length()} of $total)")
            }
            target.delete()
            if (!part.renameTo(target)) throw IllegalStateException("could not finalise download")

            Log.i(TAG, "installed ${model.fileName} (${target.length()} bytes)")
            _state.value = DownloadState.Done(model)
            Result.success(target)
        } catch (t: Throwable) {
            part.delete()
            Log.e(TAG, "download failed", t)
            _state.value = DownloadState.Failed(model, t.message ?: t::class.java.simpleName)
            Result.failure(t)
        }
    }
}
