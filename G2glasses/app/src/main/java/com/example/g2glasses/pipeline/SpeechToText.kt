package com.example.g2glasses.pipeline

import kotlinx.coroutines.flow.SharedFlow
import java.io.InputStream

/**
 * Speech recognizer behind a small interface so the engine can be swapped later
 * (sherpa-onnx / Parakeet is planned). [VoskSpeechToText] is the current implementation.
 */
interface SpeechToText {
    /** Short engine id for logs and metrics, e.g. "vosk". */
    val name: String

    /** Live, still-changing text while someone is speaking. */
    val partials: SharedFlow<String>

    /** One finished utterance per pause, already filtered for noise. */
    val finals: SharedFlow<String>

    /** Emitted once a WAV stream from [startFromWav] has been fully processed (after its last final). */
    val streamEnded: SharedFlow<StreamEnd>

    suspend fun prepare()
    fun startMic()

    /** Feeds a 16 kHz, mono, 16-bit PCM WAV through the same recognizer as the mic. */
    fun startFromWav(wav: InputStream, label: String = "wav")
    fun stop()
    fun release()
}

/** Timing for one WAV run. */
data class StreamEnd(val label: String, val audioSeconds: Double, val wallSeconds: Double)
