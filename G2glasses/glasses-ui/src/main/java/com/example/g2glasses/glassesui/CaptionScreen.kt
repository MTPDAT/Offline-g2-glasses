package com.example.g2glasses.glassesui

/**
 * What the glasses show while captioning. This is the file to edit to change the on-glasses UI:
 * it only turns caption text into a [GlassesPage], with no Bluetooth or speech code involved.
 *
 * Current design: one text box, the newest English captions filling it bottom-up, newest last,
 * pre-wrapped to [Style.maxCols] characters and at most [Style.maxLines] lines. The firmware draws
 * text in its own font, whose metrics aren't documented, so the limits are found by trying them
 * on the glasses (the app's Layout tab), then written back into [Style]'s defaults here.
 */
class CaptionScreen(val style: Style = Style()) : GlassesScreen<CaptionScreen.State> {

    /** Layout knobs. Defaults: the whole display, limits from the team's g2_bridge.py. */
    data class Style(
        /** Characters per line before wrapping. */
        val maxCols: Int = 34,
        /** Lines kept on screen; older lines scroll off the top. */
        val maxLines: Int = 6,
        /** UTF-8 bytes per update, under the firmware's 1000. */
        val maxBytes: Int = GlassesDisplay.SAFE_TEXT_BYTES,
        val x: Int = 0,
        val y: Int = 0,
        val width: Int = GlassesDisplay.WIDTH,
        val height: Int = GlassesDisplay.HEIGHT,
        val padding: Int = 4,
        val borderWidth: Int = 0,
        /** 0..15 grey level. */
        val borderColor: Int = 0,
        val borderRadius: Int = 0,
        /** Show the original-language line above each English line. */
        val showSourceText: Boolean = false,
    ) {
        /** The caption box holding [text]. */
        fun box(text: String) = TextBox(
            id = 1, name = "captions",
            x = x, y = y, width = width, height = height,
            text = text.ifEmpty { " " },  // the firmware rejects empty text; a space shows a blank box
            padding = padding, borderWidth = borderWidth, borderColor = borderColor, borderRadius = borderRadius,
            capturesEvents = true,
        )

        /** This style as Kotlin, for pasting into the defaults above once tuned. */
        fun toKotlin(): String =
            "CaptionScreen.Style(maxCols = $maxCols, maxLines = $maxLines, maxBytes = $maxBytes, " +
                "x = $x, y = $y, width = $width, height = $height, padding = $padding, " +
                "borderWidth = $borderWidth, borderColor = $borderColor, borderRadius = $borderRadius, " +
                "showSourceText = $showSourceText)"
    }

    /** One finished sentence. [source] is the original-language text. */
    data class Line(val english: String, val source: String = "")

    data class State(
        /** Oldest first. Only the newest lines that fit are shown. */
        val lines: List<Line>,
    )

    override fun render(state: State): GlassesPage {
        val window = state.lines.flatMap { line ->
            if (style.showSourceText && line.source.isNotBlank()) listOf(line.source, line.english) else listOf(line.english)
        }
        val text = CaptionLayout.wrap(window, style.maxCols, style.maxLines, style.maxBytes)
        return GlassesPage(listOf(style.box(text)))
    }
}
