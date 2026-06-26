package com.anezium.taskerbridge.glasses

import android.content.Context
import android.os.SystemClock
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
import com.anezium.taskerbridge.shared.TaskerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private data class PendingLaunchRequest(
    val id: String,
    val taskName: String,
    val selectedIndex: Int,
    val onLaunchSucceeded: () -> Unit,
)

class HelperRuntime private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val bridge = BluetoothBridgeClient(
        context = appContext,
        onState = { state -> onMain { handleBluetoothState(state) } },
        onMessage = { message -> onMain { handleControlMessage(message) } },
        onLog = { message -> onMain { _state.value = _state.value.copy(bridgeState = message) } },
        onError = { message, _ -> onMain { _state.value = _state.value.copy(bridgeState = message) } },
    )
    private val cxr = CxrFallbackBridge(
        onState = { state -> onMain { handleCxrState(state) } },
        onMessage = { message -> onMain { handleControlMessage(message) } },
        onLog = { message -> onMain { _state.value = _state.value.copy(bridgeState = message) } },
    )

    private var started = false
    private var bluetoothConnected = false
    private var cxrConnected = false
    private var taskListReceived = false
    private var wakeRequestInFlight = false
    private var taskRequestRetryJob: Job? = null
    private var launchRetryJob: Job? = null
    private var pendingLaunch: PendingLaunchRequest? = null
    private var lastTaskRequestAtMs = 0L
    private var lastWakeRequestAtMs = 0L
    private var lastTaskListReceivedAtMs = 0L
    private var lastBridgeRestartAtMs = 0L
    private var nextLaunchRequestId = 0L

    private val _state = MutableStateFlow(HelperUiState())
    val state: StateFlow<HelperUiState> = _state.asStateFlow()

    fun start() {
        if (!started) {
            started = true
            taskListReceived = false
            lastTaskListReceivedAtMs = 0L
            _state.value = _state.value.copy(bridgeState = "Waking phone")
            bridge.start()
            cxr.start()
            requestPhoneWake("HUD opened")
            return
        }
        requestTasks("HUD opened")
    }

    fun close() {
        val wasStarted = started
        started = false
        taskRequestRetryJob?.cancel()
        taskRequestRetryJob = null
        launchRetryJob?.cancel()
        launchRetryJob = null
        pendingLaunch = null
        wakeRequestInFlight = false
        taskListReceived = false
        lastTaskRequestAtMs = 0L
        lastWakeRequestAtMs = 0L
        lastTaskListReceivedAtMs = 0L
        lastBridgeRestartAtMs = 0L
        BleWakeAdvertiser.cancel()
        BleWakeClient.cancel()
        bridge.stop()
        cxr.stop()
        if (!wasStarted) return
    }

    fun resume() {
        start()
    }

    fun nextTask() {
        moveMenuSelection(1)
    }

    fun previousTask() {
        moveMenuSelection(-1)
    }

    fun launchSelectedTask(onLaunchRequestSent: () -> Unit = {}) {
        val current = _state.value
        if (current.tasks.isEmpty()) return
        when (current.viewMode) {
            HelperViewMode.PROJECTS -> enterProjectAt(current.menuModel().safeProjectIndex)
            HelperViewMode.TASKS -> launchTaskAt(
                index = current.menuModel().selectedTaskIndex,
                onLaunchRequestSent = onLaunchRequestSent,
            )
        }
    }

    fun selectProjectAt(index: Int) {
        val current = _state.value
        if (current.viewMode != HelperViewMode.PROJECTS || current.tasks.isEmpty()) return
        applyMenuSelection(
            selection = current.menuModel().selectProject(index),
            notifyTaskSelection = false,
        )
    }

    fun activateProjectAt(index: Int) {
        val current = _state.value
        if (current.tasks.isEmpty()) return
        enterProjectAt(index)
    }

    fun selectTaskAt(index: Int) {
        val current = _state.value
        if (current.viewMode != HelperViewMode.TASKS || current.tasks.isEmpty()) return
        applyMenuSelection(
            selection = current.menuModel().selectTask(index),
            notifyTaskSelection = true,
        )
    }

    fun launchTaskAt(
        index: Int,
        onLaunchRequestSent: () -> Unit = {},
    ) {
        val current = _state.value
        val selection = current.menuModel().selectTask(index)
        val task = current.tasks.getOrNull(selection.selectedIndex) ?: return
        _state.value = current.applyMenuSelection(selection).copy(
            status = "Requesting ${task.name}",
            lastLaunchTask = task.name,
            lastLaunchSuccess = null,
        )
        sendLaunchRequest(
            task = task,
            selectedIndex = selection.selectedIndex,
            onLaunchRequestSent = onLaunchRequestSent,
        )
    }

    fun navigateBack(): Boolean {
        val current = _state.value
        if (current.viewMode != HelperViewMode.TASKS) return false
        val selection = current.menuModel().backToProjects()
        _state.value = current.applyMenuSelection(selection).copy(status = current.projectListStatus())
        return true
    }

    fun requestTasks(reason: String = "Refresh requested") {
        val now = SystemClock.elapsedRealtime()
        if (taskListReceived && now - lastTaskListReceivedAtMs < FRESH_TASK_LIST_WINDOW_MS) return
        taskListReceived = false
        sendTaskRequest(reason)
        beginTaskRequestRetry()
    }

    private fun handleBluetoothState(state: BluetoothClientState) {
        val wasConnected = _state.value.phoneConnected
        bluetoothConnected = state.connected
        _state.value = _state.value.copy(
            phoneConnected = bluetoothConnected || cxrConnected,
            phoneName = when {
                bluetoothConnected -> state.peerName
                cxrConnected -> "CXR-L"
                else -> state.peerName
            },
            bridgeState = if (!bluetoothConnected && cxrConnected) {
                "Phone connected via CXR"
            } else {
                state.status
            },
        )
        if (_state.value.phoneConnected && !wasConnected) {
            sendStatus(StatusMessage(StatusType.READY, "Helper ready over Bluetooth"))
            requestTasks("Bluetooth connected")
            retryPendingLaunch()
        }
    }

    private fun handleCxrState(state: CxrBridgeState) {
        val wasConnected = _state.value.phoneConnected
        cxrConnected = state.connected
        _state.value = _state.value.copy(
            phoneConnected = bluetoothConnected || cxrConnected,
            phoneName = if (cxrConnected) "CXR-L" else _state.value.phoneName,
            bridgeState = if (!cxrConnected && bluetoothConnected) {
                "Phone connected via Bluetooth"
            } else {
                state.status
            },
        )
        if (_state.value.phoneConnected && !wasConnected) {
            sendStatus(StatusMessage(StatusType.READY, "Helper ready over CXR"))
            requestTasks("CXR connected")
            retryPendingLaunch()
        }
    }

    private fun sendLaunchRequest(
        task: TaskerTask,
        selectedIndex: Int,
        onLaunchRequestSent: () -> Unit,
    ) {
        val request = PendingLaunchRequest(
            id = "launch-${SystemClock.elapsedRealtime()}-${++nextLaunchRequestId}",
            taskName = task.name,
            selectedIndex = selectedIndex,
            onLaunchSucceeded = onLaunchRequestSent,
        )
        pendingLaunch = request
        attemptLaunchRequest(request, wakeOnFailure = true)
        beginLaunchRetry(request)
    }

    private fun attemptLaunchRequest(
        request: PendingLaunchRequest,
        wakeOnFailure: Boolean,
    ) {
        sendStatus(
            StatusMessage(
                type = StatusType.LAUNCH_TASK,
                taskName = request.taskName,
                selectedIndex = request.selectedIndex,
                requestId = request.id,
                message = "Launch requested",
            ),
            onNotSent = {
                if (pendingLaunch?.id == request.id) {
                    _state.value = _state.value.copy(
                        status = "Waking phone to launch ${request.taskName}",
                        lastLaunchSuccess = null,
                    )
                    if (wakeOnFailure) {
                        requestPhoneWake("Launch task")
                    }
                }
            },
            onSent = {
                if (pendingLaunch?.id == request.id) {
                    _state.value = _state.value.copy(
                        status = "Launch sent: ${request.taskName}",
                        lastLaunchSuccess = null,
                    )
                }
            },
        )
    }

    private fun beginLaunchRetry(request: PendingLaunchRequest) {
        launchRetryJob?.cancel()
        launchRetryJob = scope.launch {
            LAUNCH_RETRY_DELAYS_MS.forEachIndexed { index, delayMs ->
                delay(delayMs)
                val current = pendingLaunch ?: return@launch
                if (current.id != request.id) return@launch
                if (!_state.value.phoneConnected) {
                    requestPhoneWake("Launch task")
                } else if (index >= STALE_CONNECTED_RETRY_INDEX) {
                    restartBridgeForStaleTaskList()
                    requestPhoneWake("Stale launch retry")
                }
                attemptLaunchRequest(current, wakeOnFailure = false)
            }
            if (pendingLaunch?.id == request.id) {
                _state.value = _state.value.copy(
                    status = "Phone link not connected",
                    lastLaunchSuccess = false,
                )
                clearPendingLaunch(request.id)
            }
        }
    }

    private fun retryPendingLaunch() {
        val request = pendingLaunch ?: return
        attemptLaunchRequest(request, wakeOnFailure = false)
    }

    private fun clearPendingLaunch(id: String) {
        if (pendingLaunch?.id != id) return
        pendingLaunch = null
        launchRetryJob?.cancel()
        launchRetryJob = null
    }

    private fun moveMenuSelection(delta: Int) {
        val current = _state.value
        if (current.tasks.isEmpty() || current.menuModel().rowCount == 0) return
        applyMenuSelection(
            selection = current.menuModel().move(delta),
            notifyTaskSelection = current.viewMode == HelperViewMode.TASKS,
        )
    }

    private fun enterProjectAt(index: Int) {
        val current = _state.value
        val model = current.menuModel()
        if (model.projects.isEmpty()) return
        applyMenuSelection(
            selection = model.enterProject(index),
            notifyTaskSelection = true,
        )
    }

    private fun applyMenuSelection(
        selection: HudMenuSelection,
        notifyTaskSelection: Boolean,
    ) {
        val current = _state.value
        val next = current.applyMenuSelection(selection)
        _state.value = next.copy(status = next.menuStatus())
        if (notifyTaskSelection && selection.viewMode == HelperViewMode.TASKS) {
            val selected = next.menuModel().selectedTask()
            sendSelectionChanged(selected?.index ?: 0, selected?.task?.name.orEmpty())
        }
    }

    private fun sendSelectionChanged(
        safeIndex: Int,
        taskName: String,
    ) {
        sendStatus(
            StatusMessage(
                type = StatusType.SELECTION_CHANGED,
                taskName = taskName,
                selectedIndex = safeIndex,
                message = "Selection changed",
            ),
        )
    }

    private fun handleControlMessage(message: ControlMessage) {
        when (message) {
            is ControlMessage.Hello -> {
                _state.value = _state.value.copy(status = "Phone connected")
            }
            is ControlMessage.Unknown -> {
                _state.value = _state.value.copy(status = "Ignored unknown phone message")
            }
            is ControlMessage.TaskList -> {
                taskListReceived = true
                lastTaskListReceivedAtMs = SystemClock.elapsedRealtime()
                taskRequestRetryJob?.cancel()
                val current = _state.value
                val currentTaskName = current.tasks.getOrNull(current.selectedIndex)?.name
                val safeMessageIndex = min(
                    max(0, message.selectedIndex),
                    max(0, message.tasks.lastIndex),
                )
                val preservedIndex = currentTaskName
                    ?.let { name -> message.tasks.indexOfFirst { it.name == name } }
                    ?.takeIf { it >= 0 }
                val selected = min(
                    max(0, preservedIndex ?: safeMessageIndex),
                    max(0, message.tasks.lastIndex),
                )
                val projects = message.tasks.taskProjects()
                val selectedProjectName = message.tasks.getOrNull(selected)?.projectGroupName().orEmpty()
                val preferredProjectName = current.selectedProjectName.takeIf { name ->
                    projects.any { it.name == name }
                } ?: selectedProjectName
                val projectIndex = projects.projectIndexFor(preferredProjectName)
                val viewMode = if (
                    current.viewMode == HelperViewMode.TASKS &&
                    projects.getOrNull(projectIndex)?.name == preferredProjectName
                ) {
                    HelperViewMode.TASKS
                } else {
                    HelperViewMode.PROJECTS
                }
                _state.value = current.copy(
                    tasks = message.tasks,
                    selectedIndex = selected,
                    viewMode = viewMode,
                    selectedProjectIndex = projectIndex,
                    selectedProjectName = projects.getOrNull(projectIndex)?.name.orEmpty(),
                    taskerInstalled = message.taskerInstalled,
                    taskerEnabled = message.taskerEnabled,
                    externalAccess = message.externalAccess,
                    status = message.message.ifBlank { "${message.tasks.size} tasks" },
                )
            }
            is ControlMessage.LaunchResult -> {
                val launch = pendingLaunch
                val matchesPendingLaunch = launch != null &&
                    (
                        message.requestId.isNotBlank() && message.requestId == launch.id ||
                            message.requestId.isBlank() && message.taskName == launch.taskName
                    )
                _state.value = _state.value.copy(
                    lastLaunchTask = message.taskName,
                    lastLaunchSuccess = message.success,
                    status = message.message.ifBlank {
                        if (message.success) "Launched ${message.taskName}" else "Launch failed"
                    },
                )
                if (matchesPendingLaunch) {
                    clearPendingLaunch(launch.id)
                    if (message.success) {
                        launch.onLaunchSucceeded()
                    }
                }
            }
            is ControlMessage.SetStatus -> {
                _state.value = _state.value.copy(status = message.message)
            }
            is ControlMessage.Ping -> {
                sendStatus(StatusMessage(StatusType.PONG, message.nonce))
            }
        }
    }

    private fun beginTaskRequestRetry() {
        taskRequestRetryJob?.cancel()
        taskRequestRetryJob = scope.launch {
            REQUEST_RETRY_DELAYS_MS.forEachIndexed { index, delayMs ->
                delay(delayMs)
                if (taskListReceived) return@launch
                if (!_state.value.phoneConnected) {
                    requestPhoneWake("Retry wake")
                } else if (index >= STALE_CONNECTED_RETRY_INDEX) {
                    restartBridgeForStaleTaskList()
                    requestPhoneWake("Stale Bluetooth retry")
                }
                sendTaskRequest("Need task list")
            }
        }
    }

    private fun restartBridgeForStaleTaskList() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBridgeRestartAtMs < BRIDGE_RESTART_MIN_INTERVAL_MS) return
        lastBridgeRestartAtMs = now
        _state.value = _state.value.copy(bridgeState = "Reconnecting phone Bluetooth")
        bridge.restart("Reconnecting phone Bluetooth")
    }

    private fun requestPhoneWake(reason: String) {
        BleWakeAdvertiser.pulse(appContext) { message ->
            if (started) {
                _state.value = _state.value.copy(bridgeState = message)
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (wakeRequestInFlight || now - lastWakeRequestAtMs < WAKE_REQUEST_MIN_INTERVAL_MS) {
            sendTaskRequest(reason)
            return
        }
        wakeRequestInFlight = true
        lastWakeRequestAtMs = now
        BleWakeClient.requestWake(appContext) { ok, message ->
            onMain {
                wakeRequestInFlight = false
                if (!started) return@onMain
                _state.value = _state.value.copy(bridgeState = message)
                requestTasks(if (ok) "Phone wake" else reason)
            }
        }
    }

    private fun sendTaskRequest(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTaskRequestAtMs < TASK_REQUEST_MIN_INTERVAL_MS) return
        lastTaskRequestAtMs = now
        sendStatus(StatusMessage(StatusType.REQUEST_TASKS, reason)) {
            _state.value = _state.value.copy(bridgeState = "Waiting for phone link")
        }
    }

    private fun sendStatus(
        message: StatusMessage,
        onNotSent: (() -> Unit)? = null,
        onSent: (() -> Unit)? = null,
    ) {
        scope.launch {
            val sent = bridge.send(message) || cxr.send(message)
            if (!sent) {
                onNotSent?.invoke()
            } else {
                onSent?.invoke()
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    companion object {
        @Volatile
        private var instance: HelperRuntime? = null

        fun get(context: Context): HelperRuntime {
            return instance ?: synchronized(this) {
                instance ?: HelperRuntime(context.applicationContext).also { instance = it }
            }
        }

        private const val TASK_REQUEST_MIN_INTERVAL_MS = 1_000L
        private const val WAKE_REQUEST_MIN_INTERVAL_MS = 12_000L
        private const val BRIDGE_RESTART_MIN_INTERVAL_MS = 12_000L
        private const val FRESH_TASK_LIST_WINDOW_MS = 5_000L
        private const val STALE_CONNECTED_RETRY_INDEX = 1
        private val LAUNCH_RETRY_DELAYS_MS = longArrayOf(
            1_000L,
            2_500L,
            5_000L,
            9_000L,
            15_000L,
            25_000L,
            40_000L,
            60_000L,
            90_000L,
            120_000L,
        )
        private val REQUEST_RETRY_DELAYS_MS = longArrayOf(
            1_500L,
            3_500L,
            7_000L,
            12_000L,
            20_000L,
            30_000L,
            45_000L,
            60_000L,
            90_000L,
            120_000L,
        )
    }
}

private fun HelperUiState.menuStatus(): String {
    val model = menuModel()
    return when (viewMode) {
        HelperViewMode.PROJECTS -> {
            model.selectedProject
                ?.let { "${it.displayName}: ${it.taskIndices.size} tasks" }
                ?: projectListStatus()
        }
        HelperViewMode.TASKS -> "Ready"
    }
}

private fun HelperUiState.projectListStatus(): String {
    val count = menuModel().projects.size
    return if (count == 1) "1 project" else "$count projects"
}
