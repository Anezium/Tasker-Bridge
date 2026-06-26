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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: BridgeRuntime
    private var notificationJob: Job? = null
    private var lastNotificationText: String = ""
    private var idleStopRunnable: Runnable? = null
    private var wakeWatchdogRunnable: Runnable? = null
    private var explicitStop = false

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
        runtime = BridgeRuntime.get(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                handleSessionStart(
                    intent.getStringExtra(EXTRA_START_REASON).orEmpty().ifBlank { "HUD wake" },
                )
                return START_REDELIVER_INTENT
            }

            ACTION_ARM_WAKE, null -> {
                handleArmWake()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        notificationJob?.cancel()
        cancelIdleStop()
        cancelWakeWatchdog()
        if (!explicitStop && !BleWakeServer.isArmed(this)) {
            runtime.stopBluetoothSession()
        } else if (!explicitStop && BleWakeServer.isArmed(this)) {
            BridgeDiagnostics.record(this, "Foreground service destroyed; wake rearm scheduled")
            BridgeWakeScheduler.schedule(this)
        }
        runtime.markServiceActive(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!explicitStop && BleWakeServer.isArmed(this)) {
            BridgeDiagnostics.record(this, "Phone task removed; wake bridge rearmed")
            runtime.refreshWakeHealth("task removed")
            startBridgeForeground(runtime.state.value)
            startWakeWatchdog()
            BridgeWakeScheduler.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStop() {
        explicitStop = true
        if (activeService === this) {
            activeService = null
        }
        BridgeWakeScheduler.cancel(this)
        runtime.stopBackground()
        runtime.markServiceActive(false)
        stopForegroundCompat()
        stopSelf()
    }

    private fun handleSessionStart(reason: String) {
        explicitStop = false
        BridgeDiagnostics.record(this, "Foreground session start requested: $reason")
        runtime.markServiceActive(true)
        startBridgeForeground(runtime.state.value)
        runtime.startBluetoothSession(reason)
        watchRuntimeState()
        startWakeWatchdog()
        BridgeWakeScheduler.schedule(this)
        scheduleIdleStop(SESSION_IDLE_TIMEOUT_MS)
    }

    private fun handleArmWake() {
        explicitStop = false
        runtime.markServiceActive(false)
        runtime.startBackground()
        startBridgeForeground(runtime.state.value)
        watchRuntimeState()
        startWakeWatchdog()
        BridgeWakeScheduler.schedule(this)
        cancelIdleStop()
    }

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
                if (state.bluetoothConnected) {
                    cancelIdleStop()
                } else if (state.bridgeServiceActive) {
                    scheduleIdleStop(SESSION_IDLE_TIMEOUT_MS)
                } else if (state.bluetoothServerActive) {
                    cancelIdleStop()
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

    private fun scheduleIdleStop(delayMs: Long) {
        cancelIdleStop()
        idleStopRunnable = Runnable {
            runtime.stopBluetoothSession()
            runtime.markServiceActive(false)
            if (BleWakeServer.isArmed(this)) {
                runtime.refreshWakeHealth("session idle")
                startBridgeForeground(runtime.state.value)
                startWakeWatchdog()
            } else {
                stopForegroundCompat()
                stopSelf()
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun cancelIdleStop() {
        idleStopRunnable?.let(mainHandler::removeCallbacks)
        idleStopRunnable = null
    }

    private fun startWakeWatchdog() {
        if (wakeWatchdogRunnable != null) return
        wakeWatchdogRunnable = object : Runnable {
            override fun run() {
                if (explicitStop || !BleWakeServer.isArmed(this@BridgeForegroundService)) {
                    wakeWatchdogRunnable = null
                    return
                }
                runtime.refreshWakeHealth()
                startBridgeForeground(runtime.state.value)
                mainHandler.postDelayed(this, WAKE_WATCHDOG_INTERVAL_MS)
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, WAKE_WATCHDOG_INITIAL_DELAY_MS)
        }
    }

    private fun cancelWakeWatchdog() {
        wakeWatchdogRunnable?.let(mainHandler::removeCallbacks)
        wakeWatchdogRunnable = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "tasker_bridge"
        private const val NOTIFICATION_ID = 72_410
        private const val ACTION_START = "com.anezium.taskerbridge.phone.action.START_BRIDGE"
        private const val ACTION_ARM_WAKE = "com.anezium.taskerbridge.phone.action.ARM_WAKE_BRIDGE"
        private const val ACTION_STOP = "com.anezium.taskerbridge.phone.action.STOP_BRIDGE"
        private const val EXTRA_START_REASON = "com.anezium.taskerbridge.phone.extra.START_REASON"
        private const val SESSION_IDLE_TIMEOUT_MS = 4 * 60 * 1000L
        private const val WAKE_WATCHDOG_INITIAL_DELAY_MS = 20_000L
        private const val WAKE_WATCHDOG_INTERVAL_MS = 60_000L
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        private const val RUNTIME_FALLBACK_MAX_CONNECTED_CHECKS = 6

        @Volatile
        private var activeService: BridgeForegroundService? = null

        @Volatile
        private var runtimeFallbackGeneration: Long = 0L

        private val fallbackHandler by lazy { Handler(Looper.getMainLooper()) }

        fun start(context: Context) {
            armWake(context)
        }

        fun armWake(context: Context): Boolean {
            BridgeWakeScheduler.schedule(context)
            val service = activeService
            if (service != null) {
                service.mainHandler.post {
                    if (activeService === service) {
                        service.handleArmWake()
                    }
                }
                BridgeDiagnostics.record(context, "Foreground wake delivered to active service")
                return true
            }
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(ACTION_ARM_WAKE),
                )
                BridgeDiagnostics.record(context, "Foreground wake service armed")
                true
            }.getOrElse { error ->
                postRuntimeArmFallback(context)
                BridgeDiagnostics.recordSessionStartFailure(
                    context,
                    "Arm wake service failed: ${error.javaClass.simpleName}; runtime fallback",
                )
                true
            }
        }

        fun startSession(context: Context, reason: String = "BLE wake"): Boolean {
            BridgeWakeScheduler.schedule(context)
            val service = activeService
            if (service != null) {
                service.mainHandler.post {
                    if (activeService === service) {
                        service.handleSessionStart(reason)
                    }
                }
                BridgeDiagnostics.record(context, "Foreground session delivered to active service: $reason")
                return true
            }
            return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_START_REASON, reason),
                )
                BridgeDiagnostics.record(context, "Foreground session start posted: $reason")
                true
            }.getOrElse { error ->
                postRuntimeSessionFallback(context, reason)
                BridgeDiagnostics.recordSessionStartFailure(
                    context,
                    "Session start failed: ${error.javaClass.simpleName}; runtime fallback",
                )
                true
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BridgeForegroundService::class.java).setAction(ACTION_STOP))
        }

        private fun postRuntimeArmFallback(context: Context) {
            val appContext = context.applicationContext
            runFallbackOnMain {
                val runtime = BridgeRuntime.get(appContext)
                runtime.start()
                runtime.startBackground()
                BridgeWakeScheduler.schedule(appContext)
                BridgeDiagnostics.record(appContext, "Runtime wake fallback armed")
            }
        }

        private fun postRuntimeSessionFallback(context: Context, reason: String) {
            val appContext = context.applicationContext
            val generation = nextRuntimeFallbackGeneration()
            runFallbackOnMain {
                val runtime = BridgeRuntime.get(appContext)
                runtime.start()
                runtime.markServiceActive(true)
                runtime.startBluetoothSession("$reason (runtime fallback)")
                BridgeDiagnostics.record(appContext, "Runtime session fallback started: $reason")
                scheduleRuntimeFallbackCleanup(appContext, runtime, generation, connectedChecks = 0)
            }
        }

        private fun scheduleRuntimeFallbackCleanup(
            context: Context,
            runtime: BridgeRuntime,
            generation: Long,
            connectedChecks: Int,
        ) {
            fallbackHandler.postDelayed(
                {
                    if (generation != runtimeFallbackGeneration) return@postDelayed
                    val state = runtime.state.value
                    if (!state.bridgeServiceActive) return@postDelayed
                    if (state.bluetoothConnected && connectedChecks < RUNTIME_FALLBACK_MAX_CONNECTED_CHECKS) {
                        scheduleRuntimeFallbackCleanup(context, runtime, generation, connectedChecks + 1)
                        return@postDelayed
                    }
                    runtime.stopBluetoothSession()
                    runtime.markServiceActive(false)
                    if (BleWakeServer.isArmed(context)) {
                        runtime.refreshWakeHealth("runtime fallback idle")
                        BridgeWakeScheduler.schedule(context)
                    }
                    BridgeDiagnostics.record(context, "Runtime session fallback cleaned up")
                },
                SESSION_IDLE_TIMEOUT_MS,
            )
        }

        private fun nextRuntimeFallbackGeneration(): Long =
            synchronized(this) {
                runtimeFallbackGeneration += 1L
                runtimeFallbackGeneration
            }

        private inline fun runFallbackOnMain(crossinline block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                fallbackHandler.post { block() }
            }
        }
    }
}

private fun PhoneUiState.notificationText(): String = when {
    bluetoothConnected -> "HUD connected, ${tasks.size} tasks ready"
    bridgeServiceActive -> "Opening HUD Bluetooth session"
    bluetoothServerActive -> bluetoothStatus.ifBlank { "BLE wake armed, waiting for HUD" }
    else -> "Tasker Bridge idle"
}
