package com.example.g2glasses.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricsFormatTest {

    @Test
    fun caption_isTabSeparatedWithSevenColumns() {
        val line = MetricsFormat.caption(Caption(3, "bom dia", "good morning", "pt", 1000L, 42L))
        assertEquals("CAPTION\t3\t42\t7\t12\tbom dia\tgood morning", line)
    }

    @Test
    fun caption_keepsTabsAndNewlinesInTextFromBreakingColumns() {
        val line = MetricsFormat.caption(Caption(1, "a\tb\nc", "x\r\ny", "pt", 0L, 1L))
        assertEquals(7, line.split('\t').size)
    }

    @Test
    fun audioSeconds_subtractsHeaderAndUses32000BytesPerSecond() {
        assertEquals(2.0, MetricsFormat.audioSeconds(44L + 64_000L), 1e-9)
        assertEquals(0.0, MetricsFormat.audioSeconds(10L), 1e-9)
    }

    @Test
    fun wavDone_includesRealTimeFactor() {
        val line = MetricsFormat.wavDone(StreamEnd("pt_01.wav", audioSeconds = 4.0, wallSeconds = 1.0))
        assertEquals("WAV_DONE\tpt_01.wav\t4.000\t1.000\t0.250", line)
    }

    @Test
    fun startupAndResultLines() {
        assertEquals(
            "STARTUP\tengine=vosk\tlang=pt\tmodelUnpackMs=12\tmlkitReadyMs=34",
            MetricsFormat.startup("vosk", "pt", 12, 34),
        )
        assertEquals("RESULT\tpt_01.wav\tbom dia\tgood morning", MetricsFormat.result("pt_01.wav", "bom dia", "good morning"))
    }
}
