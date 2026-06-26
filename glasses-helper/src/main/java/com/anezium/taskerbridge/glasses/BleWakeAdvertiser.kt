package com.anezium.taskerbridge.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.taskerbridge.shared.Protocol
import java.util.UUID

object BleWakeAdvertiser {
    private const val TAG = "TaskerBridge-BLE"
    private const val BEACON_TIMEOUT_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private val beaconUuid = UUID.fromString(Protocol.BLE_HUD_BEACON_SERVICE_UUID)

    @Volatile
    private var activeContext: Context? = null

    @Volatile
    private var activeCallback: AdvertiseCallback? = null

    private var timeoutRunnable: Runnable? = null

    fun pulse(context: Context) {
        main.post {
            stopActive()
            val appContext = context.applicationContext
            if (!hasAdvertisePermission(appContext)) {
                Log.w(TAG, "HUD wake beacon permission missing")
                return@post
            }
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            val advertiser = adapter?.bluetoothLeAdvertiser
            if (adapter == null || !adapter.isEnabled || advertiser == null) {
                Log.w(TAG, "HUD wake beacon unavailable")
                return@post
            }
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    Log.i(TAG, "HUD wake beacon advertising")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "HUD wake beacon failed code=$errorCode")
                    stopActive()
                }
            }
            activeContext = appContext
            activeCallback = callback
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(beaconUuid))
                .build()
            runCatching {
                advertiser.startAdvertising(settings, data, callback)
                scheduleStop()
            }.onFailure { error ->
                Log.w(TAG, "HUD wake beacon start failed: ${error.message}")
                stopActive()
            }
        }
    }

    fun cancel() {
        main.post { stopActive() }
    }

    private fun scheduleStop() {
        val runnable = Runnable { stopActive() }
        timeoutRunnable = runnable
        main.postDelayed(runnable, BEACON_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopActive() {
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        val context = activeContext
        val callback = activeCallback
        if (context != null && callback != null && hasAdvertisePermission(context)) {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bluetoothLeAdvertiser
                    ?.stopAdvertising(callback)
            }.onFailure {
                Log.w(TAG, "stop HUD wake beacon failed: ${it.message}")
            }
        }
        activeContext = null
        activeCallback = null
    }

    private fun hasAdvertisePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ) == PackageManager.PERMISSION_GRANTED
}
