package com.anezium.taskerbridge.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BridgeRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasker = TaskerRepository(appContext)

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    private var started = false
    private var autoLaunchEnabled = false
    private var autoInstallAttempted = false
    private var connectAfterAuthorization = false
    private var authorizationRequestInFlight = false
    private var lastAuthorizationRequestAtMs = 0L
    private var lastConnectAttemptAtMs = 0L
    private var lastHudTaskRequestAtMs = 0L
    private var lastTaskListSentAtMs = 0L
    private var lastTaskerSnapshot: TaskerSnapshot? = null

    private val cxr = CxrPhoneController(
        context = appContext,
        onAuthorized = { authorized ->
            onMain {
                authorizationRequestInFlight = false
                _state.value = _state.value.copy(
                    authorized = authorized,
                    error = if (authorized) "" else "Hi Rokid authorization failed.",
                    lastStatus = if (authorized) "Hi Rokid authorized." else "Hi Rokid authorization failed.",
                )
                if (authorized && connectAfterAuthorization) {
                    connectAfterAuthorization = false
                    connectRokid(force = true)
                }
            }
        },
        onConnectionChanged = { cxrConnected, btConnected ->
            onMain {
                _state.value = _state.value.copy(
                    authorized = true,
                    cxrConnected = cxrConnected,
                    glassBtConnected = btConnected,
                )
                if (cxrConnected && btConnected) {
                    ensureHelperForAutoStart()
                    refreshTasker(sendToGlasses = false)
                }
            }
        },
        onHelperMessage = { message -> onMain { handleHelperMessage(message) } },
        onInstallStatus = { message, busy ->
            onMain {
                _state.value = _state.value.copy(
                    helperInstallStatus = message,
                    helperInstallBusy = busy,
                    lastStatus = message,
                )
            }
        },
        onHelperInstalled = {
            onMain {
                if (autoLaunchEnabled) {
                    launchHelper()
                }
            }
        },
        onHelperOpened = { opened ->
            onMain {
                if (opened) {
                    _state.value = _state.value.copy(lastStatus = "HUD opened. Waiting for task request.")
                    sendTaskListAfterHelperOpen()
                }
            }
        },
        onLog = { message -> onMain { _state.value = _state.value.copy(lastStatus = message) } },
        onError = { message, _ ->
            onMain {
                _state.value = _state.value.copy(error = message, lastStatus = message)
            }
        },
    )

    fun start() {
        if (started) return
        started = true
        _state.value = _state.value.copy(
            requiredRokidAppInstalled = cxr.isRequiredRokidAppInstalled(appContext),
            authorized = cxr.isAuthorized(),
            lastStatus = "Bridge runtime started.",
        )
        refreshTasker(sendToGlasses = false)
    }

    fun startBackground() {
        start()
        autoLaunchEnabled = true
        refreshTasker(sendToGlasses = false)
        if (cxr.isAuthorized()) {
            connectRokid()
        } else {
            _state.value = _state.value.copy(
                authorized = false,
                lastStatus = "Waiting for Hi Rokid authorization.",
            )
        }
    }

    fun autoStartFromActivity(activity: Activity) {
        start()
        autoLaunchEnabled = true
        refreshTasker(sendToGlasses = false)
        if (cxr.isAuthorized()) {
            connectRokid()
        } else {
            requestAuthorization(activity)
        }
    }

    fun startBridgeFromUi(activity: Activity) {
        start()
        autoLaunchEnabled = true
        autoInstallAttempted = false
        refreshTasker(sendToGlasses = false)
        if (cxr.isAuthorized()) {
            connectRokid(force = true)
            ensureHelperForAutoStart(force = true)
        } else {
            requestAuthorization(activity)
        }
    }

    fun markServiceActive(active: Boolean) {
        _state.value = _state.value.copy(bridgeServiceActive = active)
    }

    fun requestAuthorization(activity: Activity) {
        if (authorizationRequestInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuthorizationRequestAtMs < AUTH_REQUEST_COOLDOWN_MS) return
        lastAuthorizationRequestAtMs = now
        authorizationRequestInFlight = true
        connectAfterAuthorization = true
        _state.value = _state.value.copy(lastStatus = "Opening Hi Rokid authorization...")
        cxr.requestAuthorization(activity, CxrPhoneController.AUTH_REQUEST_CODE)
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        authorizationRequestInFlight = false
        cxr.handleAuthorizationResult(resultCode, data)
    }

    fun refreshTasker(sendToGlasses: Boolean = true, skipSendIfFingerprint: String = "") {
        scope.launch {
            val snapshot = tasker.refresh()
            val selected = _state.value.selectedIndex.coerceInTaskBounds(snapshot.tasks)
            lastTaskerSnapshot = snapshot
            _state.value = _state.value.copy(
                taskerInstalled = snapshot.installed,
                taskerEnabled = snapshot.enabled,
                externalAccess = snapshot.externalAccess,
                taskerRunPermissionGranted = snapshot.runPermissionGranted,
                tasks = snapshot.tasks,
                selectedIndex = selected,
                lastStatus = snapshot.message,
                error = if (
                    snapshot.installed &&
                    snapshot.externalAccess &&
                    snapshot.runPermissionGranted
                ) {
                    ""
                } else {
                    _state.value.error
                },
            )
            if (sendToGlasses && snapshot.fingerprint(selected) != skipSendIfFingerprint) {
                sendSnapshotToGlasses(snapshot)
            }
        }
    }

    fun selectTask(index: Int) {
        val safeIndex = index.coerceInTaskBounds(_state.value.tasks)
        _state.value = _state.value.copy(selectedIndex = safeIndex, helperSelectedIndex = safeIndex)
        sendCurrentTasksToGlasses()
    }

    fun launchHelper() {
        autoLaunchEnabled = true
        cxr.launchHelper()
    }

    fun stopHelper() {
        cxr.stopHelper()
    }

    fun runTaskFromPhone(taskName: String) {
        scope.launch {
            val result = tasker.runTask(taskName)
            _state.value = _state.value.copy(
                lastStatus = result.message,
                error = if (result.success) "" else result.message,
            )
            cxr.sendLaunchResult(
                ControlMessage.LaunchResult(
                    taskName = result.taskName,
                    success = result.success,
                    message = result.message,
                ),
            )
            refreshTasker(sendToGlasses = false)
        }
    }

    fun sendCurrentTasksToGlasses() {
        val current = _state.value
        val snapshot = TaskerSnapshot(
            installed = current.taskerInstalled,
            enabled = current.taskerEnabled,
            externalAccess = current.externalAccess,
            runPermissionGranted = current.taskerRunPermissionGranted,
            tasks = current.tasks,
            message = current.lastStatus,
        )
        sendSnapshotToGlasses(snapshot)
    }

    private fun connectRokid(force: Boolean = false) {
        if (!cxr.isAuthorized()) {
            _state.value = _state.value.copy(
                authorized = false,
                error = "Hi Rokid authorization is required.",
                lastStatus = "Hi Rokid authorization is required.",
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastConnectAttemptAtMs < CONNECT_COOLDOWN_MS) return
        lastConnectAttemptAtMs = now
        _state.value = _state.value.copy(authorized = true, lastStatus = "Connecting CXR-L...")
        cxr.connect()
    }

    private fun ensureHelperForAutoStart(force: Boolean = false) {
        if (!autoLaunchEnabled && !force) return
        if (autoInstallAttempted && !force) return
        val current = _state.value
        if (!current.cxrConnected || !current.glassBtConnected) return
        autoInstallAttempted = true
        if (force) {
            Log.i(TAG, "Force installing helper through CXR-L")
            cxr.installHelper()
        } else {
            Log.i(TAG, "Ensuring helper is running through CXR-L")
            cxr.ensureHelperRunning()
        }
    }

    private fun handleHelperMessage(message: StatusMessage) {
        when (message.type) {
            StatusType.READY -> {
                _state.value = _state.value.copy(
                    lastStatus = "Glasses helper: ${message.message.ifBlank { message.type.name }}",
                )
            }
            StatusType.REQUEST_TASKS -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHudTaskRequestAtMs < TASK_REQUEST_DEBOUNCE_MS) {
                    _state.value = _state.value.copy(lastStatus = "Glasses helper: task request already handled.")
                    return
                }
                lastHudTaskRequestAtMs = now
                _state.value = _state.value.copy(
                    lastStatus = "Glasses helper: ${message.message.ifBlank { message.type.name }}",
                )
                sendCachedTasksThenRefresh()
            }
            StatusType.LAUNCH_TASK -> {
                _state.value = _state.value.copy(lastStatus = "Glasses requested: ${message.taskName}")
                runTaskFromPhone(message.taskName)
            }
            StatusType.SELECTION_CHANGED -> {
                val safeIndex = message.selectedIndex.coerceInTaskBounds(_state.value.tasks)
                _state.value = _state.value.copy(
                    selectedIndex = safeIndex,
                    helperSelectedIndex = safeIndex,
                    lastStatus = "HUD selected ${message.taskName}",
                )
            }
            StatusType.ERROR -> {
                _state.value = _state.value.copy(error = message.message, lastStatus = message.message)
            }
            StatusType.PONG -> {
                _state.value = _state.value.copy(lastStatus = "HUD pong: ${message.message}")
            }
        }
    }

    private fun sendSnapshotToGlasses(snapshot: TaskerSnapshot) {
        lastTaskListSentAtMs = SystemClock.elapsedRealtime()
        cxr.sendTaskList(
            ControlMessage.TaskList(
                tasks = snapshot.tasks,
                selectedIndex = _state.value.selectedIndex.coerceInTaskBounds(snapshot.tasks),
                taskerInstalled = snapshot.installed,
                taskerEnabled = snapshot.enabled,
                externalAccess = snapshot.externalAccess && snapshot.runPermissionGranted,
                message = snapshot.message,
            ),
        )
    }

    private fun sendCachedTasksThenRefresh() {
        val cached = lastTaskerSnapshot
        if (cached == null) {
            refreshTasker(sendToGlasses = true)
            return
        }
        val selected = _state.value.selectedIndex.coerceInTaskBounds(cached.tasks)
        sendSnapshotToGlasses(cached)
        refreshTasker(
            sendToGlasses = true,
            skipSendIfFingerprint = cached.fingerprint(selected),
        )
    }

    private fun sendTaskListAfterHelperOpen() {
        scope.launch {
            val openedAtMs = SystemClock.elapsedRealtime()
            delay(HELPER_OPEN_TASK_LIST_DELAY_MS)
            val sentAfterOpen = lastTaskListSentAtMs >= openedAtMs
            val sentRecently = SystemClock.elapsedRealtime() - lastTaskListSentAtMs < RECENT_TASK_LIST_WINDOW_MS
            if (sentAfterOpen || sentRecently) return@launch
            sendCachedTasksThenRefresh()
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    companion object {
        private const val TAG = "TaskerBridge-Runtime"
        private const val AUTH_REQUEST_COOLDOWN_MS = 2_500L
        private const val CONNECT_COOLDOWN_MS = 5_000L
        private const val TASK_REQUEST_DEBOUNCE_MS = 800L
        private const val HELPER_OPEN_TASK_LIST_DELAY_MS = 1_200L
        private const val RECENT_TASK_LIST_WINDOW_MS = 2_500L

        @Volatile
        private var instance: BridgeRuntime? = null

        fun get(context: Context): BridgeRuntime {
            return instance ?: synchronized(this) {
                instance ?: BridgeRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}

private fun Int.coerceInTaskBounds(tasks: List<*>): Int =
    if (tasks.isEmpty()) 0 else coerceIn(0, tasks.lastIndex)

private fun TaskerSnapshot.fingerprint(selectedIndex: Int): String = buildString {
    append(installed).append('|')
    append(enabled).append('|')
    append(externalAccess).append('|')
    append(runPermissionGranted).append('|')
    append(selectedIndex).append('|')
    append(message).append('|')
    tasks.forEach { task ->
        append(task.name).append('\u001F').append(task.projectName).append('\u001E')
    }
}
