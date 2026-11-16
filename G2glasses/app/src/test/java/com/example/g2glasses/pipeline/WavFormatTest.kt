package com.example.g2glasses.pipeline

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFormatTest {

    private fun header(sampleRate: Int = 16_000, channels: Int = 1, bits: Int = 16, format: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort())
        b.putInt(sampleRate).putInt(sampleRate * channels * bits / 8)
        b.putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put("data".toByteArray()).putInt(0)
        return b.array()
    }

    @Test
    fun accepts16kMono16Bit() = assertNull(WavFormat.problem(header()))

    @Test
    fun rejectsWrongRateChannelsBitsOrFormat() {
        assertNotNull(WavFormat.problem(header(sampleRate = 44_100)))
        assertNotNull(WavFormat.problem(header(channels = 2)))
        assertNotNull(WavFormat.problem(header(bits = 24)))
        assertNotNull(WavFormat.problem(header(format = 3)))
    }

    @Test
    fun rejectsShortOrNonWav() {
        assertNotNull(WavFormat.problem(ByteArray(10)))
        assertNotNull(WavFormat.problem(ByteArray(44)))
    }
}
