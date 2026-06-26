package com.anezium.taskerbridge.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

object BridgeWakeScheduler {
    const val ACTION_REARM_WAKE = "com.anezium.taskerbridge.phone.action.REARM_WAKE_BRIDGE"

    private const val TAG = "TaskerBridge-Rearm"
    private const val REARM_INTERVAL_MS = 15 * 60 * 1000L
    private const val REARM_WINDOW_MS = 5 * 60 * 1000L
    private const val REQUEST_CODE = 72_412

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        if (!BleWakeServer.isArmed(appContext)) {
            cancel(appContext)
            return
        }
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMs = SystemClock.elapsedRealtime() + REARM_INTERVAL_MS
        val operation = pendingIntent(appContext)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, operation)
            } else {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, operation)
            }
        }.onFailure { error ->
            Log.w(TAG, "exact idle rearm scheduling failed: ${error.message}")
            runCatching {
                alarm.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    REARM_WINDOW_MS,
                    operation,
                )
            }.onFailure {
                Log.w(TAG, "inexact rearm scheduling failed: ${it.message}")
            }
        }
    }

    fun cancel(context: Context) {
        val alarm = context.applicationContext.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(context.applicationContext))
    }

    fun isRearmIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_REARM_WAKE

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, BridgeAutostartReceiver::class.java)
                .setAction(ACTION_REARM_WAKE)
                .setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
