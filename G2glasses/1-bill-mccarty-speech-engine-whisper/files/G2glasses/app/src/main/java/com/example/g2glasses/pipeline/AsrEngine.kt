package com.example.g2glasses.pipeline

import android.content.Context

/** The speech engines the app can switch between. */
enum class AsrEngine(val label: String) {
    VOSK("Vosk"),
    PARAKEET("Parakeet"),
    WHISPER("Whisper");

    /** Vosk ships only the Portuguese model; Parakeet v3 and Whisper cover every [SourceLanguage]. */
    fun supports(language: SourceLanguage): Boolean = when (this) {
        VOSK -> language == SourceLanguage.PORTUGUESE
        PARAKEET -> true
        WHISPER -> language.code in WhisperText.LANGUAGES
    }

    /**
     * [language] is the language being spoken. Whisper decodes in it (more accurate than its own
     * detection on short sentences); null lets Whisper detect it. Vosk and Parakeet ignore it.
     */
    fun create(context: Context, language: SourceLanguage? = null): SpeechToText = when (this) {
        VOSK -> VoskSpeechToText(context, "model-pt")
        PARAKEET -> ParakeetSpeechToText(context)
        WHISPER -> WhisperSpeechToText(context, language)
    }
}
