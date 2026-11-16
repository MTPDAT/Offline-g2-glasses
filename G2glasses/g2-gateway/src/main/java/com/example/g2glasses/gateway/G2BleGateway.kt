package com.example.g2glasses.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.bluetooth.BluetoothDevice
import com.example.g2glasses.gateway.ble.G2Advert
import com.example.g2glasses.gateway.ble.G2Lens
import com.example.g2glasses.gateway.ble.G2Pair
import com.example.g2glasses.gateway.ble.G2Scanner
import com.example.g2glasses.gateway.protocol.FrameAssembler
import com.example.g2glasses.gateway.protocol.G2Messages
import com.example.g2glasses.gateway.protocol.G2Response
import com.example.g2glasses.gateway.protocol.G2Responses
import com.example.g2glasses.gateway.protocol.OsEvent
import com.example.g2glasses.gateway.protocol.Packets
import com.example.g2glasses.gateway.protocol.SendCounters
import com.example.g2glasses.gateway.protocol.ServiceId
import com.example.g2glasses.glassesui.GlassesPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * [GlassesGateway] for Even Realities G2 over Bluetooth LE, ported from the BLE client in
 * gpsnmeajp/men-g2-ble-gateway (mentraos/g2/client.py), which ports MentraOS G2.kt.
 *
 * Kept: scan, connect both lenses, the setup sequence, heartbeats, reconnect, the text display
 * path and touch/battery/firmware events for debugging. Left out: images, microphone audio,
 * menus beyond the one launch entry, and the HTTP, WebSocket and MCP servers.
 *
 * All state lives on one thread ([scope]); BLE callbacks hop onto it.
 */
@SuppressLint("MissingPermission")  // checked in connectOnce() before any Bluetooth call
class G2BleGateway(context: Context) : GlassesGateway {

    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "G2Gateway") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val prefs = this.context.getSharedPreferences("g2_gateway", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(GatewayState(serial = prefs.getString(KEY_SERIAL, "") ?: ""))
    override val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val _log = MutableSharedFlow<GatewayLogEntry>(replay = 100, extraBufferCapacity = 100)
    override val log: SharedFlow<GatewayLogEntry> = _log.asSharedFlow()

    private var left: G2Lens? = null
    private var right: G2Lens? = null
    private var wanted = false
    private var connectJob: Job? = null
    private var loopsJob: Job? = null
    private var generation = 0
    private var lostLink = CompletableDeferred<Unit>()

    private val counters = SendCounters()
    private val assembler = FrameAssembler()
    private val writeMutex = Mutex()

    // Display state
    private var wantedPage: GlassesPage? = null   // what should be on the glasses
    private var wantedEnforceLimits = true
    private var shownPage: GlassesPage? = null    // what was last sent
    private var startupPageCreated = false
    private var dashboardShowing = false
    private val pendingText = LinkedHashMap<Int, ByteArray>()  // box id -> latest update
    private var lastTextMessage: ByteArray? = null
    private var resendsLeft = 0
    private var heartbeats = 0
    private val launchAppId = G2Messages.appIdFor(LAUNCH_PACKAGE)

    // ---------------------------------------------------------------- public API

    override fun connect() {
        scope.launch {
            wanted = true
            if (connectJob?.isActive != true) connectJob = scope.launch { connectLoop() }
        }
    }

    override fun disconnect() {
        scope.launch {
            wanted = false
            connectJob?.cancel()
            teardown()
            _state.update { it.copy(phase = ConnectionPhase.DISCONNECTED, lastError = "") }
            log("Disconnected")
        }
    }

    override fun show(page: GlassesPage, enforceLimits: Boolean) {
        scope.launch {
            wantedEnforceLimits = enforceLimits
            render(page, force = false)
        }
    }

    override fun forgetGlasses() {
        scope.launch {
            prefs.edit().clear().apply()
            _state.update { it.copy(serial = "") }
            log("Forgot saved glasses; the next connect scans again")
        }
    }

    override fun release() {
        wanted = false
        scope.launch { teardown() }.invokeOnCompletion {
            scope.cancel()
            executor.shutdown()
        }
    }

    // ---------------------------------------------------------------- connection

    private suspend fun connectLoop() {
        var trySaved = true
        while (wanted && scope.isActive) {
            try {
                connectOnce(trySaved)
                lostLink.await()
                log("Connection lost")
                trySaved = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                log("Connect failed: $reason")
                _state.update { it.copy(lastError = reason) }
                trySaved = false  // a saved address can go stale; scan next time
            }
            teardown()
            if (!wanted) break
            _state.update { it.copy(phase = ConnectionPhase.DISCONNECTED) }
            log("Retrying in ${RECONNECT_DELAY_MS / 1000} s")
            delay(RECONNECT_DELAY_MS)
        }
    }

    private suspend fun connectOnce(trySaved: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("This phone has no Bluetooth")
        check(hasPermissions(context)) { "Allow the Nearby devices permission to connect to the glasses" }
        check(adapter.isEnabled) { "Turn on Bluetooth" }

        val gen = ++generation
        lostLink = CompletableDeferred()
        val savedLeft = prefs.getString(KEY_LEFT, null)
        val savedRight = prefs.getString(KEY_RIGHT, null)
        val pair = if (trySaved && savedLeft != null && savedRight != null) {
            val serial = prefs.getString(KEY_SERIAL, "") ?: ""
            log("Using saved glasses $serial")
            setPhase(ConnectionPhase.CONNECTING)
            val saved = G2Pair(serial, adapter.getRemoteDevice(savedLeft), adapter.getRemoteDevice(savedRight))
            openLens("L", saved.left, gen)
            delay(50)
            openLens("R", saved.right, gen)
            saved
        } else {
            scanAndConnect(adapter, gen)
        }
        _state.update { it.copy(serial = pair.serial) }
        prefs.edit()
            .putString(KEY_LEFT, pair.left.address).putString(KEY_RIGHT, pair.right.address)
            .putString(KEY_SERIAL, pair.serial)
            .apply()

        setPhase(ConnectionPhase.INITIALIZING)
        runSetupSequence()
        setPhase(ConnectionPhase.READY)
        _state.update { it.copy(lastError = "") }
        log("Ready")
        loopsJob = scope.launch { runLoops() }
        render(wantedPage ?: GlassesPage.fullScreenText(" "), force = true)
    }

    /**
     * Scans and connects to each lens the moment it advertises, as G2.kt does, instead of waiting
     * to see both: a lens may only advertise once its partner is connected, or rarely. The first
     * lens connected fixes the serial, so the other lens must come from the same pair.
     */
    private suspend fun scanAndConnect(adapter: android.bluetooth.BluetoothAdapter, gen: Int): G2Pair {
        setPhase(ConnectionPhase.SCANNING)
        log("Scanning for G2 glasses…")
        val adverts = Channel<G2Advert>(Channel.UNLIMITED)
        val scanner = G2Scanner(adapter)
        val started = scanner.start(
            onLens = { adverts.trySend(it) },
            onLog = { line -> scope.launch { log(line) } },
            onFailed = { code -> adverts.close(IllegalStateException("Bluetooth scan failed (code $code)")) },
        )
        check(started) { "Bluetooth scanner unavailable" }

        var serial: String? = null
        var leftDevice: BluetoothDevice? = null
        var rightDevice: BluetoothDevice? = null
        try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                while (leftDevice == null || rightDevice == null) {
                    val a = adverts.receive()
                    if (serial != null && a.serial != serial) continue
                    if (a.side == "L" && leftDevice == null) {
                        serial = a.serial
                        setPhase(ConnectionPhase.CONNECTING)
                        openLens("L", a.device, gen)
                        leftDevice = a.device
                    } else if (a.side == "R" && rightDevice == null) {
                        serial = a.serial
                        setPhase(ConnectionPhase.CONNECTING)
                        openLens("R", a.device, gen)
                        rightDevice = a.device
                        if (leftDevice == null) log("Right lens connected; still looking for the left lens")
                    }
                }
            }
        } finally {
            scanner.stop()
            adverts.close()
        }
        val l = leftDevice
        val r = rightDevice
        if (l != null && r != null) return G2Pair(serial ?: "", l, r)
        error(
            when {
                l == null && r == null ->
                    "No G2 glasses found. Turn them on, and make sure no other phone or computer is connected to them."
                l == null ->
                    "Found the right lens but not the left. Restart the glasses (tap both touch panels 5 times) " +
                        "and make sure no other phone or computer is paired with the left lens."
                else ->
                    "Found the left lens but not the right. Restart the glasses (tap both touch panels 5 times) " +
                        "and make sure no other phone or computer is paired with the right lens."
            }
        )
    }

    /** Connects one lens and shows it as connected. */
    private suspend fun openLens(side: String, device: BluetoothDevice, gen: Int) {
        val lens = G2Lens(
            side, context, device,
            onNotify = { frame -> scope.launch { if (gen == generation) handleFrame(side, frame) } },
            onDisconnected = { scope.launch { if (gen == generation) lostLink.complete(Unit) } },
        )
        if (side == "L") left = lens else right = lens
        lens.open()
        log("${G2Scanner.sideName(side).replaceFirstChar { it.uppercase() }} lens connected (${device.address})")
        _state.update {
            val connected = LensState(connected = true, address = device.address)
            if (side == "L") it.copy(left = connected) else it.copy(right = connected)
        }
    }

    /** The setup G2.kt and the Python port send after connecting, in the same order. */
    private suspend fun runSetupSequence() {
        assembler.reset()
        startupPageCreated = false
        shownPage = null
        heartbeats = 0
        val localEpochSeconds = System.currentTimeMillis() / 1000 +
            TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

        sendDev(G2Messages.auth(counters.nextMagic()), toLeft = true, toRight = false); delay(STEP_MS)
        sendDev(G2Messages.auth(counters.nextMagic()), toLeft = false, toRight = true); delay(STEP_MS)
        sendDev(G2Messages.pipeRoleChange(counters.nextMagic()), toLeft = false, toRight = true); delay(STEP_MS)
        sendDev(G2Messages.timeSync(counters.nextMagic(), localEpochSeconds), toLeft = false, toRight = true); delay(STEP_MS)
        sendRight(ServiceId.ONBOARDING, G2Messages.skipOnboarding(counters.nextMagic())); delay(STEP_MS)
        sendRight(ServiceId.EVEN_AI, G2Messages.heyEvenOff(counters.nextMagic())); delay(STEP_MS)
        sendRight(ServiceId.G2_SETTING, G2Messages.universeSettings(counters.nextMagic())); delay(STEP_MS)
        sendRight(ServiceId.CALENDAR, G2Messages.calendarDefaults(counters.nextMagic())); delay(STEP_MS)
        sendRight(ServiceId.DASHBOARD, G2Messages.dashboardDefaults(counters.nextMagic())); delay(STEP_MS)
        sendRight(ServiceId.MENU, G2Messages.launchMenu(counters.nextMagic(), LAUNCH_PACKAGE, LAUNCH_NAME)); delay(STEP_MS)
        sendRight(ServiceId.G2_SETTING, G2Messages.requestDeviceInfo(counters.nextMagic()))
    }

    private fun teardown() {
        loopsJob?.cancel(); loopsJob = null
        generation++
        left?.close(); right?.close()
        left = null; right = null
        assembler.reset()
        pendingText.clear()
        lastTextMessage = null
        shownPage = null
        startupPageCreated = false
        _state.update { it.copy(left = LensState(), right = LensState(), battery = null, charging = null, shownText = "") }
    }

    // ---------------------------------------------------------------- background loops

    private suspend fun runLoops() = kotlinx.coroutines.coroutineScope {
        // EvenHub heartbeat keeps our page alive; every 10th also refreshes battery and firmware.
        launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                sendRight(ServiceId.EVEN_HUB, G2Messages.evenHubHeartbeat(counters.nextMagic()))
                if (++heartbeats % 10 == 0) sendRight(ServiceId.G2_SETTING, G2Messages.requestDeviceInfo(counters.nextMagic()))
            }
        }
        // Connection heartbeat, offset half a period from the one above.
        launch {
            delay(HEARTBEAT_MS / 2)
            while (isActive) {
                delay(HEARTBEAT_MS)
                sendDev(G2Messages.baseHeartbeat(counters.nextMagic()), toLeft = false, toRight = true)
            }
        }
        // Text queue: only the newest update per box goes out, at most every 100 ms, sent twice.
        launch {
            while (isActive) {
                delay(TEXT_QUEUE_MS)
                val next = pendingText.entries.firstOrNull()
                if (next != null) {
                    pendingText.remove(next.key)
                    lastTextMessage = next.value
                    resendsLeft = 1
                    sendRight(ServiceId.EVEN_HUB, next.value)
                } else if (resendsLeft > 0) {
                    resendsLeft--
                    lastTextMessage?.let { sendRight(ServiceId.EVEN_HUB, it) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- display

    private suspend fun render(page: GlassesPage, force: Boolean) {
        val problems = page.structureProblems()
        if (problems.isNotEmpty()) {
            log("Page not shown: ${problems.joinToString("; ")}")
            return
        }
        val warnings = page.limitWarnings()
        if (warnings.isNotEmpty()) {
            if (wantedEnforceLimits) {
                log("Page not shown (past documented limits): ${warnings.joinToString("; ")}")
                return
            }
            log("Sending past documented limits: ${warnings.joinToString("; ")}")
        }
        wantedPage = page
        if (!_state.value.ready) return

        val current = shownPage
        if (!force && !dashboardShowing && startupPageCreated && page.hasSameLayoutAs(current)) {
            // Fast path: same boxes, send only the text that changed.
            for (box in page.boxes) {
                if (current?.boxes?.firstOrNull { it.id == box.id }?.text == box.text) continue
                pendingText.remove(box.id)
                pendingText[box.id] = G2Messages.updateText(box.id, box.text)
            }
        } else {
            pendingText.clear()
            val message = if (startupPageCreated) G2Messages.rebuildPage(counters.nextMagic(), page.boxes)
            else G2Messages.createPage(counters.nextMagic(), page.boxes)
            startupPageCreated = true
            dashboardShowing = false
            sendRight(ServiceId.EVEN_HUB, message)
        }
        shownPage = page
        _state.update { it.copy(shownText = page.boxes.first().text) }
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun handleFrame(side: String, frame: ByteArray) {
        val (service, payload) = assembler.accept(frame, side) ?: return
        for (response in G2Responses.parse(service, payload)) {
            when (response) {
                is G2Response.Touch -> {
                    log("Touch on glasses: ${response.event.gesture}")
                    if (response.event == OsEvent.SYSTEM_EXIT || response.event == OsEvent.ABNORMAL_EXIT) {
                        dashboardShowing = true  // the wearer left our page; the next show rebuilds it
                    }
                }
                is G2Response.PageClosed -> {
                    log("Glasses closed the page; recreating it")
                    startupPageCreated = false
                    shownPage = null
                    wantedPage?.let { render(it, force = true) }
                }
                is G2Response.MenuSelected -> if (response.appId == launchAppId) {
                    log("'$LAUNCH_NAME' picked in the glasses menu")
                    startupPageCreated = false
                    wantedPage?.let { render(it, force = true) }
                }
                is G2Response.Authenticated -> {
                    log("${if (side == "L") "Left" else "Right"} lens authentication ${if (response.ok) "OK" else "failed"}")
                    _state.update {
                        if (side == "L") it.copy(left = it.left.copy(authenticated = response.ok))
                        else it.copy(right = it.right.copy(authenticated = response.ok))
                    }
                }
                is G2Response.DeviceInfo -> _state.update {
                    it.copy(
                        battery = response.battery ?: it.battery,
                        charging = response.charging ?: it.charging,
                        firmware = response.rightFirmware ?: response.leftFirmware ?: it.firmware,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /** Services other than DevSettings go to the right lens only, with the reserve flag set. */
    private suspend fun sendRight(service: Int, payload: ByteArray) =
        sendFrames(Packets.build(counters.nextSyncId(), service, payload, reserveFlag = true), toLeft = false, toRight = true)

    private suspend fun sendDev(payload: ByteArray, toLeft: Boolean, toRight: Boolean) =
        sendFrames(Packets.build(counters.nextSyncId(), ServiceId.DEVICE_SETTINGS, payload, reserveFlag = false), toLeft, toRight)

    private suspend fun sendFrames(frames: List<ByteArray>, toLeft: Boolean, toRight: Boolean) {
        writeMutex.withLock {
            frames.forEachIndexed { i, frame ->
                if (toRight) right?.let { if (!it.write(frame)) log("Right lens dropped a frame (Bluetooth busy)") }
                if (toLeft) left?.let { if (!it.write(frame)) log("Left lens dropped a frame (Bluetooth busy)") }
                if (i < frames.size - 1) delay(G2Lens.PACKET_GAP_MS)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setPhase(phase: ConnectionPhase) = _state.update { it.copy(phase = phase) }

    private fun log(message: String) {
        Log.i(TAG, message)
        _log.tryEmit(GatewayLogEntry(SystemClock.elapsedRealtime(), message))
    }

    companion object {
        private const val TAG = "G2Gateway"
        private const val KEY_LEFT = "left_address"
        private const val KEY_RIGHT = "right_address"
        private const val KEY_SERIAL = "serial"

        /** Entry this app adds to the glasses' menu; picking it brings the captions back. */
        private const val LAUNCH_PACKAGE = "com.example.g2glasses.launch"
        private const val LAUNCH_NAME = "Captions"

        /** Long enough for a lens that only advertises after its partner connects. */
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val TEXT_QUEUE_MS = 100L
        private const val STEP_MS = 200L

        /** Runtime permissions the gateway needs before [connect]. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
