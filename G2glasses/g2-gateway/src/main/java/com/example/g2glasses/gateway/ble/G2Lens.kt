package com.example.g2glasses.gateway.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.example.g2glasses.gateway.protocol.G2Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * One G2 lens (left or right) over GATT. Android runs one GATT operation at a time, so every
 * setup step waits for its callback before the next starts, as G2.kt's op queue does.
 *
 * Callbacks arrive on a Binder thread; [onNotify] and [onDisconnected] are called from there and
 * must hand off to the gateway's own thread.
 */
@SuppressLint("MissingPermission")  // G2BleGateway checks BLUETOOTH_CONNECT before creating lenses
internal class G2Lens(
    val side: String,                                  // "L" or "R"
    private val context: Context,
    private val device: BluetoothDevice,
    private val onNotify: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val connected = CompletableDeferred<Unit>()
    private var mtuDone = CompletableDeferred<Int>()
    private val servicesDone = CompletableDeferred<Boolean>()
    private var descriptorDone = CompletableDeferred<Int>()

    @Volatile var isConnected = false
        private set

    val address: String get() = device.address

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = isConnected
                isConnected = false
                connected.completeExceptionally(IllegalStateException("$side lens refused the connection (status $status)"))
                if (wasConnected) onDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mtuDone.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDone.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorDone.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == G2Protocol.CHAR_NOTIFY) onNotify(value)
        }

        @Deprecated("Called instead of the ByteArray overload before Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && c.uuid == G2Protocol.CHAR_NOTIFY) {
                onNotify(c.value ?: return)
            }
        }
    }

    /** Connect, raise the MTU, find the EvenHub characteristics and enable notifications. */
    suspend fun open() {
        // Deprecated in Android 17, but its replacement doesn't exist on the older phones we support.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("$side lens: connectGatt returned null")
        withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
        val g = gatt!!

        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (g.requestMtu(G2Protocol.REQUESTED_MTU)) {
            // Frames are up to 246 bytes; a smaller MTU would cut them. Carry on either way, like G2.kt.
            runCatching { withTimeout(STEP_TIMEOUT_MS) { mtuDone.await() } }
        }

        check(g.discoverServices()) { "$side lens: service discovery did not start" }
        check(withTimeout(STEP_TIMEOUT_MS) { servicesDone.await() }) { "$side lens: service discovery failed" }

        // Find the characteristics in whichever service holds them, as G2.kt does; their service
        // UUID isn't the same on every firmware.
        val chars = g.services.flatMap { it.characteristics }
        writeChar = chars.firstOrNull { it.uuid == G2Protocol.CHAR_WRITE }
        val notify = chars.firstOrNull { it.uuid == G2Protocol.CHAR_NOTIFY }
        if (writeChar == null || notify == null) {
            val offered = g.services.joinToString { svc -> "${svc.uuid} [${svc.characteristics.joinToString { it.uuid.toString() }}]" }
            error("$side lens: G2 characteristics not found. Services: ${offered.ifEmpty { "none" }}")
        }
        enableNotifications(g, notify)
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(G2Protocol.CLIENT_CHARACTERISTIC_CONFIG) ?: return
        descriptorDone = CompletableDeferred()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        check(started) { "$side lens: could not enable notifications" }
        withTimeout(STEP_TIMEOUT_MS) { descriptorDone.await() }
    }

    /**
     * Writes one frame without response. Android rejects a write while the stack is busy, so retry
     * a few times with the 8 ms packet gap, as G2.kt does. Returns false if the frame was dropped.
     */
    suspend fun write(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        repeat(WRITE_ATTEMPTS) { attempt ->
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (ok) return true
            if (attempt < WRITE_ATTEMPTS - 1) delay(PACKET_GAP_MS)
        }
        return false
    }

    fun close() {
        isConnected = false
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        writeChar = null
    }

    companion object {
        const val PACKET_GAP_MS = 8L
        private const val WRITE_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val STEP_TIMEOUT_MS = 5_000L
    }
}
