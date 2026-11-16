package com.example.g2glasses.gateway.protocol

/**
 * Splits a protobuf payload into G2 BLE frames and joins incoming frames back together.
 *
 * Frame: AA | dest<<4|src | syncId | payloadLen | total | index | serviceId | status | chunk [| CRC16 LE]
 * The CRC of the whole payload rides on the last frame; a payload that exactly fills the last
 * frame gets an extra CRC-only frame.
 */
object Packets {
    fun build(syncId: Int, serviceId: Int, payload: ByteArray, reserveFlag: Boolean): List<ByteArray> {
        val chunks = payload.toList().chunked(G2Protocol.MAX_PACKET_PAYLOAD).map { it.toByteArray() }.toMutableList()
        if (chunks.isEmpty()) chunks += ByteArray(0)
        if (chunks.last().size == G2Protocol.MAX_PACKET_PAYLOAD) chunks += ByteArray(0)

        val crc = Crc16.of(payload)
        val total = chunks.size
        return chunks.mapIndexed { i, chunk ->
            val last = i == total - 1
            val frame = ByteArray(8 + chunk.size + if (last) 2 else 0)
            frame[0] = G2Protocol.HEADER_BYTE.toByte()
            frame[1] = ((G2Protocol.DEST_GLASSES shl 4) or G2Protocol.SOURCE_PHONE).toByte()
            frame[2] = syncId.toByte()
            frame[3] = (chunk.size + if (last) 2 else 0).toByte()
            frame[4] = total.toByte()
            frame[5] = (i + 1).toByte()
            frame[6] = serviceId.toByte()
            frame[7] = (if (reserveFlag) 0x20 else 0x00).toByte()
            chunk.copyInto(frame, 8)
            if (last) {
                frame[8 + chunk.size] = (crc and 0xFF).toByte()
                frame[9 + chunk.size] = (crc ushr 8).toByte()
            }
            frame
        }
    }
}

/** Rolling one-byte counters for frame syncId and the protobuf MagicRandom field. */
class SendCounters {
    private var sync = 0
    private var magic = 0

    @Synchronized fun nextSyncId(): Int = sync.also { sync = (sync + 1) and 0xFF }
    @Synchronized fun nextMagic(): Int = magic.also { magic = (magic + 1) and 0xFF }
}

/** Reassembles multi-frame notifications per lens. The CRC is not checked, as in G2.kt. */
class FrameAssembler {
    private val partials = HashMap<String, java.io.ByteArrayOutputStream>()

    fun reset() = partials.clear()

    /** Returns (serviceId, payload) when [raw] completes a message, else null. */
    fun accept(raw: ByteArray, sourceKey: String): Pair<Int, ByteArray>? {
        if (raw.size < 8 || (raw[0].toInt() and 0xFF) != G2Protocol.HEADER_BYTE) return null
        val payloadLen = raw[3].toInt() and 0xFF
        if (raw.size < payloadLen + 8) return null
        val total = raw[4].toInt() and 0xFF
        val index = raw[5].toInt() and 0xFF
        val serviceId = raw[6].toInt() and 0xFF
        val resultCode = ((raw[7].toInt() and 0xFF) shr 1) and 0x0F
        if (resultCode != 0) return null

        val last = index == total
        val payload = raw.copyOfRange(8, 8 + payloadLen - if (last) 2 else 0)
        val key = "$sourceKey-$serviceId-${raw[2].toInt() and 0xFF}"

        if (index > 1) {
            partials[key]?.write(payload) ?: return null
        } else if (total > 1) {
            partials[key] = java.io.ByteArrayOutputStream().apply { write(payload) }
        }
        if (!last) return null
        val joined = partials.remove(key)?.toByteArray() ?: payload
        return serviceId to joined
    }
}
