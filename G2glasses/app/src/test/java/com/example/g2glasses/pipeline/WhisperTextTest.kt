package com.example.g2glasses.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhisperTextTest {

    @Test
    fun keepsOrdinarySpeech() {
        assertEquals("Bom dia, como vai?", WhisperText.clean(" Bom dia, como vai?"))
    }

    @Test
    fun removesBracketedAnnotations() {
        assertEquals("", WhisperText.clean("[BLANK_AUDIO]"))
        assertEquals("Olá a todos", WhisperText.clean("(música) Olá [aplausos] a todos"))
        assertEquals("", WhisperText.clean("♪ la la la ♪"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("um dois três", WhisperText.clean("um   dois\n três "))
    }

    @Test
    fun annotationOnly_isNotAUsefulFinal() {
        assertFalse(VoskResults.isUseful(WhisperText.clean(" [MUSIC] ")))
    }
}
