package com.anezium.taskerbridge.glasses

import android.os.SystemClock
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
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

class HelperRuntime private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bridge: CxrBridgeController
    private var started = false
    private var taskListReceived = false
    private var taskRequestRetryJob: Job? = null
    private var lastTaskRequestAtMs = 0L
    private var lastTaskListReceivedAtMs = 0L

    private val _state = MutableStateFlow(HelperUiState())
    val state: StateFlow<HelperUiState> = _state.asStateFlow()

    fun start() {
        if (started) {
            requestTasks()
            return
        }
        started = true
        bridge = CxrBridgeController(
            onControlMessage = ::handleControlMessage,
            onBridgeState = { message -> _state.value = _state.value.copy(bridgeState = message) },
            onPhoneAvailable = ::requestTasks,
        )
        bridge.start()
        requestTasks("HUD opened")
    }

    fun stop() {
        taskRequestRetryJob?.cancel()
        if (::bridge.isInitialized) {
            bridge.sendStatus(StatusMessage(StatusType.READY, "Helper closing"))
        }
    }

    fun resume() {
        if (::bridge.isInitialized) {
            requestTasks()
        }
    }

    fun nextTask() {
        val current = _state.value
        if (current.tasks.isEmpty()) return
        val next = (current.selectedIndex + 1).coerceAtMost(current.tasks.lastIndex)
        updateSelection(next)
    }

    fun previousTask() {
        val current = _state.value
        if (current.tasks.isEmpty()) return
        val next = (current.selectedIndex - 1).coerceAtLeast(0)
        updateSelection(next)
    }

    fun launchSelectedTask() {
        val current = _state.value
        val task = current.tasks.getOrNull(current.selectedIndex) ?: return
        _state.value = current.copy(status = "Requesting ${task.name}", lastLaunchTask = task.name, lastLaunchSuccess = null)
        bridge.sendStatus(
            StatusMessage(
                type = StatusType.LAUNCH_TASK,
                taskName = task.name,
                selectedIndex = current.selectedIndex,
                message = "Launch requested",
            ),
        )
    }

    fun requestTasks(reason: String = "Refresh requested") {
        val now = SystemClock.elapsedRealtime()
        if (taskListReceived && now - lastTaskListReceivedAtMs < FRESH_TASK_LIST_WINDOW_MS) return
        taskListReceived = false
        sendTaskRequest(reason)
        beginTaskRequestRetry()
    }

    private fun updateSelection(index: Int) {
        val current = _state.value
        val safeIndex = index.coerceIn(0, max(0, current.tasks.lastIndex))
        val task = current.tasks.getOrNull(safeIndex)
        _state.value = current.copy(selectedIndex = safeIndex)
        bridge.sendStatus(
            StatusMessage(
                type = StatusType.SELECTION_CHANGED,
                taskName = task?.name.orEmpty(),
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
                _state.value = current.copy(
                    tasks = message.tasks,
                    selectedIndex = selected,
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
                bridge.sendStatus(StatusMessage(StatusType.PONG, message.nonce))
            }
        }
    }

    private fun beginTaskRequestRetry() {
        taskRequestRetryJob?.cancel()
        taskRequestRetryJob = scope.launch {
            REQUEST_RETRY_DELAYS_MS.forEach { delayMs ->
                delay(delayMs)
                if (taskListReceived) return@launch
                sendTaskRequest("Need task list")
            }
        }
    }

    private fun sendTaskRequest(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTaskRequestAtMs < TASK_REQUEST_MIN_INTERVAL_MS) return
        lastTaskRequestAtMs = now
        val result = bridge.sendStatus(StatusMessage(StatusType.REQUEST_TASKS, reason))
        if (result == CxrBridgeController.BRIDGE_PHONE_NOT_READY) {
            _state.value = _state.value.copy(bridgeState = "Listening for phone push")
        }
    }

    companion object {
        @Volatile
        private var instance: HelperRuntime? = null

        fun get(): HelperRuntime {
            return instance ?: synchronized(this) {
                instance ?: HelperRuntime().also { instance = it }
            }
        }

        private const val TASK_REQUEST_MIN_INTERVAL_MS = 1_000L
        private const val FRESH_TASK_LIST_WINDOW_MS = 5_000L
        private val REQUEST_RETRY_DELAYS_MS = longArrayOf(1_500L, 3_500L, 7_000L)
    }
}
