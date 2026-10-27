// JNI bridge between WhisperNative.kt and whisper.cpp. One whisper_context per loaded model;
// the Kotlin side calls it from a single worker thread.
#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

namespace {

constexpr const char *TAG = "WhisperJni";

void log_to_logcat(enum ggml_log_level level, const char *text, void *) {
    // whisper.cpp is chatty at INFO (every tensor on load); keep warnings and errors only.
    if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, TAG, text);
}

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_g2glasses_pipeline_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring model_path) {
    whisper_log_set(log_to_logcat, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;  // CPU only: ggml's Android GPU backends are not built here
    whisper_context *ctx = whisper_init_from_file_with_params(to_string(env, model_path).c_str(), params);
    return reinterpret_cast<jlong>(ctx);  // 0 when the file is missing or not a ggml model
}

// Returns UTF-8 bytes rather than a jstring: NewStringUTF wants modified UTF-8, and Whisper can
// emit sequences (4-byte characters, a token split mid-character) that CheckJNI aborts on.
JNIEXPORT jbyteArray JNICALL
Java_com_example_g2glasses_pipeline_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewByteArray(0);

    const std::string lang = to_string(env, language);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.translate = false;          // ML Kit translates; Whisper only transcribes
    p.no_context = true;          // each VAD segment is decoded on its own
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;        // drop "(music)", "[applause]" style non-speech tokens

    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return env->NewByteArray(0);
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT void JNICALL
Java_com_example_g2glasses_pipeline_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_g2glasses_pipeline_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
