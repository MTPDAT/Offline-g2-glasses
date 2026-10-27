package com.example.g2glasses.glassesui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance budgets for the code that runs on the main thread for every caption and partial.
 * The budgets are ~10x what a laptop or CI runner needs, so they catch regressions (an accidental
 * O(n^2) wrap, re-rendering the whole history) without being flaky on a slow machine.
 */
class RenderPerformanceTest {

    private fun msPerCall(warmup: Int, runs: Int, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val start = System.nanoTime()
        repeat(runs) { block() }
        return (System.nanoTime() - start) / 1e6 / runs
    }

    private val captions = (1..10).map {
        CaptionScreen.Line("This is caption number $it with a typical sentence length", "Esta é a legenda número $it")
    }

    @Test
    fun captionScreenRender_underHalfAMillisecond() {
        val screen = CaptionScreen()
        val ms = msPerCall(warmup = 2_000, runs = 5_000) {
            screen.render(CaptionScreen.State(captions, partial = "e agora mais algumas palavras"))
        }
        assertTrue("CaptionScreen.render took %.3f ms per page (budget 0.5 ms)".format(ms), ms < 0.5)
    }

    @Test
    fun captionLayoutWrap_longHistory_underOneMillisecond() {
        val window = (1..200).map { "caption $it " + "palavra ".repeat(it % 12) }
        val ms = msPerCall(warmup = 500, runs = 2_000) {
            CaptionLayout.wrap(window, 34, 6, GlassesDisplay.SAFE_TEXT_BYTES)
        }
        assertTrue("CaptionLayout.wrap over 200 captions took %.3f ms (budget 1 ms)".format(ms), ms < 1.0)
    }

    @Test
    fun governorSubmit_underTenMicroseconds() {
        val pages = (0 until 1_000).map { GlassesPage.fullScreenText("partial $it") }
        var now = 0L
        val governor = RenderGovernor(
            clock = { now },
            scheduler = { _, _ -> RenderGovernor.Cancellable {} },
            display = {},
        )
        var i = 0
        val ms = msPerCall(warmup = 20_000, runs = 100_000) {
            now += 7
            governor.submit(pages[i++ % pages.size])
        }
        assertTrue("RenderGovernor.submit took %.4f ms (budget 0.01 ms)".format(ms), ms < 0.01)
    }
}
