package com.example.g2glasses.gateway.protocol

/** What the glasses told us, decoded from a reassembled notification. */
sealed interface G2Response {
    data class Touch(val event: OsEvent) : G2Response
    data class MenuSelected(val appId: Int) : G2Response
    /** The page was closed on the glasses (the wearer exited, or an error); it must be recreated. */
    data object PageClosed : G2Response
    data class Authenticated(val ok: Boolean) : G2Response
    data class DeviceInfo(val battery: Int?, val charging: Boolean?, val leftFirmware: String?, val rightFirmware: String?) : G2Response
}

/** Port of mentraos/g2/events.py, limited to touch, page, auth and battery/firmware messages. */
object G2Responses {

    fun parse(serviceId: Int, payload: ByteArray): List<G2Response> = when (serviceId) {
        ServiceId.EVEN_HUB -> evenHub(payload)
        ServiceId.DEVICE_SETTINGS -> devSettings(payload)
        ServiceId.G2_SETTING -> g2Setting(payload)
        else -> emptyList()
    }

    private fun evenHub(payload: ByteArray): List<G2Response> {
        val f = ProtoReader(payload).fields()
        val out = mutableListOf<G2Response>()
        when (val cmd = f.int(1) ?: return out) {
            2 -> {  // OS_NOTIFY_EVENT_TO_APP
                val dev = ProtoReader(f.bytes(13) ?: return out).fields()
                val sys = dev.bytes(3)
                val code = if (sys != null) ProtoReader(sys).fields().int(1) ?: 0
                else dev.bytes(2)?.let { ProtoReader(it).fields().int(3) }
                OsEvent.fromCode(code ?: return out)?.let { out += G2Response.Touch(it) }
            }
            17 -> f.bytes(20)?.let { ProtoReader(it).fields().int(1) }?.let { out += G2Response.MenuSelected(it) }
            else -> {
                val error9 = listOf(4, 6, 8, 10).any { field -> f.bytes(field)?.let { ProtoReader(it).fields().int(1) } == 9 }
                if (error9 || cmd == 9 || cmd == 10) out += G2Response.PageClosed
            }
        }
        return out
    }

    private fun devSettings(payload: ByteArray): List<G2Response> {
        val f = ProtoReader(payload).fields()
        if (f.int(1) != DevCfgCmd.AUTHENTICATION) return emptyList()
        val ok = f.bytes(3)?.let { ProtoReader(it).fields().int(1) }?.let { it != 0 } ?: false
        return listOf(G2Response.Authenticated(ok))
    }

    private fun g2Setting(payload: ByteArray): List<G2Response> {
        val f = ProtoReader(payload).fields()
        val cmd = f.int(1)
        if (cmd != G2SettingCmd.DEVICE_RECEIVE_REQUEST && cmd != G2SettingCmd.DEVICE_SEND_TO_APP) return emptyList()
        val r = ProtoReader(f.bytes(4) ?: return emptyList()).fields()
        val battery = r.int(12)?.takeIf { it in 0..100 }
        val charging = r.int(13)?.let { it != 0 }
        val left = r.bytes(5)?.toString(Charsets.UTF_8)
        val right = r.bytes(6)?.toString(Charsets.UTF_8)
        if (battery == null && left == null && right == null) return emptyList()
        return listOf(G2Response.DeviceInfo(battery, charging, left, right))
    }
}
