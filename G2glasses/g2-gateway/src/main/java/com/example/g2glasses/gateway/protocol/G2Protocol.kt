package com.example.g2glasses.gateway.protocol

import java.util.UUID

/**
 * Even Realities G2 BLE protocol constants.
 *
 * Ported from mentraos/g2/constants.py in gpsnmeajp/men-g2-ble-gateway, itself a port of
 * MentraOS G2.kt (Apache-2.0). Only what text display and diagnostics need is kept.
 */
object G2Protocol {
    val CHAR_WRITE: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5401")
    val CHAR_NOTIFY: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5402")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HEADER_BYTE = 0xAA
    const val SOURCE_PHONE = 0x01
    const val DEST_GLASSES = 0x02
    const val MAX_PACKET_PAYLOAD = 236

    /** ATT MTU to request: 8-byte header + 236 payload + 2 CRC + 3 ATT overhead fits in 247. */
    const val REQUESTED_MTU = 247
}

/** service_id_def.proto */
object ServiceId {
    const val DASHBOARD = 0x01
    const val MENU = 0x03
    const val CALENDAR = 0x04
    const val EVEN_AI = 0x07
    const val G2_SETTING = 0x09
    const val ONBOARDING = 0x10
    const val DEVICE_SETTINGS = 0x80
    const val EVEN_HUB = 0xE0
}

/** EvenHub.proto commands sent by the phone. */
object EvenHubCmd {
    const val CREATE_STARTUP_PAGE = 0
    const val UPDATE_TEXT_DATA = 5
    const val REBUILD_PAGE = 7
    const val SHUTDOWN_PAGE = 9
    const val HEARTBEAT = 12
}

/** dev_config_protocol.proto command ids. */
object DevCfgCmd {
    const val AUTHENTICATION = 4
    const val PIPE_ROLE_CHANGE = 5
    const val BASE_CONN_HEART_BEAT = 14
    const val TIME_SYNC = 128
}

/** g2_setting.proto command ids. */
object G2SettingCmd {
    const val DEVICE_RECEIVE_INFO = 1
    const val DEVICE_RECEIVE_REQUEST = 2
    const val DEVICE_SEND_TO_APP = 3
}

/** OS events the glasses report through EvenHub, mapped to the gesture names MentraOS uses. */
enum class OsEvent(val code: Int, val gesture: String) {
    CLICK(0, "single_tap"),
    SCROLL_TOP(1, "swipe_up"),
    SCROLL_BOTTOM(2, "swipe_down"),
    DOUBLE_CLICK(3, "double_tap"),
    FOREGROUND_ENTER(4, "foreground_enter"),
    FOREGROUND_EXIT(5, "foreground_exit"),
    ABNORMAL_EXIT(6, "abnormal_exit"),
    SYSTEM_EXIT(7, "system_exit");

    companion object {
        fun fromCode(code: Int): OsEvent? = entries.firstOrNull { it.code == code }
    }
}
