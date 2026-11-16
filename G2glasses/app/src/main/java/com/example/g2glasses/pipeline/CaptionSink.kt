package com.example.g2glasses.pipeline

import android.util.Log
import com.example.g2glasses.gateway.GlassesGateway
import com.example.g2glasses.glassesui.CaptionScreen

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
 * module), and hands the page to [gateway]. Call from the main thread.
 */
class GlassesCaptionSink(
    private val gateway: GlassesGateway,
    /** Replaced by the Layout tab when the layout is tuned; applies from the next caption. */
    var screen: CaptionScreen = CaptionScreen(),
    private val keep: Int = 10,
) : CaptionSink {

    /** Off: captions stay on the phone only. */
    var enabled = true

    /** Off: captions laid out past the documented limits are sent anyway (Layout tab testing). */
    var enforceLimits = true

    private val recent = ArrayDeque<CaptionScreen.Line>()

    /** The captions currently kept for the glasses, oldest first. */
    fun recentLines(): List<CaptionScreen.Line> = recent.toList()

    override fun onCaption(c: Caption) {
        recent.addLast(CaptionScreen.Line(english = c.english, source = c.source))
        while (recent.size > keep) recent.removeFirst()
        if (enabled) gateway.show(screen.render(CaptionScreen.State(recent.toList())), enforceLimits)
    }

    fun clear() {
        recent.clear()
        if (enabled) gateway.show(screen.render(CaptionScreen.State(emptyList())), enforceLimits)
    }
}
