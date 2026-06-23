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
    private var authorizationRequestInFlight = false
    private var lastAuthorizationRequestAtMs = 0L
    private var lastHudTaskRequestAtMs = 0L
    private var lastTaskListSentAtMs = 0L
    private var lastTaskerSnapshot: TaskerSnapshot? = null
    private val cxrSetup = CxrSetupCoordinator(CONNECT_COOLDOWN_MS)

    private val bluetooth = BluetoothBridgeServer(
        context = appContext,
        onState = { state -> onMain { handleBluetoothState(state) } },
        onMessage = { message -> onMain { handleHelperMessage(message) } },
        onLog = { message -> onMain { _state.value = _state.value.copy(lastStatus = message) } },
        onError = { message, _ -> onMain { setError(message) } },
    )

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
                if (authorized) {
                    continuePendingCxrSetup(forceConnect = true)
                } else {
                    cxrSetup.cancel()
                }
            }
        },
        onConnectionChanged = { cxrConnected, btConnected ->
            onMain {
                _state.value = _state.value.copy(
                    authorized = _state.value.authorized || cxrConnected || btConnected,
                    cxrConnected = cxrConnected,
                    glassBtConnected = btConnected,
                )
                continuePendingCxrSetup()
            }
        },
        onInstallStatus = { message, busy ->
            onMain {
                _state.value = _state.value.copy(
                    helperInstallStatus = message,
                    helperInstallBusy = busy,
                    lastStatus = message,
                )
            }
        },
        onHelperInstalled = { success ->
            onMain {
                updateHelperVersionState()
                cxrSetup.finishOperation()
                if (success) {
                    _state.value = _state.value.copy(
                        lastStatus = "HUD installed. Launch it when you are ready.",
                    )
                }
                releaseCxrSoon()
            }
        },
        onHelperOpened = { opened ->
            onMain {
                cxrSetup.finishOperation()
                if (opened) {
                    _state.value = _state.value.copy(
                        lastStatus = "HUD opened. Bluetooth will handle Tasker commands.",
                    )
                }
                releaseCxrSoon()
            }
        },
        onLog = { message -> onMain { _state.value = _state.value.copy(lastStatus = message) } },
        onError = { message, _ -> onMain { setError(message) } },
    )

    fun start() {
        if (started) return
        started = true
        _state.value = _state.value.copy(
            requiredRokidAppInstalled = cxr.isRequiredRokidAppInstalled(appContext),
            authorized = cxr.isAuthorized(),
            helperBundledVersion = cxr.bundledHelperVersionLabel(),
            helperLastInstalledVersion = cxr.lastInstalledHelperVersionLabel(),
            lastStatus = "Bridge runtime started.",
        )
        refreshTasker(sendToGlasses = false)
    }

    fun startBackground() {
        start()
        bluetooth.start()
        refreshTasker(sendToGlasses = false)
    }

    fun stopBackground() {
        bluetooth.stop()
        _state.value = _state.value.copy(
            bluetoothServerActive = false,
            bluetoothConnected = false,
            bluetoothPairingMode = false,
            bluetoothStatus = "Bluetooth stopped.",
        )
    }

    fun forgetBluetoothPairing() {
        bluetooth.forgetPairing()
        _state.value = _state.value.copy(
            bluetoothConnected = false,
            bluetoothPaired = false,
            bluetoothPairingMode = _state.value.bluetoothServerActive,
            bluetoothPeerName = "",
            bluetoothPeerAddress = "",
            bluetoothStatus = "Bluetooth pairing cleared.",
            lastStatus = "Bluetooth pairing cleared.",
        )
    }

    fun markServiceActive(active: Boolean) {
        _state.value = _state.value.copy(bridgeServiceActive = active)
    }

    fun installHudFromUi(activity: Activity) {
        start()
        cxrSetup.begin(CxrSetupAction.INSTALL)
        updateHelperVersionState()
        beginCxrSetup(activity)
    }

    fun launchHudFromUi(activity: Activity) {
        start()
        bluetooth.start()
        bluetooth.wakeForHudLaunch()
        cxrSetup.begin(CxrSetupAction.LAUNCH)
        beginCxrSetup(activity)
    }

    fun requestAuthorization(activity: Activity) {
        if (authorizationRequestInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuthorizationRequestAtMs < AUTH_REQUEST_COOLDOWN_MS) return
        lastAuthorizationRequestAtMs = now
        authorizationRequestInFlight = true
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
        scope.launch {
            sendSnapshotToGlasses(snapshot)
        }
    }

    fun runTaskFromPhone(taskName: String) {
        scope.launch {
            val result = tasker.runTask(taskName)
            _state.value = _state.value.copy(
                lastStatus = result.message,
                error = if (result.success) "" else result.message,
            )
            val delivered = bluetooth.send(
                ControlMessage.LaunchResult(
                    taskName = result.taskName,
                    success = result.success,
                    message = result.message,
                ),
            )
            if (!delivered) {
                _state.value = _state.value.copy(lastStatus = "Waiting for HUD Bluetooth connection.")
            }
            refreshTasker(sendToGlasses = false)
        }
    }

    private fun beginCxrSetup(activity: Activity) {
        if (!cxr.isAuthorized()) {
            _state.value = _state.value.copy(
                authorized = false,
                lastStatus = "Hi Rokid authorization is needed for HUD install/launch.",
            )
            requestAuthorization(activity)
            return
        }
        continuePendingCxrSetup(forceConnect = true)
    }

    private fun continuePendingCxrSetup(forceConnect: Boolean = false) {
        if (!cxr.isAuthorized()) {
            _state.value = _state.value.copy(
                authorized = false,
                lastStatus = "Hi Rokid authorization is required for setup.",
            )
            return
        }
        val setupConnected = _state.value.cxrConnected && _state.value.glassBtConnected
        when (val step = cxrSetup.nextStep(
            authorized = true,
            setupConnected = setupConnected,
            forceConnect = forceConnect,
        )) {
            CxrSetupStep.None -> Unit
            CxrSetupStep.Connect -> {
                _state.value = _state.value.copy(
                    authorized = true,
                    helperInstallStatus = "Connecting CXR-L for setup...",
                    lastStatus = "Connecting CXR-L for setup...",
                )
                cxr.connect()
            }
            is CxrSetupStep.Run -> when (step.action) {
                CxrSetupAction.INSTALL -> {
                    Log.i(TAG, "Installing helper through one-shot CXR-L")
                    cxr.installHelper(forceReinstall = true)
                }
                CxrSetupAction.LAUNCH -> {
                    Log.i(TAG, "Launching helper through one-shot CXR-L")
                    cxr.ensureHelperRunning()
                }
            }
        }
    }

    private fun handleBluetoothState(state: BluetoothServerState) {
        val wasConnected = _state.value.bluetoothConnected
        _state.value = _state.value.copy(
            bluetoothServerActive = state.active,
            bluetoothConnected = state.connected,
            bluetoothPaired = state.paired,
            bluetoothPairingMode = state.pairingMode,
            bluetoothPeerName = state.peerName,
            bluetoothPeerAddress = state.peerAddress,
            bluetoothStatus = state.status,
            lastStatus = state.status.ifBlank { _state.value.lastStatus },
        )
        if (state.connected && !wasConnected) {
            sendCachedTasksThenRefresh()
        }
    }

    private fun handleHelperMessage(message: StatusMessage) {
        when (message.type) {
            StatusType.READY -> {
                _state.value = _state.value.copy(
                    lastStatus = "HUD: ${message.message.ifBlank { message.type.name }}",
                )
                sendCachedTasksThenRefresh()
            }
            StatusType.REQUEST_TASKS -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHudTaskRequestAtMs < TASK_REQUEST_DEBOUNCE_MS) {
                    _state.value = _state.value.copy(lastStatus = "HUD task request already handled.")
                    return
                }
                lastHudTaskRequestAtMs = now
                _state.value = _state.value.copy(
                    lastStatus = "HUD: ${message.message.ifBlank { message.type.name }}",
                )
                sendCachedTasksThenRefresh()
            }
            StatusType.LAUNCH_TASK -> {
                _state.value = _state.value.copy(lastStatus = "HUD requested: ${message.taskName}")
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
            StatusType.ERROR -> setError(message.message)
            StatusType.HELLO -> {
                _state.value = _state.value.copy(lastStatus = "HUD Bluetooth handshake complete.")
            }
            StatusType.UNKNOWN -> {
                _state.value = _state.value.copy(lastStatus = "Ignored unknown HUD message.")
            }
            StatusType.PONG -> {
                _state.value = _state.value.copy(lastStatus = "HUD pong: ${message.message}")
            }
        }
    }

    private suspend fun sendSnapshotToGlasses(snapshot: TaskerSnapshot) {
        lastTaskListSentAtMs = SystemClock.elapsedRealtime()
        val sent = bluetooth.send(
            ControlMessage.TaskList(
                tasks = snapshot.tasks,
                selectedIndex = _state.value.selectedIndex.coerceInTaskBounds(snapshot.tasks),
                taskerInstalled = snapshot.installed,
                taskerEnabled = snapshot.enabled,
                externalAccess = snapshot.externalAccess && snapshot.runPermissionGranted,
                message = snapshot.message,
            ),
        )
        if (!sent) {
            _state.value = _state.value.copy(lastStatus = "Waiting for HUD Bluetooth connection.")
        }
    }

    private fun sendCachedTasksThenRefresh() {
        scope.launch {
            val cached = lastTaskerSnapshot
            if (cached == null) {
                refreshTasker(sendToGlasses = true)
                return@launch
            }
            val selected = _state.value.selectedIndex.coerceInTaskBounds(cached.tasks)
            sendSnapshotToGlasses(cached)
            refreshTasker(
                sendToGlasses = true,
                skipSendIfFingerprint = cached.fingerprint(selected),
            )
        }
    }

    private fun releaseCxrSoon() {
        scope.launch {
            delay(CXR_RELEASE_DELAY_MS)
            if (!cxrSetup.isIdle()) return@launch
            cxr.disconnect()
            _state.value = _state.value.copy(
                cxrConnected = false,
                glassBtConnected = false,
                helperInstallBusy = false,
                lastStatus = "CXR-L released; Bluetooth runtime remains active.",
            )
        }
    }

    private fun setError(message: String) {
        _state.value = _state.value.copy(error = message, lastStatus = message)
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun updateHelperVersionState() {
        _state.value = _state.value.copy(
            helperBundledVersion = cxr.bundledHelperVersionLabel(),
            helperLastInstalledVersion = cxr.lastInstalledHelperVersionLabel(),
        )
    }

    companion object {
        private const val TAG = "TaskerBridge-Runtime"
        private const val AUTH_REQUEST_COOLDOWN_MS = 2_500L
        private const val CONNECT_COOLDOWN_MS = 5_000L
        private const val TASK_REQUEST_DEBOUNCE_MS = 800L
        private const val CXR_RELEASE_DELAY_MS = 1_500L

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
