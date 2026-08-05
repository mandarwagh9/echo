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

    /** Returns 0 on success. [language] is "auto", "en", "hi" or "mr". */
    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray,
        language: String,
        translate: Boolean,
    ): Int

    /** 0..100, valid only while [fullTranscribe] is running. */
    external fun getProgress(): Int

    /** Cooperatively cancels an in-flight [fullTranscribe]. */
    external fun requestAbort(abort: Boolean)

    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String

    /** Centiseconds (1 unit = 10 ms) from the start of the supplied audio. */
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    external fun getDetectedLanguage(contextPtr: Long): String
    external fun getSystemInfo(): String
}
