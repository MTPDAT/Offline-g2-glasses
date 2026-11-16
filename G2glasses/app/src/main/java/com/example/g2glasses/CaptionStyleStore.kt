package com.example.g2glasses

import android.content.SharedPreferences
import com.example.g2glasses.glassesui.CaptionScreen

/** Saves the Layout tab's tuning between launches. Missing keys fall back to the [CaptionScreen.Style] defaults. */
class CaptionStyleStore(private val prefs: SharedPreferences) {

    fun load(): CaptionScreen.Style {
        val d = CaptionScreen.Style()
        fun int(key: String, default: Int) = prefs.getInt("style_$key", default)
        return CaptionScreen.Style(
            maxCols = int("maxCols", d.maxCols),
            maxLines = int("maxLines", d.maxLines),
            maxBytes = int("maxBytes", d.maxBytes),
            x = int("x", d.x),
            y = int("y", d.y),
            width = int("width", d.width),
            height = int("height", d.height),
            padding = int("padding", d.padding),
            borderWidth = int("borderWidth", d.borderWidth),
            borderColor = int("borderColor", d.borderColor),
            borderRadius = int("borderRadius", d.borderRadius),
            showSourceText = prefs.getBoolean("style_showSourceText", d.showSourceText),
        )
    }

    fun save(s: CaptionScreen.Style) {
        prefs.edit()
            .putInt("style_maxCols", s.maxCols)
            .putInt("style_maxLines", s.maxLines)
            .putInt("style_maxBytes", s.maxBytes)
            .putInt("style_x", s.x)
            .putInt("style_y", s.y)
            .putInt("style_width", s.width)
            .putInt("style_height", s.height)
            .putInt("style_padding", s.padding)
            .putInt("style_borderWidth", s.borderWidth)
            .putInt("style_borderColor", s.borderColor)
            .putInt("style_borderRadius", s.borderRadius)
            .putBoolean("style_showSourceText", s.showSourceText)
            .apply()
    }
}
