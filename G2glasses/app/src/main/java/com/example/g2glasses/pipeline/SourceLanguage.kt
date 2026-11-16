package com.example.g2glasses.pipeline

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * A spoken language the app can caption into English.
 *
 * The list is the 25 languages NVIDIA Parakeet TDT 0.6B v3 recognizes, all of which ML Kit can
 * translate to English. Parakeet detects the spoken language by itself; the selection tells ML Kit
 * which language to translate from. English is passed through untranslated.
 */
data class SourceLanguage(
    val code: String,         // ML Kit / ISO 639-1 code, e.g. "pt"
    val englishName: String,  // shown in the picker, e.g. "Portuguese"
    val nativeName: String,   // e.g. "Português"
) {
    val label: String get() = if (nativeName == englishName) englishName else "$englishName · $nativeName"

    companion object {
        val ENGLISH = SourceLanguage(TranslateLanguage.ENGLISH, "English", "English")
        val PORTUGUESE = SourceLanguage(TranslateLanguage.PORTUGUESE, "Portuguese", "Português")

        val ALL: List<SourceLanguage> = listOf(
            SourceLanguage(TranslateLanguage.BULGARIAN, "Bulgarian", "Български"),
            SourceLanguage(TranslateLanguage.CROATIAN, "Croatian", "Hrvatski"),
            SourceLanguage(TranslateLanguage.CZECH, "Czech", "Čeština"),
            SourceLanguage(TranslateLanguage.DANISH, "Danish", "Dansk"),
            SourceLanguage(TranslateLanguage.DUTCH, "Dutch", "Nederlands"),
            ENGLISH,
            SourceLanguage(TranslateLanguage.ESTONIAN, "Estonian", "Eesti"),
            SourceLanguage(TranslateLanguage.FINNISH, "Finnish", "Suomi"),
            SourceLanguage(TranslateLanguage.FRENCH, "French", "Français"),
            SourceLanguage(TranslateLanguage.GERMAN, "German", "Deutsch"),
            SourceLanguage(TranslateLanguage.GREEK, "Greek", "Ελληνικά"),
            SourceLanguage(TranslateLanguage.HUNGARIAN, "Hungarian", "Magyar"),
            SourceLanguage(TranslateLanguage.ITALIAN, "Italian", "Italiano"),
            SourceLanguage(TranslateLanguage.LATVIAN, "Latvian", "Latviešu"),
            SourceLanguage(TranslateLanguage.LITHUANIAN, "Lithuanian", "Lietuvių"),
            SourceLanguage(TranslateLanguage.MALTESE, "Maltese", "Malti"),
            SourceLanguage(TranslateLanguage.POLISH, "Polish", "Polski"),
            PORTUGUESE,
            SourceLanguage(TranslateLanguage.ROMANIAN, "Romanian", "Română"),
            SourceLanguage(TranslateLanguage.RUSSIAN, "Russian", "Русский"),
            SourceLanguage(TranslateLanguage.SLOVAK, "Slovak", "Slovenčina"),
            SourceLanguage(TranslateLanguage.SLOVENIAN, "Slovenian", "Slovenščina"),
            SourceLanguage(TranslateLanguage.SPANISH, "Spanish", "Español"),
            SourceLanguage(TranslateLanguage.SWEDISH, "Swedish", "Svenska"),
            SourceLanguage(TranslateLanguage.UKRAINIAN, "Ukrainian", "Українська"),
        )

        fun fromCode(code: String?): SourceLanguage? = ALL.firstOrNull { it.code == code }
    }
}
