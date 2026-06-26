package com.anezium.taskerbridge.phone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BridgeAutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            -> rearmIfEnabled(context, action.substringAfterLast('.'))

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    rearmIfEnabled(context, "BLUETOOTH_ON")
                }
            }
        }
    }

    private fun rearmIfEnabled(context: Context, reason: String) {
        if (!BleWakeServer.isArmed(context)) return
        CompanionDeviceCoordinator.startObserving(context)
        val state = BleWakeServer.ensureStarted(context)
        Log.i(TAG, "BLE wake rearm after $reason: ${state.status}")
    }

    companion object {
        private const val TAG = "TaskerBridge-Autostart"
    }
}
