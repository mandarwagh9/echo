package com.mandar.echo.stt

/**
 * Raw JNI surface for whisper.cpp. Do not call directly — use [WhisperEngine],
 * which serialises access. A [whisper_context] is not safe to use from more
 * than one thread at a time.
 */
internal object WhisperNative {

    init {
        System.loadLibrary("echo_whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)

    /**
     * Returns 0 on success. [language] is "auto", "en", "hi" or "mr".
     * [prompt] conditions the decoder — mainly to pin the output script.
     */
    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        translate: Boolean,
        prompt: String,
        /** Silero VAD model, or "" to fall back to the caller's own gate. */
        vadModelPath: String,
    ): Int

    /**
     * Probability of each of [codes] being the spoken language, scored over the
     * first 30 s. Restricting the choice to the languages the app supports avoids
     * whisper's own "auto" wandering into one of the other 96.
     */
    external fun detectLanguageProbs(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        codes: Array<String>,
    ): FloatArray

    /** 0..100, valid only while [fullTranscribe] is running. */
    external fun getProgress(): Int

    /** Cooperatively cancels an in-flight [fullTranscribe]. */
    external fun requestAbort(abort: Boolean)

    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String

    /** Centiseconds (1 unit = 10 ms) from the start of the supplied audio. */
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    /**
     * 0..1 — whisper's own estimate that the segment contains no speech.
     * High values on fluent-looking text are the signature of a hallucination
     * over silence.
     */
    external fun getSegmentNoSpeechProb(contextPtr: Long, index: Int): Float

    external fun getDetectedLanguage(contextPtr: Long): String
    external fun getSystemInfo(): String
}
