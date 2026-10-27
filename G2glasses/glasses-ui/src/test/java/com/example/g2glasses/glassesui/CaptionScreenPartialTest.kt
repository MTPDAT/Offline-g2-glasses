package com.example.g2glasses.glassesui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScreenPartialTest {

    private fun text(state: CaptionScreen.State, screen: CaptionScreen = CaptionScreen()) =
        screen.render(state).boxes.single().text

    @Test
    fun partial_goesOnTheBottomLineAfterThePrefix() {
        val state = CaptionScreen.State(listOf(CaptionScreen.Line("good morning")), partial = "como vai")
        assertEquals("good morning\n${CaptionScreen.PARTIAL_PREFIX}como vai", text(state))
    }

    @Test
    fun blankPartial_isNotShown() {
        val state = CaptionScreen.State(listOf(CaptionScreen.Line("good morning")), partial = "   ")
        assertEquals("good morning", text(state))
    }

    @Test
    fun partialAlone_onAnEmptyScreen() {
        assertEquals("${CaptionScreen.PARTIAL_PREFIX}olá", text(CaptionScreen.State(emptyList(), partial = "olá")))
    }

    @Test
    fun longPartial_pushesOlderCaptionsOff_andStaysValid() {
        val lines = (1..6).map { CaptionScreen.Line("caption $it") }
        val partial = "uma frase muito longa que ocupa várias linhas no ecrã dos óculos enquanto a pessoa fala"
        val page = CaptionScreen(maxCols = 34, maxLines = 6).render(CaptionScreen.State(lines, partial))
        val shown = page.boxes.single().text.lines()
        assertTrue(shown.size <= 6)
        assertTrue(shown.first { it.startsWith(CaptionScreen.PARTIAL_PREFIX) }.isNotEmpty())
        assertTrue(page.problems().isEmpty())
    }

    @Test
    fun defaultState_hasNoPartial() {
        assertEquals("", CaptionScreen.State(emptyList()).partial)
    }
}
