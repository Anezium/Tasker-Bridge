package com.anezium.taskerbridge.phone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

class BridgeAutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (BleWakeServer.isWakeScanIntent(intent)) {
            val pendingResult = goAsync()
            val accepted = runCatching {
                BleWakeServer.handleWakeScanIntent(context, intent)
            }.getOrElse { error ->
                Log.w(TAG, "HUD beacon wake handling failed: ${error.message}")
                false
            }
            if (accepted) {
                Log.i(TAG, "HUD beacon wake accepted")
            }
            finishPendingResult(pendingResult, if (accepted) HUD_WAKE_RECEIVER_HOLD_MS else 0L)
            return
        }
        if (BridgeWakeScheduler.isRearmIntent(intent)) {
            rearmIfEnabled(context, "scheduled")
            return
        }
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
        val state = if (reason == "scheduled") {
            BleWakeServer.restart(context)
        } else {
            BleWakeServer.ensureStarted(context)
        }
        BridgeForegroundService.armWake(context)
        BridgeWakeScheduler.schedule(context)
        Log.i(TAG, "BLE wake rearm after $reason: ${state.status}")
    }

    companion object {
        private const val TAG = "TaskerBridge-Autostart"
        private const val HUD_WAKE_RECEIVER_HOLD_MS = 4_000L
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        private fun finishPendingResult(
            pendingResult: BroadcastReceiver.PendingResult,
            delayMs: Long,
        ) {
            if (delayMs <= 0L) {
                runCatching { pendingResult.finish() }
                return
            }
            mainHandler.postDelayed(
                { runCatching { pendingResult.finish() } },
                delayMs,
            )
        }
    }
}
