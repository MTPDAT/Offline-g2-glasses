package com.example.g2glasses.gateway.protocol

import com.example.g2glasses.glassesui.TextBox
import kotlin.math.abs

/**
 * Builders for every message the gateway sends, byte-for-byte equal to the Python port
 * (the mentraos/g2/protocol modules) and G2.kt. Each returns the protobuf payload; [Packets] frames it.
 */
object G2Messages {

    // ---- DevSettings (service 0x80) ----

    fun auth(magic: Int) = ProtoWriter()
        .int32(1, DevCfgCmd.AUTHENTICATION).int32(2, magic)
        .message(3, ProtoWriter().bool(1, true).int32(2, 4))
        .toByteArray()

    /** Makes the right lens the command pipe. */
    fun pipeRoleChange(magic: Int) = ProtoWriter()
        .int32(1, DevCfgCmd.PIPE_ROLE_CHANGE).int32(2, magic)
        .message(4, ProtoWriter().int32(1, 1))
        .toByteArray()

    /** [localEpochSeconds] is wall-clock time: UTC epoch plus the local offset (the clock shows it as-is). */
    fun timeSync(magic: Int, localEpochSeconds: Long) = ProtoWriter()
        .int32(1, DevCfgCmd.TIME_SYNC).int32(2, magic)
        .message(128, ProtoWriter().int32(1, localEpochSeconds.toInt()).int32(2, 0))
        .toByteArray()

    fun baseHeartbeat(magic: Int) = ProtoWriter()
        .int32(1, DevCfgCmd.BASE_CONN_HEART_BEAT).int32(2, magic)
        .message(13, ProtoWriter())
        .toByteArray()

    // ---- EvenHub (service 0xE0): the display ----

    private fun textContainer(box: TextBox) = ProtoWriter()
        .int32(1, box.x).int32(2, box.y).int32(3, box.width).int32(4, box.height)
        .int32(5, box.borderWidth).int32(6, box.borderColor).int32(7, box.borderRadius)
        .int32(8, box.padding).int32(9, box.id)
        .string(10, box.name)
        .int32(11, if (box.capturesEvents) 1 else 0)
        .string(12, box.text.ifEmpty { " " })  // the firmware rejects empty text

    private fun pageContainer(boxes: List<TextBox>) = ProtoWriter().apply {
        int32(1, boxes.size)
        for (b in boxes) message(3, textContainer(b))
    }

    private fun evenHub(cmd: Int, magic: Int, subField: Int, sub: ProtoWriter) = ProtoWriter()
        .int32(1, cmd).int32(2, magic).message(subField, sub)
        .toByteArray()

    /** First page after connecting. */
    fun createPage(magic: Int, boxes: List<TextBox>) =
        evenHub(EvenHubCmd.CREATE_STARTUP_PAGE, magic, 3, pageContainer(boxes))

    /** Replaces the page with a new layout. */
    fun rebuildPage(magic: Int, boxes: List<TextBox>) =
        evenHub(EvenHubCmd.REBUILD_PAGE, magic, 7, pageContainer(boxes))

    /** In-place text change for one box (the fast path). The Python port sends MagicRandom 0 here. */
    fun updateText(boxId: Int, text: String): ByteArray {
        val content = text.ifEmpty { " " }
        val sub = ProtoWriter()
            .int32(1, boxId).int32(3, 0)
            .int32(4, content.toByteArray(Charsets.UTF_8).size)
            .string(5, content)
        return evenHub(EvenHubCmd.UPDATE_TEXT_DATA, 0, 9, sub)
    }

    fun shutdownPage() = evenHub(EvenHubCmd.SHUTDOWN_PAGE, 0, 11, ProtoWriter().int32(1, 0))

    fun evenHubHeartbeat(magic: Int) = evenHub(EvenHubCmd.HEARTBEAT, magic, 14, ProtoWriter())

    // ---- Setup messages sent once after connecting, as G2.kt does ----

    fun skipOnboarding(magic: Int) = ProtoWriter()
        .int32(1, 1).int32(2, magic).message(3, ProtoWriter().int32(1, 4))
        .toByteArray()

    /** Turns off the "Hey Even" wake word so it doesn't take over the display. */
    fun heyEvenOff(magic: Int) = ProtoWriter()
        .int32(1, 10).int32(2, magic).message(13, ProtoWriter().int32(1, 0).int32(2, 80))
        .toByteArray()

    fun universeSettings(magic: Int) = ProtoWriter()
        .int32(1, G2SettingCmd.DEVICE_RECEIVE_INFO).int32(2, magic)
        .message(3, ProtoWriter().message(9, ProtoWriter().int32(1, 0).int32(2, 0).int32(3, 1).int32(4, 0).int32(5, 1)))
        .toByteArray()

    /** Asks for battery and firmware versions. */
    fun requestDeviceInfo(magic: Int) = ProtoWriter()
        .int32(1, G2SettingCmd.DEVICE_RECEIVE_REQUEST).int32(2, magic)
        .message(4, ProtoWriter().int32(1, 1))
        .toByteArray()

    fun calendarDefaults(magic: Int) = ProtoWriter()
        .int32(1, 1).int32(2, magic)
        .message(3, ProtoWriter().int32(1, 1).int32(2, 1).int32(3, 5).int32(5, 1))
        .toByteArray()

    fun dashboardDefaults(magic: Int): ByteArray {
        val display = ProtoWriter()
            .int32(1, 4).int32(2, 3).bytes(3, byteArrayOf(1, 2, 3))
            .int32(4, 4).bytes(5, byteArrayOf(1, 3, 2, 2)).int32(6, 1).int32(7, 1)
        return ProtoWriter()
            .int32(1, 2).int32(2, magic).message(4, ProtoWriter().message(2, display))
            .toByteArray()
    }

    /**
     * The glasses' app menu with one entry for this app. Picking it brings our page back after the
     * wearer leaves to the dashboard. Built-in Notification first, then ours, then "---" fillers.
     */
    fun launchMenu(magic: Int, packageName: String, name: String): ByteArray {
        data class Item(val label: String, val appId: Int, val builtIn: Boolean)
        val items = mutableListOf(Item("", 4, true), Item("  " + name.take(15), appIdFor(packageName), false))
        val placeholders = listOf(10535, 10536, 10537, 10538, 10539)
        while (items.size < 5) items += Item("  ---", placeholders[items.size - 1], false)

        val menu = ProtoWriter().int32(1, items.size)
        for (item in items) {
            val w = ProtoWriter()
            if (item.builtIn) w.int32(1, 0).int32(4, item.appId)
            else w.int32(1, 1).int32(2, 1).string(3, item.label).int32(4, item.appId)
            menu.message(2, w)
        }
        return ProtoWriter().int32(1, 0).int32(2, magic).message(3, menu).toByteArray()
    }

    /** Same deterministic id as G2.kt: 10029 + |String.hashCode()| mod 506. */
    fun appIdFor(packageName: String): Int = 10029 + abs(packageName.hashCode()) % 506
}
