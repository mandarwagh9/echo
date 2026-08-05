package com.mandar.echo.stt

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "WhisperEngine"

data class WhisperSegment(
    /** Milliseconds from the start of the supplied audio. */
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscriptionResult(
    val segments: List<WhisperSegment>,
    val language: String,
    val elapsedMs: Long,
) {
    val text: String get() = segments.joinToString(" ") { it.text.trim() }.trim()
    val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/**
 * Serialised wrapper around [WhisperNative]. A whisper_context must never be
 * touched from two threads at once, so every entry point holds [mutex].
 */
class WhisperEngine {

    private companion object {
        /**
         * Above this, whisper itself believes the segment has no speech in it.
         * The decoder only acts on that internally when the average log-probability
         * is *also* poor, so a fluent, confident hallucination over room tone still
         * reaches the caller. Dropping it here is the single most effective filter.
         */
        const val NO_SPEECH_MAX = 0.6f

        /** Occurrences of the same normalised text kept per chunk. */
        const val MAX_REPEATS = 2

        /** The only languages this app claims to handle. */
        val SUPPORTED = arrayOf("en", "hi", "mr")

        /**
         * A sentence of ordinary speech in each language, used as the decoder's
         * initial prompt.
         *
         * Whisper conditions on this text, and the strongest thing it conditions
         * is the output script. Without a prompt, Marathi audio comes back
         * romanised, because Latin is the model's prior for a low-resource
         * language; with one, it writes Devanagari. The content is deliberately
         * mundane and domain-free so it biases script and register without
         * putting specific words in the model's mouth.
         */
        val PROMPTS = mapOf(
            "en" to "Okay, so I was thinking about the meeting tomorrow morning. " +
                "Let me know what you think about it.",
            "hi" to "हाँ, तो मैं कल की मीटिंग के बारे में सोच रहा था। " +
                "आप बताइए कि आपको क्या लगता है।",
            "mr" to "हो, मी उद्याच्या मीटिंगबद्दल विचार करत होतो. " +
                "तुम्हाला काय वाटतं ते सांगा.",
        )
    }

    private val mutex = Mutex()
    @Volatile private var ptr: Long = 0
    @Volatile private var loadedPath: String? = null

    val isLoaded: Boolean get() = ptr != 0L
    val modelPath: String? get() = loadedPath

    val threadCount: Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)

    suspend fun load(model: File): Result<Unit> = mutex.withLock {
        if (ptr != 0L && loadedPath == model.absolutePath) return@withLock Result.success(Unit)
        releaseLocked()

        if (!model.exists()) {
            return@withLock Result.failure(IllegalStateException("model missing: ${model.absolutePath}"))
        }
        val p = try {
            WhisperNative.initContext(model.absolutePath)
        } catch (t: Throwable) {
            return@withLock Result.failure(t)
        }
        if (p == 0L) {
            return@withLock Result.failure(IllegalStateException("whisper failed to load ${model.name}"))
        }
        ptr = p
        loadedPath = model.absolutePath
        Log.i(TAG, "loaded ${model.name} with $threadCount threads")
        Result.success(Unit)
    }

    /**
     * @param samples 16 kHz mono, normalised to -1..1
     * @param language "auto", "en", "hi" or "mr"
     */
    suspend fun transcribe(
        samples: FloatArray,
        language: String,
    ): Result<TranscriptionResult> = mutex.withLock {
        val p = ptr
        if (p == 0L) return@withLock Result.failure(IllegalStateException("no model loaded"))
        if (samples.isEmpty()) {
            return@withLock Result.success(TranscriptionResult(emptyList(), language, 0))
        }

        // Resolve "auto" ourselves, over three languages instead of whisper's 99.
        val resolved = if (language == "auto") {
            val probs = runCatching {
                WhisperNative.detectLanguageProbs(p, threadCount, samples, SUPPORTED)
            }.getOrNull()
            val best = probs
                ?.withIndex()
                ?.maxByOrNull { it.value }
                ?.takeIf { it.value > 0f }
                ?.let { SUPPORTED[it.index] }
            if (probs != null) {
                Log.i(
                    TAG,
                    "language scores " + SUPPORTED.indices.joinToString(" ") {
                        "${SUPPORTED[it]}=${"%.2f".format(probs.getOrElse(it) { 0f })}"
                    } + " -> ${best ?: "en"}",
                )
            }
            best ?: "en"
        } else {
            language
        }

        val started = System.currentTimeMillis()
        val rc = try {
            WhisperNative.fullTranscribe(
                p, threadCount, samples, resolved, false, PROMPTS[resolved].orEmpty(),
            )
        } catch (t: Throwable) {
            return@withLock Result.failure(t)
        }
        val elapsed = System.currentTimeMillis() - started
        if (rc != 0) {
            return@withLock Result.failure(IllegalStateException("whisper_full returned $rc"))
        }

        val count = WhisperNative.getTextSegmentCount(p)
        val segments = ArrayList<WhisperSegment>(count)

        // Repetition-loop defence. When the decoder gets stuck it emits the same
        // phrase over and over ("the security is over" x7). Whisper's internal
        // thresholds catch some of it, but not a loop that stays confident, so the
        // surviving output is deduplicated here as well.
        val seen = HashMap<String, Int>()
        var dropped = 0
        var noSpeechDropped = 0

        for (i in 0 until count) {
            val text = WhisperNative.getTextSegment(p, i).trim()
            if (text.isEmpty()) continue

            if (WhisperNative.getSegmentNoSpeechProb(p, i) > NO_SPEECH_MAX) {
                noSpeechDropped++
                continue
            }

            val key = text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
            val times = (seen[key] ?: 0) + 1
            seen[key] = times
            if (times > MAX_REPEATS) {
                dropped++
                continue
            }

            // whisper timestamps are centiseconds
            segments += WhisperSegment(
                startMs = WhisperNative.getTextSegmentT0(p, i) * 10,
                endMs = WhisperNative.getTextSegmentT1(p, i) * 10,
                text = text,
            )
        }

        if (dropped > 0 || noSpeechDropped > 0) {
            Log.i(
                TAG,
                "filtered $noSpeechDropped no-speech and $dropped repeated segments " +
                    "of $count (kept ${segments.size})",
            )
        }

        val detected = WhisperNative.getDetectedLanguage(p).ifBlank { resolved }
        Result.success(TranscriptionResult(segments, detected, elapsed))
    }

    /** 0..100 while a transcription is in flight. */
    fun progress(): Int = runCatching { WhisperNative.getProgress() }.getOrDefault(0)

    fun requestAbort() = runCatching { WhisperNative.requestAbort(true) }

    fun systemInfo(): String = runCatching { WhisperNative.getSystemInfo() }.getOrDefault("unavailable")

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun releaseLocked() {
        if (ptr != 0L) {
            WhisperNative.freeContext(ptr)
            ptr = 0
            loadedPath = null
        }
    }
}
