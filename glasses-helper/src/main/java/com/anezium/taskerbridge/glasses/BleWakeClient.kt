package com.anezium.taskerbridge.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.anezium.taskerbridge.shared.Protocol
import org.json.JSONObject
import java.util.UUID

object BleWakeClient {
    private const val TAG = "TaskerBridge-BLE"
    private const val WAKE_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val serviceUuid = UUID.fromString(Protocol.BLE_WAKE_SERVICE_UUID)
    private val characteristicUuid = UUID.fromString(Protocol.BLE_WAKE_CHARACTERISTIC_UUID)

    @Volatile
    private var activeCallback: ((Boolean, String) -> Unit)? = null

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var activeScanCallback: ScanCallback? = null

    @Volatile
    private var activeContext: Context? = null

    @Volatile
    private var activeOperationId: Long = 0L

    @Volatile
    private var activeGattOperationId: Long = 0L

    private var timeoutRunnable: Runnable? = null

    fun requestWake(
        context: Context,
        callback: (Boolean, String) -> Unit,
    ) {
        main.post {
            cancelActive(notify = false)
            val operationId = activeOperationId + 1
            activeOperationId = operationId
            activeCallback = callback
            val appContext = context.applicationContext
            if (!hasBlePermissions(appContext)) {
                finish(operationId, false, "Bluetooth wake permission missing")
                return@post
            }
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                finish(operationId, false, "Bluetooth is off")
                return@post
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                finish(operationId, false, "BLE scanner unavailable")
                return@post
            }
            activeContext = appContext
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    if (!isActiveOperation(operationId)) return
                    val device = result?.device ?: return
                    stopScan(appContext)
                    connect(appContext, device, operationId)
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(operationId, false, "BLE wake scan failed: $errorCode")
                }
            }
            activeScanCallback = scanCallback
            scheduleTimeout(operationId)
            startScan(scanner, scanCallback)
        }
    }

    fun cancel() {
        val cancelledOperationId = activeOperationId + 1
        activeOperationId = cancelledOperationId
        runOnMain {
            if (activeOperationId == cancelledOperationId) {
                cancelActive(notify = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        callback: ScanCallback,
    ) {
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(context: Context) {
        val callback = activeScanCallback ?: return
        activeScanCallback = null
        runCatching {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter
                ?.bluetoothLeScanner
                ?.stopScan(callback)
        }.onFailure {
            Log.w(TAG, "stop BLE wake scan failed: ${it.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(
        context: Context,
        device: BluetoothDevice,
        operationId: Long,
    ) {
        if (!isActiveOperation(operationId)) return
        activeGattOperationId = operationId
        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val operationId = activeTokenFor(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(operationId, false, "BLE wake connect failed")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> finish(operationId, false, "BLE wake disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val operationId = activeTokenFor(gatt) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(operationId, false, "BLE wake service discovery failed")
                return
            }
            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(characteristicUuid)
            if (service == null || characteristic == null) {
                finish(operationId, false, "BLE wake service not found")
                return
            }
            writeWakeRequest(gatt, characteristic, operationId)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != characteristicUuid) return
            val operationId = activeTokenFor(gatt) ?: return
            finish(
                operationId = operationId,
                ok = status == BluetoothGatt.GATT_SUCCESS,
                message = if (status == BluetoothGatt.GATT_SUCCESS) {
                    "Phone waking..."
                } else {
                    "BLE wake write failed"
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeWakeRequest(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        operationId: Long,
    ) {
        if (!isActiveOperation(operationId)) return
        val payload = JSONObject()
            .put("version", Protocol.PROTOCOL_VERSION)
            .put("type", Protocol.BLE_WAKE_TYPE_TASKS)
            .put("source", Protocol.HELPER_ROLE)
            .toString()
            .toByteArray(Charsets.UTF_8)
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (!started) {
            finish(operationId, false, "BLE wake write did not start")
        }
    }

    private fun scheduleTimeout(operationId: Long) {
        val runnable = Runnable {
            finish(operationId, false, "Phone wake timed out")
        }
        timeoutRunnable = runnable
        main.postDelayed(runnable, WAKE_TIMEOUT_MS)
    }

    private fun finish(operationId: Long, ok: Boolean, message: String) {
        main.post {
            if (!isActiveOperation(operationId)) return@post
            val callback = activeCallback
            cancelActive(notify = false)
            callback?.invoke(ok, message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun cancelActive(notify: Boolean) {
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        val context = activeContext
        val scanCallback = activeScanCallback
        if (context != null && scanCallback != null) {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bluetoothLeScanner
                    ?.stopScan(scanCallback)
            }.onFailure {
                Log.w(TAG, "cancel BLE wake scan failed: ${it.message}")
            }
        }
        activeScanCallback = null
        activeContext = null
        runCatching { activeGatt?.close() }.onFailure {
            Log.w(TAG, "close BLE wake GATT failed: ${it.message}")
        }
        activeGatt = null
        activeGattOperationId = 0L
        val callback = activeCallback
        activeCallback = null
        if (notify) callback?.invoke(false, "BLE wake cancelled")
    }

    private fun activeTokenFor(gatt: BluetoothGatt): Long? {
        if (activeGatt !== gatt) {
            runCatching { gatt.close() }
            return null
        }
        return activeGattOperationId.takeIf(::isActiveOperation)
    }

    private fun isActiveOperation(operationId: Long): Boolean =
        operationId != 0L && activeOperationId == operationId

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post { block() }
        }
    }

    private fun hasBlePermissions(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            )
}
