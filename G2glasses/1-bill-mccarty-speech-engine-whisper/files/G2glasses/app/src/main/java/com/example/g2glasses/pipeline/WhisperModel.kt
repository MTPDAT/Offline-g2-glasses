package com.example.g2glasses.pipeline

/**
 * The multilingual whisper.cpp models the Whisper engine can load, smallest first. All are
 * quantized ggml files from huggingface.co/ggerganov/whisper.cpp; scripts/fetch_whisper.* pushes
 * the chosen one to the phone.
 *
 * [minRamGb] is the phone RAM below which the model is not picked automatically: the model's
 * working memory is several times its file size, and ML Kit and the UI need room too.
 */
enum class WhisperModel(val id: String, val fileName: String, val sizeMb: Int, val minRamGb: Int) {
    TINY("tiny", "ggml-tiny-q5_1.bin", 31, 2),
    BASE("base", "ggml-base-q5_1.bin", 57, 3),
    SMALL("small", "ggml-small-q5_1.bin", 181, 4),
    LARGE_V3_TURBO("large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin", 547, 8);

    fun fitsIn(totalRamBytes: Long): Boolean = totalRamBytes >= minRamGb * GIB

    companion object {
        private const val GIB = 1024L * 1024 * 1024

        fun fromId(id: String?): WhisperModel? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * Which model to load, from the files that are on the phone. Pure, so it is unit tested.
 *
 * 1. [preferred] (the id in model.txt, written by fetch_whisper) if its file is there and it fits.
 * 2. Otherwise the largest model present that fits in RAM.
 * 3. Otherwise the smallest model present (it may be slow, but captions beat an error).
 * Returns null when no model file is present.
 */
object WhisperModelSelector {
    /** One line in the model folder naming the model to use, e.g. "small". */
    const val CHOICE_FILE = "model.txt"

    fun select(presentFiles: Set<String>, totalRamBytes: Long, preferred: WhisperModel? = null): WhisperModel? {
        val present = WhisperModel.entries.filter { it.fileName in presentFiles }
        if (present.isEmpty()) return null
        if (preferred != null && preferred in present && preferred.fitsIn(totalRamBytes)) return preferred
        return present.lastOrNull { it.fitsIn(totalRamBytes) } ?: present.first()
    }
}

/** Clean-up of Whisper output before it becomes a final. */
object WhisperText {
    // "[BLANK_AUDIO]", "[MUSIC]", "(applause)", "♪ ... ♪": annotations, not speech.
    private val annotation = Regex("""\[[^\]]*]|\([^)]*\)|♪[^♪]*♪?""")
    private val spaces = Regex("""\s+""")

    fun clean(raw: String): String = raw.replace(annotation, " ").replace(spaces, " ").trim()

    /**
     * The 25 spoken languages ([SourceLanguage.ALL]) are all in whisper.cpp's language table under the
     * same ISO 639-1 codes ML Kit uses, so the code is passed through unchanged.
     */
    val LANGUAGES: Set<String> = setOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
        "lv", "lt", "mt", "pl", "pt", "ro", "ru", "sk", "sl", "es", "sv", "uk",
    )
}
