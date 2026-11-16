package com.example.g2glasses.glassesui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesPageTest {

    @Test
    fun fullScreenText_isValid() {
        assertEquals(emptyList<String>(), GlassesPage.fullScreenText("hello").problems())
    }

    @Test
    fun problems_catchFirmwareLimits() {
        val box = GlassesPage.fullScreenText("x").boxes.single()
        assertTrue(GlassesPage(emptyList()).problems().isNotEmpty())
        assertTrue(GlassesPage(listOf(box.copy(capturesEvents = false))).problems().any { "capture" in it })
        assertTrue(GlassesPage(listOf(box, box.copy(name = "b", capturesEvents = false))).problems().any { "ids" in it })
        assertTrue(GlassesPage(listOf(box.copy(width = 600))).problems().any { "outside" in it })
        assertTrue(GlassesPage(listOf(box.copy(text = "é".repeat(501)))).problems().any { "bytes" in it })
        assertTrue(GlassesPage(listOf(box.copy(name = "x".repeat(17)))).problems().any { "name" in it })
    }

    @Test
    fun sameLayout_ignoresText() {
        val a = GlassesPage.fullScreenText("one")
        assertTrue(a.hasSameLayoutAs(GlassesPage.fullScreenText("two")))
        assertFalse(a.hasSameLayoutAs(null))
        assertFalse(a.hasSameLayoutAs(GlassesPage(listOf(a.boxes[0].copy(height = 100)))))
    }
}
