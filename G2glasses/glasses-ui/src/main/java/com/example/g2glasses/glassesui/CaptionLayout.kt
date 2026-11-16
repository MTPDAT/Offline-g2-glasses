package com.example.g2glasses.glassesui

/**
 * Lays out the rolling caption window for the G2 display: newest last, word-wrapped to a fixed
 * column count, at most [maxLines] lines and at most [byteCap] UTF-8 bytes.
 *
 * Kotlin port of `wrap()` in whisper.cpp/g2_bridge.py (LinYu-Ch/offline_g2_language_server).
 * We wrap ourselves because the glasses' font metrics are undocumented: letting the firmware
 * reflow makes the line count unpredictable, and the rolling window depends on it.
 * Used by [CaptionScreen].
 */
object CaptionLayout {

    /** The gateway caps in-place text at 1000 bytes; keep some headroom. */
    const val BYTE_CAP = 950

    fun wrap(window: List<String>, maxCols: Int, maxLines: Int, byteCap: Int = BYTE_CAP): String {
        var lines = emptyList<String>()
        for (text in window.asReversed()) {
            val w = wrapText(text, maxCols).ifEmpty { listOf("") }
            if (lines.isNotEmpty() && lines.size + w.size > maxLines) break
            lines = w + lines
            if (lines.size >= maxLines) {
                lines = lines.takeLast(maxLines)
                break
            }
        }

        var out = lines.joinToString("\n")
        // Byte cap, not character cap: accented, CJK and emoji text is 2–4 bytes per character.
        // Drop whole older lines first, then leading code points (never half of one).
        while (utf8Length(out) > byteCap && '\n' in out) out = out.substringAfter('\n')
        return trimFrontToBytes(out, byteCap)
    }

    /**
     * Greedy word wrap matching Python's `textwrap.wrap(text, width)`: lengths in code points,
     * runs of spaces inside a line kept, whitespace at line breaks dropped, words longer than a
     * line broken (filling the rest of the current line first).
     * Differences: no extra break points after hyphens, and a tab counts as one space.
     */
    internal fun wrapText(text: String, width: Int): List<String> {
        require(width > 0) { "width must be positive" }
        // Python's reversed chunk stack; we pop from the end.
        val chunks = splitChunks(text).asReversed().toMutableList()
        val lines = mutableListOf<String>()

        while (chunks.isNotEmpty()) {
            val cur = mutableListOf<String>()
            var curLen = 0
            // Leading whitespace is dropped on every line but the first.
            if (lines.isNotEmpty() && chunks.last().isBlank()) chunks.removeAt(chunks.lastIndex)

            while (chunks.isNotEmpty()) {
                val len = cpLen(chunks.last())
                if (curLen + len <= width) {
                    cur += chunks.removeAt(chunks.lastIndex)
                    curLen += len
                } else {
                    break
                }
            }
            // A chunk too long for any line: put as much as fits on this one.
            if (chunks.isNotEmpty() && cpLen(chunks.last()) > width) {
                val chunk = chunks.last()
                val end = width - curLen
                cur += takeCp(chunk, end)
                chunks[chunks.lastIndex] = dropCp(chunk, end)
            }
            if (cur.isNotEmpty() && cur.last().isBlank()) cur.removeAt(cur.lastIndex)
            if (cur.isNotEmpty()) lines += cur.joinToString("")
        }
        return lines
    }

    /** Alternating runs of words and spaces; every whitespace character becomes a space. */
    private fun splitChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        var inSpace = false
        for (ch in text) {
            val space = ch.isWhitespace()
            if (sb.isNotEmpty() && space != inSpace) {
                chunks += sb.toString()
                sb.clear()
            }
            inSpace = space
            sb.append(if (space) ' ' else ch)
        }
        if (sb.isNotEmpty()) chunks += sb.toString()
        return chunks
    }

    private fun trimFrontToBytes(s: String, byteCap: Int): String {
        var bytes = utf8Length(s)
        var i = 0
        while (bytes > byteCap && i < s.length) {
            val cp = s.codePointAt(i)
            bytes -= utf8Length(cp)
            i += Character.charCount(cp)
        }
        return s.substring(i)
    }

    internal fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return n
    }

    private fun utf8Length(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun cpLen(s: String) = s.codePointCount(0, s.length)
    private fun takeCp(s: String, n: Int) = s.substring(0, s.offsetByCodePoints(0, n.coerceIn(0, cpLen(s))))
    private fun dropCp(s: String, n: Int) = s.substring(s.offsetByCodePoints(0, n.coerceIn(0, cpLen(s))))
}
