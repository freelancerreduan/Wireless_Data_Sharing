package com.reduan.smartinfo.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.reduan.smartinfo.security.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes MY card readable by nearby phones that run this app — only while the user has
 * explicitly switched visibility on, and only for a limited time.
 *  - The advertisement contains the service UUID and a random 4-byte session id. No name, no contact data.
 *  - The GATT DATA characteristic serves the vCard of the fields the user selected (no photo),
 *    always built from the latest saved profile ([updateCard]).
 * Permission checks happen in the UI before start(); SecurityException is still caught here.
 */
@SuppressLint("MissingPermission")
class NearbyCardServer(private val context: Context) {

    data class State(
        val visible: Boolean = false,
        val visibleUntil: Long = 0L,
        val sessionId: String = "",
        val readers: Int = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    @Volatile private var chunks: List<ByteArray> = emptyList()
    private val requestedIndex = ConcurrentHashMap<String, Int>()
    private val servedDevices = ConcurrentHashMap.newKeySet<String>()
    private val stopRunnable = Runnable { stop() }

    /** Called whenever the saved profile / sharing selection changes. */
    fun updateCard(vcard: String?): String? = try {
        chunks = if (vcard.isNullOrEmpty()) emptyList() else BleProtocol.split(vcard.toByteArray(Charsets.UTF_8)); null
    } catch (e: BleProtocol.TooLargeException) {
        chunks = emptyList(); e.message
    }

    fun start(durationMs: Long) {
        stop()
        val adapter = manager?.adapter
        when {
            adapter == null -> return fail("This phone has no Bluetooth.")
            !adapter.isEnabled -> return fail("Bluetooth is off. Turn it on and try again.")
            !adapter.isMultipleAdvertisementSupported || adapter.bluetoothLeAdvertiser == null ->
                return fail("This phone cannot advertise over Bluetooth LE, so it can't be found. You can still scan others, or use QR / Wi-Fi.")
            chunks.isEmpty() -> return fail("Nothing to share. Select some fields on the My Card screen (the card may also be too large for Bluetooth).")
        }
        try {
            val server = manager!!.openGattServer(context, gattCallback) ?: return fail("Could not start the Bluetooth server.")
            gattServer = server
            val service = BluetoothGattService(BleProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                addCharacteristic(BluetoothGattCharacteristic(BleProtocol.CONTROL,
                    BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
                addCharacteristic(BluetoothGattCharacteristic(BleProtocol.DATA,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            }
            server.addService(service) // advertising starts in onServiceAdded
            val sid = Tokens.randomBytes(BleProtocol.SESSION_ID_BYTES)
            pendingSessionId = sid
            _state.value = State(visible = false, sessionId = BleProtocol.toHex(sid))
            handler.postDelayed(stopRunnable, durationMs)
            pendingDuration = durationMs
        } catch (e: SecurityException) {
            fail("Bluetooth permission was denied.")
        }
    }

    private var pendingSessionId: ByteArray = ByteArray(0)
    private var pendingDuration = 0L

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return fail("Bluetooth LE advertising is unavailable.")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleProtocol.SERVICE), pendingSessionId)
            .build()
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            fail("Bluetooth advertise permission was denied.")
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        try { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        requestedIndex.clear(); servedDevices.clear()
        _state.value = _state.value.copy(visible = false, visibleUntil = 0L)
    }

    private fun fail(msg: String) {
        stop()
        _state.value = State(error = msg)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _state.value = _state.value.copy(visible = true, visibleUntil = System.currentTimeMillis() + pendingDuration, error = null)
        }

        override fun onStartFailure(errorCode: Int) {
            val msg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertisement data too large."
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many apps are advertising over Bluetooth right now."
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support Bluetooth LE advertising."
                else -> "Could not start Bluetooth advertising (code $errorCode)."
            }
            handler.post { fail(msg) }
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { startAdvertising() }
            else handler.post { fail("Could not register the Bluetooth service.") }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = characteristic.uuid == BleProtocol.CONTROL && _state.value.visible &&
                value != null && value.size == 1 && (value[0].toInt() and 0xFF) < chunks.size
            if (ok) requestedIndex[device.address] = value!![0].toInt() and 0xFF
            if (responseNeeded) respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleProtocol.DATA || !_state.value.visible) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null); return
            }
            val all = chunks
            val idx = requestedIndex[device.address] ?: 0
            val chunk = all.getOrNull(idx) ?: run {
                respond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null); return
            }
            if (offset > chunk.size) { respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null); return }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk.copyOfRange(offset, chunk.size))
            if (idx == all.lastIndex && offset == 0 && servedDevices.add(device.address)) {
                _state.value = _state.value.copy(readers = servedDevices.size)
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try { gattServer?.sendResponse(device, requestId, status, offset, value) } catch (_: SecurityException) {}
    }
}
