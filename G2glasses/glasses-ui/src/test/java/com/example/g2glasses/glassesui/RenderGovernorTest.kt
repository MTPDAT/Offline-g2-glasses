package com.example.g2glasses.glassesui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RenderGovernorTest {

    /** Manual clock and timer queue: [advance] runs whatever falls due, in order. */
    private class FakeTime : RenderGovernor.Scheduler {
        var now = 0L
        private val timers = mutableListOf<Triple<Long, Int, () -> Unit>>()
        private var seq = 0

        override fun schedule(delayMs: Long, action: () -> Unit): RenderGovernor.Cancellable {
            val entry = Triple(now + delayMs, seq++, action)
            timers += entry
            return RenderGovernor.Cancellable { timers.remove(entry) }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = timers.filter { it.first <= until }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                timers.remove(next)
                now = next.first
                next.third()
            }
            now = until
        }

        val waiting: Int get() = timers.size
    }

    private lateinit var time: FakeTime
    private lateinit var shown: MutableList<String>
    private lateinit var governor: RenderGovernor

    private fun page(text: String) = GlassesPage.fullScreenText(text)

    @Before
    fun setUp() {
        time = FakeTime()
        shown = mutableListOf()
        governor = RenderGovernor(clock = { time.now }, scheduler = time, minIntervalMs = 300) {
            shown += it.boxes.single().text
        }
    }

    @Test
    fun firstPartial_isSentImmediately() {
        governor.submit(page("a"))
        assertEquals(listOf("a"), shown)
    }

    @Test
    fun identicalPage_isDropped() {
        governor.submit(page("a"))
        time.advance(1_000)
        governor.submit(page("a"))
        governor.submit(page("a"), urgent = true)
        assertEquals(listOf("a"), shown)
        assertEquals(2, governor.stats.unchanged)
        assertEquals(1, governor.stats.sent)
    }

    @Test
    fun partialsInsideTheInterval_areMergedAndOnlyTheNewestIsSent() {
        governor.submit(page("a"))
        time.advance(50); governor.submit(page("ab"))
        time.advance(50); governor.submit(page("abc"))
        time.advance(50); governor.submit(page("abcd"))
        assertEquals(listOf("a"), shown)

        time.advance(150)  // 300 ms after "a"
        assertEquals(listOf("a", "abcd"), shown)
        assertEquals(2, governor.stats.merged)
        assertEquals(0, time.waiting)
    }

    @Test
    fun partialsNeverGoOutFasterThanTheInterval() {
        val sentAt = mutableListOf<Long>()
        val g = RenderGovernor(clock = { time.now }, scheduler = time, minIntervalMs = 300) { sentAt.add(time.now) }
        for (i in 1..100) {   // a partial every 20 ms for 2 s
            g.submit(page("word ".repeat(i)))
            time.advance(20)
        }
        time.advance(1_000)
        assertTrue(sentAt.size in 6..8)
        sentAt.zipWithNext().forEach { (a, b) -> assertTrue("sent ${b - a} ms apart", b - a >= 300) }
    }

    @Test
    fun urgentPage_skipsTheWaitAndReplacesThePendingPartial() {
        governor.submit(page("partial 1"))
        time.advance(100); governor.submit(page("partial 2"))   // waits
        time.advance(10); governor.submit(page("final caption"), urgent = true)
        assertEquals(listOf("partial 1", "final caption"), shown)

        time.advance(1_000)  // the replaced partial must not appear later
        assertEquals(listOf("partial 1", "final caption"), shown)
        assertEquals(1, governor.stats.merged)
    }

    @Test
    fun forgetShown_letsTheSamePageBeSentAgain() {
        governor.submit(page("caption"), urgent = true)
        governor.forgetShown()   // e.g. a test message was drawn over it
        governor.submit(page("caption"), urgent = true)
        assertEquals(listOf("caption", "caption"), shown)
    }

    @Test
    fun cancelPending_dropsTheWaitingPage() {
        governor.submit(page("a"))
        time.advance(10); governor.submit(page("b"))
        governor.cancelPending()
        time.advance(1_000)
        assertEquals(listOf("a"), shown)
    }

    @Test
    fun statsListener_seesEverySubmit() {
        var last = RenderGovernor.Stats()
        governor.onStats = { last = it }
        governor.submit(page("a"))
        governor.submit(page("a"))
        assertEquals(RenderGovernor.Stats(submitted = 2, sent = 1, unchanged = 1, merged = 0), last)
    }
}
