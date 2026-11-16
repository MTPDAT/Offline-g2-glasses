package com.example.g2glasses.glassesui

/**
 * Test patterns for measuring what fits on the lens. Send them unwrapped, so the firmware's own
 * wrapping shows where its font runs out of room.
 */
object CalibrationText {

    /**
     * One unbroken row of [length] characters in groups of ten. Each group is "123456789" followed
     * by its group number, so a row ending in "...12345678" after group "3" means 38 characters fit.
     */
    fun columnRuler(length: Int = 80): String =
        (1..length).map { pos -> if (pos % 10 == 0) ('0' + (pos / 10) % 10) else ('0' + pos % 10) }.joinToString("")

    /** Numbered lines; the last fully visible number is how many lines fit. */
    fun lineRuler(lines: Int = 14): String = (1..lines).joinToString("\n") { "Line $it" }

    /** Captions to lay out when there are no real ones yet. */
    val SAMPLE_CAPTIONS = listOf(
        CaptionScreen.Line("Where is the nearest subway station?", "Onde fica a estação de metrô mais próxima?"),
        CaptionScreen.Line("Attention, passengers. The train to São Paulo is late.", "Atenção, passageiros. O trem para São Paulo está atrasado."),
        CaptionScreen.Line("How much does this shirt cost?", "Quanto custa esta camisa?"),
        CaptionScreen.Line("The store will close in 15 minutes.", "A loja vai fechar em 15 minutos."),
    )
}
