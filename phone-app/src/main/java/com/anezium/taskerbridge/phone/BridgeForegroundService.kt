package com.anezium.taskerbridge.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: BridgeRuntime
    private var notificationJob: Job? = null
    private var lastNotificationText: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runtime = BridgeRuntime.get(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.stopBackground()
            stopSelf()
            return START_NOT_STICKY
        }

        runtime.markServiceActive(true)
        startBridgeForeground(runtime.state.value)
        runtime.startBackground()
        watchRuntimeState()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runtime.startBackground()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        runtime.stopBackground()
        runtime.markServiceActive(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun watchRuntimeState() {
        if (notificationJob?.isActive == true) return
        val manager = getSystemService(NotificationManager::class.java)
        notificationJob = serviceScope.launch {
            runtime.state.collect { state ->
                val nextText = state.notificationText()
                if (nextText != lastNotificationText) {
                    lastNotificationText = nextText
                    manager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    private fun startBridgeForeground(state: PhoneUiState) {
        lastNotificationText = state.notificationText()
        val notification = buildNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_TYPES)
            }.onFailure {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: PhoneUiState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.anezium.taskerbridge.phone.R.drawable.ic_stat_tasker_bridge)
            .setContentTitle("Tasker Bridge")
            .setContentText(state.notificationText())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(com.anezium.taskerbridge.phone.R.drawable.ic_stat_tasker_bridge, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tasker Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Rokid Tasker bridge connected in the background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tasker_bridge"
        private const val NOTIFICATION_ID = 72_410
        private const val ACTION_START = "com.anezium.taskerbridge.phone.action.START_BRIDGE"
        private const val ACTION_STOP = "com.anezium.taskerbridge.phone.action.STOP_BRIDGE"
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeForegroundService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BridgeForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}

private fun PhoneUiState.notificationText(): String = when {
    bluetoothConnected -> "HUD connected, ${tasks.size} tasks ready"
    bluetoothServerActive -> "Waiting for Tasker Bridge HUD"
    else -> "Starting Bluetooth bridge"
}
