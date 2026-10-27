package com.example.g2glasses.pipeline

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.g2glasses.gateway.GlassesGateway
import com.example.g2glasses.glassesui.CaptionScreen
import com.example.g2glasses.glassesui.RenderGovernor

/** Where finished captions go besides the phone screen. MainActivity forwards every [Caption] here. */
fun interface CaptionSink {
    fun onCaption(c: Caption)
}

/** Writes each caption to Logcat (tag CaptionSink). */
class LogcatCaptionSink : CaptionSink {
    override fun onCaption(c: Caption) {
        Log.i("CaptionSink", "#${c.id} ${c.english}")
    }
}

/**
 * Shows captions on the glasses: keeps the last few, lets [screen] lay them out (glasses-ui
 * module), and hands the page to [gateway] through [governor], which drops repeats and limits how
 * often live speech is redrawn. Call from the main thread.
 */
class GlassesCaptionSink(
    private val gateway: GlassesGateway,
    private val screen: CaptionScreen = CaptionScreen(),
    private val keep: Int = 10,
    clock: () -> Long = SystemClock::elapsedRealtime,
    scheduler: RenderGovernor.Scheduler = MainThreadScheduler(),
) : CaptionSink {

    val governor = RenderGovernor(clock = clock, scheduler = scheduler, display = gateway::show)

    /** Off: captions stay on the phone only. */
    var enabled = true

    /** On: the words being spoken show on the bottom line until their caption arrives. */
    var showPartials = false
        set(on) {
            field = on
            if (!on && partial.isNotEmpty()) render(urgent = true)
        }

    private val recent = ArrayDeque<CaptionScreen.Line>()
    private var partial = ""

    override fun onCaption(c: Caption) {
        recent.addLast(CaptionScreen.Line(english = c.english, source = c.source))
        while (recent.size > keep) recent.removeFirst()
        partial = ""
        render(urgent = true)
    }

    /** Live text from the speech engine; redrawn at most a few times a second. */
    fun onPartial(text: String) {
        if (text == partial) return
        partial = text
        if (showPartials) render(urgent = false)
    }

    fun clear() {
        recent.clear()
        partial = ""
        render(urgent = true)
    }

    /** Something else was drawn on the glasses (a test message); the next caption page is sent regardless. */
    fun forgetShown() = governor.forgetShown()

    private fun render(urgent: Boolean) {
        if (!enabled) return
        val live = if (showPartials) partial else ""
        governor.submit(screen.render(CaptionScreen.State(recent.toList(), live)), urgent)
    }
}

/** [RenderGovernor.Scheduler] on the main thread's Handler. */
private class MainThreadScheduler : RenderGovernor.Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMs: Long, action: () -> Unit): RenderGovernor.Cancellable {
        val r = Runnable(action)
        handler.postDelayed(r, delayMs)
        return RenderGovernor.Cancellable { handler.removeCallbacks(r) }
    }
}
