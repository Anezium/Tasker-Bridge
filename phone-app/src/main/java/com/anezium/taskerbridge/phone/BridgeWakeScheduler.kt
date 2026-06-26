package com.anezium.taskerbridge.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log

object BridgeWakeScheduler {
    const val ACTION_REARM_WAKE = "com.anezium.taskerbridge.phone.action.REARM_WAKE_BRIDGE"

    private const val TAG = "TaskerBridge-Rearm"
    private const val REARM_INTERVAL_MS = 15 * 60 * 1000L
    private const val REARM_WINDOW_MS = 5 * 60 * 1000L
    private const val REQUEST_CODE = 72_412
    private const val PREFS_NAME = "tasker_bridge_wake_scheduler"
    private const val KEY_LAST_SCHEDULE_MODE = "last_schedule_mode"
    private const val KEY_LAST_SCHEDULE_ERROR = "last_schedule_error"
    private const val KEY_LAST_SCHEDULE_AT = "last_schedule_at"
    private const val KEY_LAST_TRIGGER_ELAPSED = "last_trigger_elapsed"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        if (!BleWakeServer.isArmed(appContext)) {
            cancel(appContext)
            return
        }
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: run {
            recordSchedule(appContext, "unavailable", 0L, "AlarmManager unavailable")
            return
        }
        val triggerAtMs = SystemClock.elapsedRealtime() + REARM_INTERVAL_MS
        val operation = pendingIntent(appContext)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, operation)
                recordSchedule(appContext, "idle", triggerAtMs, null)
            } else {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, operation)
                recordSchedule(appContext, "set", triggerAtMs, null)
            }
        }.onFailure { error ->
            Log.w(TAG, "idle rearm scheduling failed: ${error.message}")
            runCatching {
                alarm.setWindow(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    REARM_WINDOW_MS,
                    operation,
                )
                recordSchedule(appContext, "window", triggerAtMs, error.message)
            }.onFailure {
                Log.w(TAG, "inexact rearm scheduling failed: ${it.message}")
                recordSchedule(appContext, "failed", triggerAtMs, it.message)
            }
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarm = appContext.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(appContext))
        recordSchedule(appContext, "off", 0L, null)
    }

    fun summary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_LAST_SCHEDULE_MODE, "").orEmpty()
        if (mode.isBlank()) return "rearm=unknown"
        val triggerElapsed = prefs.getLong(KEY_LAST_TRIGGER_ELAPSED, 0L)
        val scheduledAt = prefs.getLong(KEY_LAST_SCHEDULE_AT, 0L)
        val scheduledTime = if (scheduledAt > 0L) {
            DateFormat.format("HH:mm:ss", scheduledAt).toString()
        } else {
            "--:--:--"
        }
        val remaining = if (triggerElapsed > 0L) {
            " next~${durationLabel(triggerElapsed - SystemClock.elapsedRealtime())}"
        } else {
            ""
        }
        val error = prefs.getString(KEY_LAST_SCHEDULE_ERROR, "").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { " err=${it.take(48)}" }
            .orEmpty()
        return "rearm=$mode@$scheduledTime$remaining$error"
    }

    fun isRearmIntent(intent: Intent?): Boolean =
        intent?.action == ACTION_REARM_WAKE

    private fun recordSchedule(
        context: Context,
        mode: String,
        triggerAtMs: Long,
        error: String?,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SCHEDULE_MODE, mode)
            .putString(KEY_LAST_SCHEDULE_ERROR, error.orEmpty())
            .putLong(KEY_LAST_SCHEDULE_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_TRIGGER_ELAPSED, triggerAtMs)
            .apply()
    }

    private fun durationLabel(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0L) / 1000L
        val minutes = seconds / 60L
        val remainderSeconds = seconds % 60L
        return if (minutes > 0L) {
            "${minutes}m${remainderSeconds}s"
        } else {
            "${remainderSeconds}s"
        }
    }

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
