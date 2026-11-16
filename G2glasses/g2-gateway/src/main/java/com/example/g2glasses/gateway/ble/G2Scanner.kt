package com.example.g2glasses.gateway.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings

/** Both lenses of one pair of G2 glasses. */
internal data class G2Pair(val serial: String, val left: BluetoothDevice, val right: BluetoothDevice)

/** One lens seen advertising. [side] is "L" or "R". */
internal data class G2Advert(val side: String, val serial: String, val name: String, val device: BluetoothDevice)

/**
 * Reports G2 lenses as they advertise, as G2.kt does: the name contains "G2" and "_L_" or "_R_",
 * and the first 14 bytes of manufacturer data are the pair's serial number. Each lens is reported
 * on its own so the gateway can connect to it at once instead of waiting for the pair.
 */
@SuppressLint("MissingPermission")  // G2BleGateway checks BLUETOOTH_SCAN before scanning
internal class G2Scanner(private val adapter: BluetoothAdapter) {

    private var callback: ScanCallback? = null

    /**
     * Starts scanning. [onLens] gets every usable lens advertisement (repeats included); [onLog]
     * gets one line per G2 advertiser per scan, including ones that can't be used and why.
     * Callbacks run on a Binder thread. Returns false if the scanner is unavailable.
     */
    fun start(onLens: (G2Advert) -> Unit, onLog: (String) -> Unit, onFailed: (Int) -> Unit): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: return false
        val logged = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.uppercase().contains("G2")) return
                val side = sideOf(name)
                val mfg = result.scanRecord?.manufacturerSpecificData
                val serial = if (mfg != null && mfg.size() > 0) serialOf(mfg.valueAt(0)) else null

                val firstSighting = synchronized(logged) { logged.add(result.device.address) }
                if (firstSighting) {
                    val what = when {
                        side == null -> "not a lens (no _L_/_R_ in the name), ignored"
                        serial == null -> "${sideName(side)} lens, but no serial in its advertisement " +
                            "(manufacturer data ${mfg?.valueAt(0)?.size ?: 0} bytes), ignored"
                        else -> "${sideName(side)} lens, serial $serial"
                    }
                    onLog("Seen $name (${result.device.address}, ${result.rssi} dBm): $what")
                }
                if (side != null && serial != null) onLens(G2Advert(side, serial, name, result.device))
            }

            override fun onScanFailed(errorCode: Int) = onFailed(errorCode)
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        callback = cb
        return true
    }

    fun stop() {
        callback?.let { cb -> runCatching { adapter.bluetoothLeScanner?.stopScan(cb) } }
        callback = null
    }

    companion object {
        fun sideOf(name: String): String? {
            val upper = name.uppercase()
            return when {
                "_L_" in upper -> "L"
                "_R_" in upper -> "R"
                else -> null
            }
        }

        fun sideName(side: String) = if (side == "L") "left" else "right"

        /** First 14 bytes of manufacturer data as ASCII, control characters removed. */
        fun serialOf(data: ByteArray?): String? {
            if (data == null || data.size < 14) return null
            return data.copyOfRange(0, 14).toString(Charsets.US_ASCII)
                .filter { it.code in 0x20..0x7E }
                .ifEmpty { null }
        }
    }
}
