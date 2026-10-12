package com.reduan.smartinfo.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

data class NearbyDevice(
    val key: String,
    val address: String,
    val rssi: Int,
    val proximity: Proximity,
    val lastSeen: Long,
)

/**
 * Finds phones that are running Reduan Smart Info with visibility ON.
 * Ordinary Bluetooth phones without the app are NOT discoverable this way.
 */
@SuppressLint("MissingPermission")
class NearbyScanner(context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    val tracker = ProximityTracker()
    private val addresses = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val refresh = object : Runnable {
        override fun run() {
            publish()
            if (_scanning.value) handler.postDelayed(this, 700)
        }
    }
    private val stopRunnable = Runnable { stop() }

    fun start(durationMs: Long) {
        stop()
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        when {
            adapter == null -> { _error.value = "This phone has no Bluetooth."; return }
            !adapter.isEnabled -> { _error.value = "Bluetooth is off. Turn it on and try again."; return }
            scanner == null -> { _error.value = "Bluetooth LE scanning is unavailable."; return }
        }
        tracker.clear(); addresses.clear(); _devices.value = emptyList(); _error.value = null
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner!!.startScan(filters, settings, callback)
            _scanning.value = true
            handler.post(refresh)
            handler.postDelayed(stopRunnable, durationMs)
        } catch (e: SecurityException) {
            _error.value = "Nearby devices permission was denied."
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable); handler.removeCallbacks(refresh)
        if (_scanning.value) {
            try { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        }
        _scanning.value = false
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        tracker.prune(now).forEach { addresses.remove(it) }
        _devices.value = tracker.ids().mapNotNull { id ->
            val p = tracker.classify(id, now) ?: return@mapNotNull null
            NearbyDevice(id, addresses[id] ?: return@mapNotNull null, tracker.smoothedRssi(id) ?: -100, p, now)
        }.sortedByDescending { it.rssi }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val sid = result.scanRecord?.getServiceData(ParcelUuid(BleProtocol.SERVICE))
            // De-duplicate by the random session id (the MAC address may rotate); fall back to address.
            val key = sid?.let(BleProtocol::toHex) ?: result.device.address
            addresses[key] = result.device.address
            tracker.add(key, result.rssi, System.currentTimeMillis())
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "A scan is already running."
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support this Bluetooth scan."
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android refused the scan. Toggle Bluetooth off/on and retry."
                else -> "Bluetooth scan failed (code $errorCode). Wait a few seconds and retry."
            }
            stop()
        }
    }
}

/** Reads a nearby sender's card over GATT. The caller shows a preview; nothing is saved automatically. */
@SuppressLint("MissingPermission")
class NearbyCardClient(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    suspend fun fetch(address: String, timeoutMs: Long = 20_000): Result<String> = runCatching {
        withTimeout(timeoutMs) { fetchInternal(address) }
    }.recoverCatching { e ->
        throw if (e is kotlinx.coroutines.TimeoutCancellationException)
            Exception("Timed out. Move closer and make sure the other phone is still visible.") else e
    }

    private suspend fun fetchInternal(address: String): String = suspendCancellableCoroutine { cont ->
        val adapter = manager?.adapter ?: run { cont.resumeWith(Result.failure(Exception("No Bluetooth adapter"))); return@suspendCancellableCoroutine }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        val finished = AtomicBoolean(false)
        val assembler = ChunkAssembler()
        var control: BluetoothGattCharacteristic? = null
        var data: BluetoothGattCharacteristic? = null
        var gattRef: BluetoothGatt? = null

        fun finish(gatt: BluetoothGatt?, result: Result<String>) {
            if (!finished.compareAndSet(false, true)) return
            try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
            if (cont.isActive) cont.resumeWith(result)
        }

        fun requestChunk(gatt: BluetoothGatt, index: Int) {
            val c = control ?: return finish(gatt, Result.failure(Exception("Incompatible device")))
            val value = byteArrayOf(index.toByte())
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = value; gatt.writeCharacteristic(c) }
            }
            if (!ok) finish(gatt, Result.failure(Exception("Bluetooth write failed")))
        }

        fun onChunk(gatt: BluetoothGatt, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_READ_NOT_PERMITTED)
                return finish(gatt, Result.failure(Exception("The other phone is no longer sharing.")))
            if (status != BluetoothGatt.GATT_SUCCESS)
                return finish(gatt, Result.failure(Exception("Bluetooth read failed ($status)")))
            try {
                if (assembler.add(value)) finish(gatt, Result.success(assembler.result()))
                else requestChunk(gatt, assembler.expectedIndex)
            } catch (e: IllegalArgumentException) {
                finish(gatt, Result.failure(Exception("Received data was invalid.")))
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) gatt.requestMtu(517)
                else finish(gatt, Result.failure(Exception("Could not connect (status $status).")))
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { gatt.discoverServices() }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val s = gatt.getService(BleProtocol.SERVICE)
                control = s?.getCharacteristic(BleProtocol.CONTROL)
                data = s?.getCharacteristic(BleProtocol.DATA)
                if (control == null || data == null) finish(gatt, Result.failure(Exception("This device is not sharing a card.")))
                else requestChunk(gatt, 0)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return finish(gatt, Result.failure(Exception("The other phone is no longer sharing.")))
                val d = data ?: return
                if (!gatt.readCharacteristic(d)) finish(gatt, Result.failure(Exception("Bluetooth read failed")))
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                onChunk(gatt, value, status)
            }

            @Deprecated("Used below Android 13")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < 33) onChunk(gatt, characteristic.value ?: ByteArray(0), status)
            }
        }
        try {
            gattRef = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            finish(null, Result.failure(Exception("Bluetooth permission was denied.")))
        }
        cont.invokeOnCancellation { finish(gattRef, Result.failure(Exception("Cancelled"))) }
    }
}
