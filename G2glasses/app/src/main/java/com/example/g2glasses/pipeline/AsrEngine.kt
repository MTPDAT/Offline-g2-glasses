package com.example.g2glasses.pipeline

import android.content.Context

/** The speech engines the app can switch between. */
enum class AsrEngine(val label: String) {
    VOSK("Vosk"),
    PARAKEET("Parakeet");

    /** Vosk ships only the Portuguese model; Parakeet v3 covers every [SourceLanguage]. */
    fun supports(language: SourceLanguage): Boolean = when (this) {
        VOSK -> language == SourceLanguage.PORTUGUESE
        PARAKEET -> true
    }

    fun create(context: Context): SpeechToText = when (this) {
        VOSK -> VoskSpeechToText(context, "model-pt")
        PARAKEET -> ParakeetSpeechToText(context)
    }
}
