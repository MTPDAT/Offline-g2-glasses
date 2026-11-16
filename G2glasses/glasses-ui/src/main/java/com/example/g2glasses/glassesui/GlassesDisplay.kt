package com.example.g2glasses.glassesui

/** Physical limits of the Even Realities G2 display, from the Even Hub constraints. */
object GlassesDisplay {
    const val WIDTH = 576
    const val HEIGHT = 288

    /** Text per box, in UTF-8 bytes, that one create or update message may carry. */
    const val MAX_TEXT_BYTES = 1000

    /** What screens should aim for, leaving headroom under [MAX_TEXT_BYTES]. */
    const val SAFE_TEXT_BYTES = 950

    const val MAX_TEXT_BOXES = 8
    const val MAX_NAME_CHARS = 16
}
