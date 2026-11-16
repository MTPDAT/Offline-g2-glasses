package com.example.g2glasses.gateway.protocol

import java.io.ByteArrayOutputStream

/** The few protobuf field types the G2 messages use, hand-written like G2.kt. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long) {
        var v = value
        // Negative values are written as 64-bit two's complement (10 bytes), as protobuf does.
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun int32(field: Int, value: Int) = apply { varint((field shl 3).toLong()); varint(value.toLong()) }

    fun bool(field: Int, value: Boolean) = int32(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray) = apply {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, sub: ProtoWriter) = bytes(field, sub.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Reads a message into field number -> value (Int for varints, ByteArray for length-delimited). */
class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    private fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun lengthDelimited(): ByteArray? {
        val len = varint()?.toInt() ?: return null
        if (len < 0 || pos + len > data.size) return null
        return data.copyOfRange(pos, pos + len).also { pos += len }
    }

    /** Repeated fields keep their last value, which is all the G2 responses need. */
    fun fields(): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        while (pos < data.size) {
            val tag = varint() ?: break
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out[field] = (varint() ?: break).toInt()  // int32, like Kotlin's Long.toInt()
                2 -> out[field] = lengthDelimited() ?: break
                1 -> pos = minOf(pos + 8, data.size)
                5 -> pos = minOf(pos + 4, data.size)
                else -> break
            }
        }
        return out
    }
}

internal fun Map<Int, Any>.int(field: Int): Int? = this[field] as? Int
internal fun Map<Int, Any>.bytes(field: Int): ByteArray? = this[field] as? ByteArray
