// JNI bridge for Echo.
//
// Adapted from whisper.cpp's examples/whisper.android, with three changes that
// the upstream bridge does not support and this app requires:
//
//   1. Selectable language ("auto" | "en" | "hi" | "mr") instead of hardcoded "en".
//   2. Read-back of the language whisper actually detected, per chunk.
//   3. Poll-able progress + a cooperative abort flag, so a 10-minute chunk can be
//      cancelled promptly when the service shuts down.
//
// Symbols resolve against the Kotlin `object com.mandar.echo.stt.WhisperNative`.

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "EchoWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Only one chunk is ever transcribed at a time (the transcriber is a single
// thread by design -- whisper contexts are not safe for concurrent use), so a
// pair of file-scope flags is sufficient and avoids a context->state map.
static volatile int  g_progress = 0;
static volatile bool g_abort    = false;

static void progress_cb(struct whisper_context *ctx, struct whisper_state *state, int progress, void *user_data) {
    UNUSED(ctx); UNUSED(state); UNUSED(user_data);
    g_progress = progress;
}

static bool abort_cb(void *user_data) {
    UNUSED(user_data);
    return g_abort;
}

JNIEXPORT jlong JNICALL
Java_com_mandar_echo_stt_WhisperNative_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;   // CPU only: predictable, and no GPU backend is built in.

    struct whisper_context *context = whisper_init_from_file_with_params(model_path, cparams);
    if (context == NULL) {
        LOGE("Failed to load model from %s", model_path);
    } else {
        LOGI("Loaded model %s", model_path);
    }
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_mandar_echo_stt_WhisperNative_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env); UNUSED(thiz);
    if (context_ptr == 0) return;
    whisper_free((struct whisper_context *) context_ptr);
}

JNIEXPORT jint JNICALL
Java_com_mandar_echo_stt_WhisperNative_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return -1;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    // Held for the whole whisper_full call: params.language borrows this pointer.
    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = (bool) translate;
    params.language         = language;          // "auto" triggers detection
    params.detect_language  = false;
    params.n_threads        = num_threads;
    params.offset_ms        = 0;
    params.no_context       = true;   // stops repetition loops running away across
                                      // the long silent stretches of an ambient chunk
    params.single_segment   = false;
    params.suppress_blank   = true;
    params.no_timestamps    = false;

    params.progress_callback           = progress_cb;
    params.progress_callback_user_data = NULL;
    params.abort_callback              = abort_cb;
    params.abort_callback_user_data    = NULL;

    g_progress = 0;
    g_abort    = false;

    int result = whisper_full(context, params, samples, n_samples);
    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
    }

    (*env)->ReleaseStringUTFChars(env, language_str, language);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mandar_echo_stt_WhisperNative_getProgress(JNIEnv *env, jobject thiz) {
    UNUSED(env); UNUSED(thiz);
    return g_progress;
}

JNIEXPORT void JNICALL
Java_com_mandar_echo_stt_WhisperNative_requestAbort(JNIEnv *env, jobject thiz, jboolean abort) {
    UNUSED(env); UNUSED(thiz);
    g_abort = (bool) abort;
}

JNIEXPORT jint JNICALL
Java_com_mandar_echo_stt_WhisperNative_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env); UNUSED(thiz);
    if (context_ptr == 0) return 0;
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_mandar_echo_stt_WhisperNative_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text == NULL ? "" : text);
}

// whisper timestamps are in centiseconds (1 unit = 10 ms).
JNIEXPORT jlong JNICALL
Java_com_mandar_echo_stt_WhisperNative_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env); UNUSED(thiz);
    return whisper_full_get_segment_t0((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_mandar_echo_stt_WhisperNative_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env); UNUSED(thiz);
    return whisper_full_get_segment_t1((struct whisper_context *) context_ptr, index);
}

JNIEXPORT jstring JNICALL
Java_com_mandar_echo_stt_WhisperNative_getDetectedLanguage(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(thiz);
    if (context_ptr == 0) return (*env)->NewStringUTF(env, "");
    const int lang_id = whisper_full_lang_id((struct whisper_context *) context_ptr);
    const char *lang = whisper_lang_str(lang_id);
    return (*env)->NewStringUTF(env, lang == NULL ? "" : lang);
}

JNIEXPORT jstring JNICALL
Java_com_mandar_echo_stt_WhisperNative_getSystemInfo(JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    char buf[512];
    snprintf(buf, sizeof(buf), "whisper.cpp %s | %s", WHISPER_VERSION, whisper_print_system_info());
    return (*env)->NewStringUTF(env, buf);
}
