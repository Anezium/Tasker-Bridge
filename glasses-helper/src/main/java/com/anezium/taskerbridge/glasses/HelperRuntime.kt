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

    private var started = false
    private var taskListReceived = false
    private var wakeRequestInFlight = false
    private var taskRequestRetryJob: Job? = null
    private var lastTaskRequestAtMs = 0L
    private var lastWakeRequestAtMs = 0L
    private var lastTaskListReceivedAtMs = 0L

    private val _state = MutableStateFlow(HelperUiState())
    val state: StateFlow<HelperUiState> = _state.asStateFlow()

    fun start() {
        if (!started) {
            started = true
            _state.value = _state.value.copy(bridgeState = "Waking phone")
            bridge.start()
            requestPhoneWake("HUD opened")
            return
        }
        requestTasks("HUD opened")
    }

    fun close() {
        taskRequestRetryJob?.cancel()
        BleWakeClient.cancel()
        scope.launch {
            bridge.send(StatusMessage(StatusType.READY, "Helper closing"))
            bridge.stop()
            started = false
        }
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
        _state.value = _state.value.copy(
            phoneConnected = state.connected,
            phoneName = state.peerName,
            bridgeState = state.status,
        )
        if (state.connected && !wasConnected) {
            sendStatus(StatusMessage(StatusType.READY, "Helper ready over Bluetooth"))
            requestTasks("Bluetooth connected")
        }
    }

    private fun sendLaunchRequest(
        task: TaskerTask,
        selectedIndex: Int,
        onLaunchRequestSent: () -> Unit,
    ) {
        sendStatus(
            StatusMessage(
                type = StatusType.LAUNCH_TASK,
                taskName = task.name,
                selectedIndex = selectedIndex,
                message = "Launch requested",
            ),
            onNotSent = {
                _state.value = _state.value.copy(
                    status = "Phone Bluetooth not connected",
                    lastLaunchSuccess = false,
                )
            },
            onSent = onLaunchRequestSent,
        )
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
                _state.value = _state.value.copy(
                    lastLaunchTask = message.taskName,
                    lastLaunchSuccess = message.success,
                    status = message.message.ifBlank {
                        if (message.success) "Launched ${message.taskName}" else "Launch failed"
                    },
                )
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
            REQUEST_RETRY_DELAYS_MS.forEach { delayMs ->
                delay(delayMs)
                if (taskListReceived) return@launch
                if (!_state.value.phoneConnected) {
                    requestPhoneWake("Retry wake")
                }
                sendTaskRequest("Need task list")
            }
        }
    }

    private fun requestPhoneWake(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (wakeRequestInFlight || now - lastWakeRequestAtMs < WAKE_REQUEST_MIN_INTERVAL_MS) {
            requestTasks(reason)
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
            _state.value = _state.value.copy(bridgeState = "Waiting for phone Bluetooth")
        }
    }

    private fun sendStatus(
        message: StatusMessage,
        onNotSent: (() -> Unit)? = null,
        onSent: (() -> Unit)? = null,
    ) {
        scope.launch {
            if (!bridge.send(message)) {
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
        private const val FRESH_TASK_LIST_WINDOW_MS = 5_000L
        private val REQUEST_RETRY_DELAYS_MS = longArrayOf(1_500L, 3_500L, 7_000L, 12_000L, 20_000L)
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
