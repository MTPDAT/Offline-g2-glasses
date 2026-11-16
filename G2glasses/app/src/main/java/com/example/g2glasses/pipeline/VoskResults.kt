package com.example.g2glasses.pipeline

import org.json.JSONObject

/** Pure helpers for Vosk output, kept free of Android types so they run as JVM unit tests. */
object VoskResults {

    /** Reads [key] ("partial" or "text") from a Vosk JSON hypothesis; "" if missing or malformed. */
    fun parse(json: String?, key: String): String =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrDefault("") }?.trim().orEmpty()

    /** False for empty results and single 1–2 character words, which are usually noise. */
    fun isUseful(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && (t.length >= 3 || t.contains(' '))
    }
}
