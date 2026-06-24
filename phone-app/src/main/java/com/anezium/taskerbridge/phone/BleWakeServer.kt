package com.anezium.taskerbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.taskerbridge.shared.Protocol
import org.json.JSONObject
import java.util.UUID

data class BleWakeState(
    val active: Boolean,
    val status: String,
)

object BleWakeServer {
    private const val TAG = "TaskerBridge-BLE"
    private const val PREFS_NAME = "tasker_bridge_bluetooth"
    private const val KEY_WAKE_ARMED = "ble_wake_armed"

    private val serviceUuid = UUID.fromString(Protocol.BLE_WAKE_SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(Protocol.BLE_WAKE_CHARACTERISTIC_UUID)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var advertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "BLE wake advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "BLE wake advertising failed code=$errorCode")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic?.uuid != characteristicUuid) {
                respond(device, requestId, responseNeeded, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }
            val accepted = handleWakePayload(value)
            respond(
                device = device,
                requestId = requestId,
                responseNeeded = responseNeeded,
                status = if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
            )
        }
    }

    fun arm(context: Context): BleWakeState {
        val cleanContext = context.applicationContext
        setArmed(cleanContext, true)
        return ensureStarted(cleanContext)
    }

    fun ensureStarted(context: Context): BleWakeState {
        val cleanContext = context.applicationContext
        appContext = cleanContext
        if (!isArmed(cleanContext)) {
            stop()
            return BleWakeState(active = false, status = "BLE wake disabled")
        }
        if (!hasBlePermissions(cleanContext)) {
            return BleWakeState(active = false, status = "Bluetooth wake permission missing")
        }
        val manager = cleanContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return BleWakeState(active = false, status = "Bluetooth disabled")
        }
        openGattServer(cleanContext, manager)
        startAdvertising(adapter)
        return BleWakeState(active = gattServer != null, status = "BLE wake armed")
    }

    fun disarm(context: Context): BleWakeState {
        setArmed(context.applicationContext, false)
        stop()
        return BleWakeState(active = false, status = "BLE wake stopped")
    }

    fun isArmed(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAKE_ARMED, false)

    private fun setArmed(context: Context, armed: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAKE_ARMED, armed)
            .apply()
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer(context: Context, manager: BluetoothManager) {
        if (gattServer != null) return
        val server = manager.openGattServer(context, serverCallback)
        if (server == null) {
            Log.w(TAG, "openGattServer returned null")
            return
        }
        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (advertising) return
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "bluetoothLeAdvertiser unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    fun stop() {
        val context = appContext
        val adapter = context?.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching {
            if (hasAdvertisePermission(context)) {
                adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
        }.onFailure {
            Log.w(TAG, "stop BLE wake advertising failed: ${it.message}")
        }
        advertising = false
        runCatching {
            if (hasConnectPermission(context)) {
                gattServer?.close()
            }
        }.onFailure {
            Log.w(TAG, "close BLE wake GATT failed: ${it.message}")
        }
        gattServer = null
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        device: BluetoothDevice?,
        requestId: Int,
        responseNeeded: Boolean,
        status: Int,
    ) {
        if (!responseNeeded || device == null) return
        runCatching {
            gattServer?.sendResponse(device, requestId, status, 0, null)
        }.onFailure {
            Log.w(TAG, "BLE wake response failed: ${it.message}")
        }
    }

    private fun handleWakePayload(value: ByteArray?): Boolean {
        val context = appContext ?: return false
        val payload = value?.toString(Charsets.UTF_8).orEmpty()
        val json = runCatching { JSONObject(payload) }
            .onFailure { Log.w(TAG, "wake payload parse failed len=${payload.length}") }
            .getOrNull()
            ?: return false
        if (json.optString("type") != Protocol.BLE_WAKE_TYPE_TASKS) return false
        Log.i(TAG, "BLE task wake received")
        return BridgeForegroundService.startSession(context)
    }

    private fun hasBlePermissions(context: Context): Boolean =
        hasAdvertisePermission(context) && hasConnectPermission(context)

    private fun hasAdvertisePermission(context: Context?): Boolean =
        context != null &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    ) == PackageManager.PERMISSION_GRANTED
            )

    private fun hasConnectPermission(context: Context?): Boolean =
        context != null &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) == PackageManager.PERMISSION_GRANTED
            )
}
