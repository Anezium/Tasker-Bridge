package com.anezium.taskerbridge.phone

import android.content.Context
import android.text.format.DateFormat

object BridgeDiagnostics {
    private const val PREFS_NAME = "tasker_bridge_diagnostics"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_EVENT_AT = "last_event_at"
    private const val KEY_WAKE_COUNT = "wake_count"
    private const val KEY_SESSION_START_FAILURES = "session_start_failures"

    fun record(context: Context, event: String) {
        val cleanEvent = event.take(MAX_EVENT_CHARS)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_EVENT, cleanEvent)
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordWake(context: Context, event: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_EVENT, event.take(MAX_EVENT_CHARS))
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .putInt(KEY_WAKE_COUNT, prefs.getInt(KEY_WAKE_COUNT, 0) + 1)
            .apply()
    }

    fun recordSessionStartFailure(context: Context, event: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_EVENT, event.take(MAX_EVENT_CHARS))
            .putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis())
            .putInt(KEY_SESSION_START_FAILURES, prefs.getInt(KEY_SESSION_START_FAILURES, 0) + 1)
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val event = prefs.getString(KEY_LAST_EVENT, "").orEmpty()
        val maintenance = "${BridgeWakeScheduler.summary(context)} | ${BleWakeServer.rebuildSummary()}"
        if (event.isBlank()) return "No wake events recorded yet. | $maintenance"
        val at = prefs.getLong(KEY_LAST_EVENT_AT, 0L)
        val time = if (at > 0L) DateFormat.format("HH:mm:ss", at).toString() else "--:--:--"
        val wakeCount = prefs.getInt(KEY_WAKE_COUNT, 0)
        val failures = prefs.getInt(KEY_SESSION_START_FAILURES, 0)
        return "Wake debug $time: $event | wakes=$wakeCount failures=$failures | $maintenance"
    }

    private const val MAX_EVENT_CHARS = 120
}
