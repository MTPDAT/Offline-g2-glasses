package com.example.g2glasses.pipeline

/** Checks the canonical 44-byte WAV header. Vosk is fed raw PCM, so anything else turns into garbage. */
object WavFormat {

    /** null if [header] is a 16 kHz, mono, 16-bit PCM WAV; otherwise a short reason. */
    fun problem(header: ByteArray): String? {
        if (header.size < MetricsFormat.WAV_HEADER_BYTES) return "file shorter than a WAV header"
        if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") return "not a RIFF/WAVE file"
        val format = u16(header, 20)
        val channels = u16(header, 22)
        val sampleRate = u32(header, 24)
        val bits = u16(header, 34)
        if (format != 1) return "not PCM (format $format)"
        if (channels != 1 || sampleRate != 16_000L || bits != 16) {
            return "needs 16000 Hz mono 16-bit, got $sampleRate Hz, $channels ch, $bits-bit"
        }
        return null
    }

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)
    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, off: Int) = u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)
}
