package com.example.g2glasses.pipeline

/** JNI bindings to whisper.cpp (src/main/cpp/whisper_jni.cpp). Call from one thread per context. */
internal object WhisperNative {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Loads a ggml model file; returns a context handle, or 0 if the file can't be loaded. */
    external fun nativeInit(modelPath: String): Long

    /**
     * Transcribes 16 kHz mono float PCM in [language] (ISO 639-1, "" = auto-detect) and returns the
     * text as UTF-8 bytes.
     */
    external fun nativeTranscribe(context: Long, samples: FloatArray, language: String, threads: Int): ByteArray

    external fun nativeFree(context: Long)

    /** CPU features whisper.cpp was built with, e.g. "NEON = 1 | ARM_FMA = 1 | FP16_VA = 1". */
    external fun nativeSystemInfo(): String

    fun transcribe(context: Long, samples: FloatArray, language: String, threads: Int): String =
        nativeTranscribe(context, samples, language, threads).toString(Charsets.UTF_8)
}
