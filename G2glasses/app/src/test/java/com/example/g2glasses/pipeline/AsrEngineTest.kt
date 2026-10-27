package com.example.g2glasses.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrEngineTest {

    @Test
    fun vosk_onlyPortuguese() {
        assertTrue(AsrEngine.VOSK.supports(SourceLanguage.PORTUGUESE))
        assertFalse(AsrEngine.VOSK.supports(SourceLanguage.ENGLISH))
    }

    @Test
    fun parakeetAndWhisper_everySelectableLanguage() {
        for (l in SourceLanguage.ALL) {
            assertTrue("Parakeet ${l.code}", AsrEngine.PARAKEET.supports(l))
            assertTrue("Whisper ${l.code}", AsrEngine.WHISPER.supports(l))
        }
    }

    @Test
    fun whisperLanguageList_isExactlyTheSelectableLanguages() {
        // Whisper gets SourceLanguage.code unchanged; a code it doesn't know would make it guess.
        assertEquals(SourceLanguage.ALL.map { it.code }.toSet(), WhisperText.LANGUAGES)
    }

    @Test
    fun everyLanguageHasAtLeastOneEngine() {
        for (l in SourceLanguage.ALL) {
            assertTrue(l.code, AsrEngine.entries.any { it.supports(l) })
        }
    }

    @Test
    fun engineChipsKeepTheirOrder() {
        // MainActivity saves the engine by name and shows the chips in this order.
        assertEquals(listOf("VOSK", "PARAKEET", "WHISPER"), AsrEngine.entries.map { it.name })
    }
}
