package com.anezium.taskerbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
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
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
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
    const val ACTION_HUD_BEACON_FOUND = "com.anezium.taskerbridge.phone.action.HUD_BEACON_FOUND"

    private val serviceUuid = UUID.fromString(Protocol.BLE_WAKE_SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(Protocol.BLE_WAKE_CHARACTERISTIC_UUID)
    private val hudBeaconUuid = UUID.fromString(Protocol.BLE_HUD_BEACON_SERVICE_UUID)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var advertising = false

    @Volatile
    private var lastAdvertiseFailureCode: Int? = null

    @Volatile
    private var hudBeaconScanActive = false

    @Volatile
    private var lastHudBeaconScanFailureCode: Int? = null

    @Volatile
    private var lastHudBeaconWakeAtMs: Long = 0L

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            lastAdvertiseFailureCode = null
            Log.i(TAG, "BLE wake advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            lastAdvertiseFailureCode = errorCode
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
        if (!hasAnyWakePermission(cleanContext)) {
            return BleWakeState(active = false, status = "Bluetooth wake permission missing")
        }
        val manager = cleanContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return BleWakeState(active = false, status = "Bluetooth disabled")
        }
        if (hasAdvertisePermission(cleanContext) && hasConnectPermission(cleanContext)) {
            openGattServer(cleanContext, manager)
            startAdvertising(adapter)
        } else {
            Log.w(TAG, "BLE wake advertising permission missing")
        }
        if (hasScanPermission(cleanContext)) {
            startHudBeaconScan(cleanContext, adapter)
        } else {
            Log.w(TAG, "BLE HUD beacon scan permission missing")
        }
        return health(cleanContext)
    }

    fun ensureHealthy(context: Context): BleWakeState {
        val state = health(context)
        if (state.active || !isArmed(context)) return state
        stop()
        ensureStarted(context)
        return health(context)
    }

    fun health(context: Context): BleWakeState {
        val cleanContext = context.applicationContext
        appContext = cleanContext
        if (!isArmed(cleanContext)) {
            return BleWakeState(active = false, status = "BLE wake disabled")
        }
        if (!hasAnyWakePermission(cleanContext)) {
            return BleWakeState(active = false, status = "Bluetooth wake permission missing")
        }
        val adapter = cleanContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return BleWakeState(active = false, status = "Bluetooth disabled")
        }
        val advertiseActive = gattServer != null && advertising
        val scanActive = hudBeaconScanActive
        if (advertiseActive && scanActive) {
            return BleWakeState(active = true, status = "BLE wake armed")
        }
        if (scanActive) {
            return BleWakeState(active = true, status = "BLE HUD beacon scan armed")
        }
        if (advertiseActive) {
            return BleWakeState(active = true, status = "BLE wake advertising armed")
        }
        return BleWakeState(active = false, status = wakeFailureStatus())
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

    fun isWakeScanIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_HUD_BEACON_FOUND

    fun handleWakeScanIntent(context: Context, intent: Intent?): Boolean {
        val cleanContext = context.applicationContext
        if (!isArmed(cleanContext)) return false
        appContext = cleanContext
        if (intent?.hasExtra(BluetoothLeScanner.EXTRA_ERROR_CODE) == true) {
            val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            hudBeaconScanActive = false
            lastHudBeaconScanFailureCode = errorCode
            Log.w(TAG, "HUD beacon scan failed code=$errorCode")
            ensureStarted(cleanContext)
            return false
        }
        val results = scanResults(intent)
        Log.i(TAG, "HUD BLE beacon received count=${results.size}")
        BridgeDiagnostics.recordWake(cleanContext, "HUD BLE beacon received count=${results.size}")
        val now = SystemClock.elapsedRealtime()
        if (now - lastHudBeaconWakeAtMs < HUD_BEACON_WAKE_DEBOUNCE_MS) {
            ensureStarted(cleanContext)
            return true
        }
        lastHudBeaconWakeAtMs = now
        val started = BridgeForegroundService.startSession(cleanContext, "HUD BLE beacon")
        BridgeDiagnostics.record(
            cleanContext,
            if (started) "HUD beacon posted session start" else "HUD beacon session start failed",
        )
        ensureStarted(cleanContext)
        return started
    }

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
        runCatching {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        }.onFailure { error ->
            advertising = false
            lastAdvertiseFailureCode = AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR
            Log.w(TAG, "BLE wake advertising start failed: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHudBeaconScan(context: Context, adapter: BluetoothAdapter) {
        if (hudBeaconScanActive) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            lastHudBeaconScanFailureCode = ScanCallback.SCAN_FAILED_INTERNAL_ERROR
            Log.w(TAG, "BLE HUD beacon scanner unavailable")
            return
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(hudBeaconUuid))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val result = runCatching {
            scanner.startScan(filters, settings, wakeScanPendingIntent(context))
        }.getOrElse { error ->
            Log.w(TAG, "BLE HUD beacon scan start failed: ${error.message}")
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR
        }
        if (result == ScanCallback.SCAN_FAILED_ALREADY_STARTED || result == SCAN_START_SUCCESS) {
            hudBeaconScanActive = true
            lastHudBeaconScanFailureCode = null
            Log.i(TAG, "BLE HUD beacon scan armed")
        } else {
            hudBeaconScanActive = false
            lastHudBeaconScanFailureCode = result
            Log.w(TAG, "BLE HUD beacon scan failed code=$result")
            BridgeDiagnostics.record(context, "HUD beacon scan failed: $result")
        }
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
        lastAdvertiseFailureCode = null
        stopHudBeaconScan(context, adapter)
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
    private fun stopHudBeaconScan(context: Context?, adapter: BluetoothAdapter?) {
        if (context != null && hasScanPermission(context)) {
            runCatching {
                adapter?.bluetoothLeScanner?.stopScan(wakeScanPendingIntent(context))
            }.onFailure {
                Log.w(TAG, "stop HUD beacon scan failed: ${it.message}")
            }
        }
        hudBeaconScanActive = false
        lastHudBeaconScanFailureCode = null
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
        BridgeDiagnostics.recordWake(context, "BLE task wake write received")
        return BridgeForegroundService.startSession(context)
    }

    private fun hasAnyWakePermission(context: Context): Boolean =
        hasScanPermission(context) || (hasAdvertisePermission(context) && hasConnectPermission(context))

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

    private fun hasScanPermission(context: Context?): Boolean =
        context != null &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN,
                    ) == PackageManager.PERMISSION_GRANTED
            )

    private fun wakeScanPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            WAKE_SCAN_REQUEST_CODE,
            Intent(context.applicationContext, BridgeAutostartReceiver::class.java)
                .setAction(ACTION_HUD_BEACON_FOUND)
                .setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or wakeScanMutabilityFlag(),
        )

    private fun wakeScanMutabilityFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }

    private fun scanResults(intent: Intent?): List<ScanResult> {
        if (intent == null) return emptyList()
        @Suppress("DEPRECATION")
        return intent.getParcelableArrayListExtra<ScanResult>(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
        ).orEmpty()
    }

    private fun wakeFailureStatus(): String {
        val scanFailure = lastHudBeaconScanFailureCode
        if (scanFailure != null) return "BLE HUD beacon scan failed: $scanFailure"
        if (gattServer == null) return "BLE wake GATT missing"
        val advertiseFailure = lastAdvertiseFailureCode
        return if (advertiseFailure == null) {
            "BLE wake advertising not confirmed"
        } else {
            "BLE wake advertising failed: $advertiseFailure"
        }
    }

    private const val SCAN_START_SUCCESS = 0
    private const val WAKE_SCAN_REQUEST_CODE = 72_411
    private const val HUD_BEACON_WAKE_DEBOUNCE_MS = 5_000L
}
