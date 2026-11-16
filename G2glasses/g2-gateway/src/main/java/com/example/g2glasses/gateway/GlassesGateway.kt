package com.example.g2glasses.gateway

import com.example.g2glasses.glassesui.GlassesPage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's link to the glasses. Its only jobs are connecting and putting a [GlassesPage] on
 * the display; what the page looks like is decided in the glasses-ui module.
 */
interface GlassesGateway {
    val state: StateFlow<GatewayState>

    /** Human-readable trace of what happened, for the debug screen and Logcat (tag G2Gateway). */
    val log: SharedFlow<GatewayLogEntry>

    /** Finds the glasses (or uses the last pair), connects both lenses and keeps reconnecting until [disconnect]. */
    fun connect()

    fun disconnect()

    /**
     * Shows [page]; remembered and shown again after a reconnect. A page with structure problems is
     * never sent. With [enforceLimits] off, a page past the documented display and text limits is
     * sent anyway (logged as a warning), to test what the firmware really does.
     */
    fun show(page: GlassesPage, enforceLimits: Boolean = true)

    /** Forgets the saved pair so the next [connect] scans again. */
    fun forgetGlasses()

    fun release()
}

enum class ConnectionPhase { DISCONNECTED, SCANNING, CONNECTING, INITIALIZING, READY }

data class LensState(
    val connected: Boolean = false,
    val authenticated: Boolean = false,
    val address: String = "",
)

data class GatewayState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val left: LensState = LensState(),
    val right: LensState = LensState(),
    val serial: String = "",
    val battery: Int? = null,
    val charging: Boolean? = null,
    val firmware: String = "",
    val lastError: String = "",
    /** Text of the main box currently on the glasses, for the debug screen. */
    val shownText: String = "",
) {
    val ready: Boolean get() = phase == ConnectionPhase.READY
}

data class GatewayLogEntry(val timeMs: Long, val message: String)
