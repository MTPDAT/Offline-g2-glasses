package com.example.g2glasses.glassesui

/**
 * One positioned block of text on the glasses. Coordinates are display pixels on the
 * 576 x 288 canvas ([GlassesDisplay]); the firmware wraps text inside the box, so screens that
 * care about line count wrap it themselves first (see [CaptionLayout]).
 */
data class TextBox(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
    val padding: Int = 4,
    val borderWidth: Int = 0,
    val borderColor: Int = 0,   // 0..15 grey level
    val borderRadius: Int = 0,
    /** Exactly one box per page receives taps and swipes. */
    val capturesEvents: Boolean = false,
) {
    /** Everything but the text: two boxes with the same layout can be updated in place. */
    val layout: TextBox get() = copy(text = "")
}

/**
 * Everything the glasses show at one moment. Screens build pages; the gateway decides how to send
 * them (a cheap text update when only text changed, a page rebuild when the layout changed).
 */
data class GlassesPage(val boxes: List<TextBox>) {

    /** Same boxes in the same places, so only the text needs sending. */
    fun hasSameLayoutAs(other: GlassesPage?): Boolean =
        other != null && boxes.map { it.layout } == other.boxes.map { it.layout }

    /** Everything wrong with the page: [structureProblems] plus [limitWarnings]. Empty when valid. */
    fun problems(): List<String> = structureProblems() + limitWarnings()

    /** Rules the protocol itself depends on; a page breaking these is never sent. */
    fun structureProblems(): List<String> {
        val out = mutableListOf<String>()
        if (boxes.isEmpty()) out += "a page needs at least one text box"
        if (boxes.size > GlassesDisplay.MAX_TEXT_BOXES) out += "at most ${GlassesDisplay.MAX_TEXT_BOXES} text boxes"
        if (boxes.map { it.id }.toSet().size != boxes.size) out += "box ids must be unique"
        if (boxes.map { it.name }.toSet().size != boxes.size) out += "box names must be unique"
        if (boxes.count { it.capturesEvents } != 1) out += "exactly one box must capture events"
        for (b in boxes) {
            if (b.name.length > GlassesDisplay.MAX_NAME_CHARS) out += "'${b.name}': name over ${GlassesDisplay.MAX_NAME_CHARS} characters"
            if (b.width <= 0 || b.height <= 0) out += "'${b.name}': width and height must be positive"
        }
        return out
    }

    /**
     * Places where the page goes past Even's documented limits (display size, text bytes). The
     * firmware may clip, reject or accept these; the Layout tab can send them anyway to find out.
     */
    fun limitWarnings(): List<String> {
        val out = mutableListOf<String>()
        for (b in boxes) {
            if (b.x < 0 || b.y < 0 || b.x + b.width > GlassesDisplay.WIDTH || b.y + b.height > GlassesDisplay.HEIGHT) {
                out += "'${b.name}': outside the ${GlassesDisplay.WIDTH}x${GlassesDisplay.HEIGHT} display"
            }
            val bytes = b.text.toByteArray(Charsets.UTF_8).size
            if (bytes > GlassesDisplay.MAX_TEXT_BYTES) {
                out += "'${b.name}': text is $bytes UTF-8 bytes, over the documented ${GlassesDisplay.MAX_TEXT_BYTES}"
            }
        }
        return out
    }

    companion object {
        /** One box covering the whole display: the gateway's fast text path. */
        fun fullScreenText(text: String) = GlassesPage(
            listOf(
                TextBox(
                    id = 1, name = "text-main",
                    x = 0, y = 0, width = GlassesDisplay.WIDTH, height = GlassesDisplay.HEIGHT,
                    text = text, capturesEvents = true,
                )
            )
        )
    }
}

/** Turns some state into what the glasses should show. Implement one per screen. */
fun interface GlassesScreen<S> {
    fun render(state: S): GlassesPage
}
