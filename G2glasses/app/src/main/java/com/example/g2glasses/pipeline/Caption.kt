package com.example.g2glasses.pipeline

/**
 * One finished, translated utterance ("Interface 1").
 *
 * This is the contract the Bluetooth renderer will consume later: one immutable record per
 * sentence, in the order it was spoken.
 */
data class Caption(
    val id: Int,
    val source: String,        // recognized text in the spoken language
    val english: String,       // ML Kit translation (the source itself when the language is English)
    val language: String,      // spoken language code, e.g. "pt"
    val asrFinalAtMs: Long,    // SystemClock.elapsedRealtime() when Vosk delivered the final result
    val translateMs: Long,     // ML Kit time for this sentence
)
