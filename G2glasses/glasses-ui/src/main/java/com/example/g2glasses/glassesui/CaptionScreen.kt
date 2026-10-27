package com.example.g2glasses.glassesui

/**
 * What the glasses show while captioning. This is the file to edit to change the on-glasses UI:
 * it only turns caption text into a [GlassesPage], with no Bluetooth or speech code involved.
 *
 * Current design: the newest English captions fill the display bottom-up, newest last, wrapped
 * to [maxCols] characters and at most [maxLines] lines (tuned for the G2's default font). While
 * someone is speaking, [State.partial] (the not yet translated words) goes on the bottom line
 * after [PARTIAL_PREFIX], so the wearer sees that speech is being heard.
 */
class CaptionScreen(
    private val maxCols: Int = 34,
    private val maxLines: Int = 6,
    private val showSourceText: Boolean = false,
) : GlassesScreen<CaptionScreen.State> {

    /** One finished sentence. [source] is the original-language text. */
    data class Line(val english: String, val source: String = "")

    data class State(
        /** Oldest first. Only the newest lines that fit are shown. */
        val lines: List<Line>,
        /** Live, still-changing words in the spoken language; blank when nobody is speaking. */
        val partial: String = "",
    )

    override fun render(state: State): GlassesPage {
        val window = state.lines.flatMap { line ->
            if (showSourceText && line.source.isNotBlank()) listOf(line.source, line.english) else listOf(line.english)
        } + listOfNotNull(state.partial.trim().ifEmpty { null }?.let { PARTIAL_PREFIX + it })
        val text = CaptionLayout.wrap(window, maxCols, maxLines, GlassesDisplay.SAFE_TEXT_BYTES)
        // The firmware rejects empty text; a single space shows a blank screen.
        return GlassesPage.fullScreenText(text.ifEmpty { " " })
    }

    companion object {
        const val PARTIAL_PREFIX = "… "
    }
}
