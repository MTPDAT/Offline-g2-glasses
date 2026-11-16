package com.example.g2glasses.glassesui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionScreenTest {

    private fun text(style: CaptionScreen.Style, lines: List<CaptionScreen.Line>) =
        CaptionScreen(style).render(CaptionScreen.State(lines)).boxes.single().text

    @Test
    fun emptyState_rendersABlankButValidPage() {
        val page = CaptionScreen().render(CaptionScreen.State(emptyList()))
        assertEquals(" ", page.boxes.single().text)
        assertTrue(page.problems().isEmpty())
    }

    @Test
    fun defaultStyle_coversTheWholeDisplay() {
        val box = CaptionScreen().render(CaptionScreen.State(emptyList())).boxes.single()
        assertEquals(listOf(0, 0, GlassesDisplay.WIDTH, GlassesDisplay.HEIGHT), listOf(box.x, box.y, box.width, box.height))
    }

    @Test
    fun keepsNewestLinesThatFit() {
        val lines = (1..10).map { CaptionScreen.Line("caption $it") }
        assertEquals("caption 8\ncaption 9\ncaption 10", text(CaptionScreen.Style(maxLines = 3), lines))
    }

    @Test
    fun canShowSourceAboveEnglish() {
        val lines = listOf(CaptionScreen.Line("good morning", "bom dia"))
        assertEquals("bom dia\ngood morning", text(CaptionScreen.Style(showSourceText = true), lines))
    }

    @Test
    fun boxGeometryComesFromStyle() {
        val style = CaptionScreen.Style(x = 20, y = 140, width = 500, height = 120, padding = 8, borderWidth = 2)
        val box = CaptionScreen(style).render(CaptionScreen.State(emptyList())).boxes.single()
        assertEquals(listOf(20, 140, 500, 120, 8, 2), listOf(box.x, box.y, box.width, box.height, box.padding, box.borderWidth))
        assertTrue(CaptionScreen(style).render(CaptionScreen.State(emptyList())).problems().isEmpty())
    }

    @Test
    fun staysUnderTheByteLimit() {
        val lines = listOf(CaptionScreen.Line("Ελληνικά ".repeat(200)))
        assertTrue(text(CaptionScreen.Style(maxCols = 1000, maxBytes = 300), lines).toByteArray(Charsets.UTF_8).size <= 300)
    }

    @Test
    fun rulers() {
        assertEquals("1234567891123456789212", CalibrationText.columnRuler(22))
        assertEquals("Line 1\nLine 2\nLine 3", CalibrationText.lineRuler(3))
    }

    @Test
    fun toKotlin_roundTripsTheValues() {
        assertTrue(CaptionScreen.Style(maxCols = 40).toKotlin().startsWith("CaptionScreen.Style(maxCols = 40, maxLines = 6"))
    }
}
