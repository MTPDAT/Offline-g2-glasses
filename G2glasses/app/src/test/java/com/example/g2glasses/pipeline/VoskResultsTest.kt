package com.example.g2glasses.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskResultsTest {

    @Test
    fun parse_readsFinalText() {
        assertEquals("onde fica a estação", VoskResults.parse("""{ "text" : "onde fica a estação" }""", "text"))
    }

    @Test
    fun parse_readsPartialAndTrims() {
        assertEquals("bom dia", VoskResults.parse("""{ "partial" : "  bom dia " }""", "partial"))
    }

    @Test
    fun parse_returnsEmptyForMissingKeyNullOrGarbage() {
        assertEquals("", VoskResults.parse("""{ "partial" : "oi" }""", "text"))
        assertEquals("", VoskResults.parse(null, "text"))
        assertEquals("", VoskResults.parse("not json", "text"))
        assertEquals("", VoskResults.parse("", "text"))
    }

    @Test
    fun isUseful_dropsEmptyAndShortSingleWords() {
        assertFalse(VoskResults.isUseful(""))
        assertFalse(VoskResults.isUseful("   "))
        assertFalse(VoskResults.isUseful("a"))
        assertFalse(VoskResults.isUseful("é"))
        assertFalse(VoskResults.isUseful("ok"))
    }

    @Test
    fun isUseful_keepsLongerWordsAndPhrases() {
        assertTrue(VoskResults.isUseful("sim"))
        assertTrue(VoskResults.isUseful("a e"))
        assertTrue(VoskResults.isUseful("quanto custa isso"))
    }
}
