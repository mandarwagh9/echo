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

// Language detection restricted to the languages this app supports.
//
// whisper_full's own "auto" runs detection on the first 30 seconds and then
// applies the winner to the entire call -- across 99 languages. On ambient audio
// that regularly lands somewhere absurd (a Marathi speaker was decoded as
// Russian, producing Cyrillic), and one bad guess corrupts a whole chunk. Since
// the app only ever targets three languages, the right move is to score just
// those three and pick the best. Returns one probability per requested code.
JNIEXPORT jfloatArray JNICALL
Java_com_mandar_echo_stt_WhisperNative_detectLanguageProbs(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jobjectArray codes) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const jsize n_codes = (*env)->GetArrayLength(env, codes);
    jfloatArray out = (*env)->NewFloatArray(env, n_codes);
    if (context == NULL || out == NULL || n_codes == 0) return out;

    jfloat *samples = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    // Detection only ever reads the first window; feeding it more is wasted mel.
    const jsize max_samples = WHISPER_SAMPLE_RATE * 30;
    if (n_samples > max_samples) n_samples = max_samples;

    jfloat *result = (jfloat *) calloc(n_codes, sizeof(jfloat));
    if (result == NULL) {
        (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
        return out;
    }

    if (whisper_pcm_to_mel(context, samples, n_samples, num_threads) == 0) {
        const int n_lang = whisper_lang_max_id() + 1;
        float *probs = (float *) calloc(n_lang, sizeof(float));
        if (probs != NULL) {
            if (whisper_lang_auto_detect(context, 0, num_threads, probs) >= 0) {
                for (jsize i = 0; i < n_codes; i++) {
                    jstring code = (jstring) (*env)->GetObjectArrayElement(env, codes, i);
                    const char *c = (*env)->GetStringUTFChars(env, code, NULL);
                    const int id = whisper_lang_id(c);
                    result[i] = (id >= 0 && id < n_lang) ? probs[id] : 0.0f;
                    (*env)->ReleaseStringUTFChars(env, code, c);
                    (*env)->DeleteLocalRef(env, code);
                }
            }
            free(probs);
        }
    }

    (*env)->SetFloatArrayRegion(env, out, 0, n_codes, result);
    free(result);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_mandar_echo_stt_WhisperNative_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str, jboolean translate,
        jstring prompt_str) {
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

    // Anti-hallucination. Ambient capture is the worst case for Whisper: it was
    // trained on audio that always contains speech, so given room tone it invents
    // fluent text and then loops on it ("the security is over" x7). These are the
    // decoder's own defences, and they are set explicitly rather than left to the
    // defaults so that changing them is a deliberate act.
    //
    //  - suppress_nst drops the non-speech token class (music/noise markers and
    //    the subtitle-credit vocabulary Whisper reaches for over silence). It
    //    defaults to FALSE upstream, which is wrong for this application.
    //  - temperature_inc drives the fallback ladder: when a decode looks
    //    degenerate by the two thresholds below, it is retried hotter instead of
    //    being accepted.
    //  - entropy_thold catches low-entropy output, which is exactly what a
    //    repetition loop produces.
    params.suppress_nst     = true;
    params.temperature      = 0.0f;
    params.entropy_thold    = 2.4f;
    params.logprob_thold    = -1.0f;
    params.no_speech_thold  = 0.6f;

    // 0.4, not the 0.2 upstream default. The ladder retries a window at ever
    // higher temperature until the decode stops looking degenerate, so the step
    // size sets the worst case: 0.2 allows six attempts, 0.4 allows three.
    //
    // That worst case is not hypothetical here. On clean English almost no window
    // needs a retry and Base runs at 5.3x realtime; on noisy far-field Marathi
    // nearly every window fails the entropy check, and measured throughput on a
    // real chunk collapsed to 0.4x -- slower than the audio arrives, which for a
    // 24/7 recorder is a queue that never drains.
    //
    // Halving the ladder is affordable because it is no longer the only defence
    // against repetition: WhisperEngine drops repeated segments per chunk, and
    // TextTools.dropRepeats collapses them again when the day is summarised.
    params.temperature_inc  = 0.4f;

    // A short prompt in the target language. Whisper conditions on it, which
    // pins the output script -- without it a Marathi utterance comes back
    // romanised ("ka ni ka paiti na radu dhagda") because Latin is the model's
    // prior. carry_initial_prompt keeps that conditioning on every window rather
    // than letting it decay after the first.
    const char *prompt = NULL;
    if (prompt_str != NULL) {
        prompt = (*env)->GetStringUTFChars(env, prompt_str, NULL);
        if (prompt != NULL && prompt[0] != '\0') {
            params.initial_prompt      = prompt;
            params.carry_initial_prompt = true;
        }
    }

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

    if (prompt != NULL) (*env)->ReleaseStringUTFChars(env, prompt_str, prompt);
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

// Whisper's own estimate that a segment contains no speech at all. The decoder
// only uses this internally when the average log-probability is also poor, so a
// confidently-worded hallucination over room tone still gets emitted. Exposing it
// lets the caller drop those segments itself.
JNIEXPORT jfloat JNICALL
Java_com_mandar_echo_stt_WhisperNative_getSegmentNoSpeechProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(env); UNUSED(thiz);
    if (context_ptr == 0) return 0.0f;
    return whisper_full_get_segment_no_speech_prob((struct whisper_context *) context_ptr, index);
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
