package com.example.g2glasses.gateway.protocol

/** The CRC16 G2.kt appends to every frame, with the same bit order. */
object Crc16 {
    fun of(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = ((crc ushr 8) or ((crc shl 8) and 0xFF00)) xor (b.toInt() and 0xFF)
            crc = crc xor ((crc and 0xFF) ushr 4)
            crc = crc xor ((crc shl 12) and 0xFFFF)
            crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
        }
        return crc and 0xFFFF
    }
}
