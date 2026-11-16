package com.example.g2glasses.pipeline

import android.util.Log

/**
 * Presentation metrics, one tab-separated line per event under the log tag `Metrics`.
 * Collect with: adb logcat -d -s Metrics:V > metrics.txt
 */
object Metrics {
    private const val TAG = "Metrics"

    fun startup(engine: String, language: String, modelUnpackMs: Long, mlkitReadyMs: Long) =
        Log.i(TAG, MetricsFormat.startup(engine, language, modelUnpackMs, mlkitReadyMs))

    fun caption(c: Caption) = Log.i(TAG, MetricsFormat.caption(c))

    fun wavDone(end: StreamEnd) = Log.i(TAG, MetricsFormat.wavDone(end))

    fun result(file: String, source: String, english: String) =
        Log.i(TAG, MetricsFormat.result(file, source, english))
}

/** Line formats for [Metrics]; pure so they can be unit tested. */
object MetricsFormat {
    const val WAV_HEADER_BYTES = 44
    private const val BYTES_PER_SECOND = 16_000 * 2 // 16 kHz, mono, 16-bit

    /** modelUnpackMs is the speech model's load time (unpack + load for Vosk, load for Parakeet). */
    fun startup(engine: String, language: String, modelUnpackMs: Long, mlkitReadyMs: Long) =
        "STARTUP\tengine=$engine\tlang=$language\tmodelUnpackMs=$modelUnpackMs\tmlkitReadyMs=$mlkitReadyMs"

    fun caption(c: Caption) = listOf(
        "CAPTION", c.id, c.translateMs, c.source.length, c.english.length, clean(c.source), clean(c.english),
    ).joinToString("\t")

    fun wavDone(end: StreamEnd) = listOf(
        "WAV_DONE", clean(end.label), fmt(end.audioSeconds), fmt(end.wallSeconds), fmt(rtf(end)),
    ).joinToString("\t")

    fun result(file: String, source: String, english: String) =
        listOf("RESULT", clean(file), clean(source), clean(english)).joinToString("\t")

    /** Seconds of audio in a WAV of [totalBytes], header included. */
    fun audioSeconds(totalBytes: Long): Double =
        (totalBytes - WAV_HEADER_BYTES).coerceAtLeast(0) / BYTES_PER_SECOND.toDouble()

    /** Real-time factor: processing time / audio time (below 1 is faster than real time). */
    fun rtf(end: StreamEnd): Double = if (end.audioSeconds > 0) end.wallSeconds / end.audioSeconds else 0.0

    /** Keeps one event on one line with a fixed column count. */
    fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim()

    private fun fmt(d: Double) = String.format(java.util.Locale.US, "%.3f", d)
}
