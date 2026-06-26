package com.anezium.taskerbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.JsonProtocol
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

data class BluetoothServerState(
    val active: Boolean,
    val connected: Boolean,
    val paired: Boolean,
    val pairingMode: Boolean,
    val peerName: String = "",
    val peerAddress: String = "",
    val status: String = "",
)

class BluetoothBridgeServer(
    context: Context,
    private val onState: (BluetoothServerState) -> Unit,
    private val onMessage: (StatusMessage) -> Unit,
    private val onLog: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceUuid = UUID.fromString(Protocol.BLUETOOTH_SERVICE_UUID)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val writeLock = Any()
    private val connectionLock = Any()

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var callbackJob: Job? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var lastListenerRefreshAtMs: Long = 0L

    @Volatile
    private var lastConnectionOpenedAtMs: Long = 0L

    @Volatile
    private var lastInboundAtMs: Long = 0L

    fun start() {
        if (connectJob?.isActive == true) return
        lastListenerRefreshAtMs = SystemClock.elapsedRealtime()
        val newJob = scope.launch(start = CoroutineStart.LAZY) { acceptLoop() }
        connectJob = newJob
        newJob.start()
    }

    fun restart() {
        val oldJob = connectJob
        val oldCallbackJob = callbackJob
        connectJob = null
        callbackJob = null
        lastListenerRefreshAtMs = SystemClock.elapsedRealtime()
        closeSocket()
        closeServer()
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            oldJob?.cancelAndJoin()
            acceptLoop()
        }
        val newCallbackJob = scope.launch(start = CoroutineStart.LAZY) {
            oldCallbackJob?.cancelAndJoin()
            callbackLoop()
        }
        connectJob = newJob
        callbackJob = newCallbackJob
        newJob.start()
        newCallbackJob.start()
    }

    fun refreshIdleListener(reason: String): Boolean {
        if (socket != null) return false
        val oldJob = connectJob
        connectJob = null
        lastListenerRefreshAtMs = SystemClock.elapsedRealtime()
        closeServer()
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            oldJob?.cancelAndJoin()
            acceptLoop()
        }
        connectJob = newJob
        newJob.start()
        onLog("Bluetooth listener refreshed: $reason")
        BridgeDiagnostics.record(appContext, "RFCOMM listener refreshed: $reason")
        return true
    }

    fun ensureFreshIdleListener(maxAgeMs: Long): Boolean {
        if (socket != null) return false
        val now = SystemClock.elapsedRealtime()
        val active = connectJob?.isActive == true
        if (!active || lastListenerRefreshAtMs == 0L || now - lastListenerRefreshAtMs >= maxAgeMs) {
            return refreshIdleListener("idle watchdog")
        }
        return false
    }

    fun closeStaleConnection(maxIdleMs: Long): Boolean {
        if (socket == null) return false
        val now = SystemClock.elapsedRealtime()
        val lastInbound = lastInboundAtMs.takeIf { it > 0L } ?: lastConnectionOpenedAtMs
        if (lastInbound <= 0L || now - lastInbound < maxIdleMs) return false
        val idleLabel = durationLabel(now - lastInbound)
        BridgeDiagnostics.record(appContext, "RFCOMM stale connection closed after $idleLabel")
        onLog("Bluetooth stale HUD connection closed.")
        closeSocket()
        onState(disconnectedState("Stale HUD connection closed"))
        return true
    }

    fun listenerSummary(): String {
        val last = lastListenerRefreshAtMs
        if (last == 0L) return "rfcomm=never"
        val ageSeconds = ((SystemClock.elapsedRealtime() - last).coerceAtLeast(0L)) / 1000L
        val age = durationLabel(ageSeconds * 1000L)
        val mode = when {
            socket != null -> "connected"
            connectJob?.isActive == true -> "listening"
            else -> "stopped"
        }
        val inbound = if (socket != null) {
            val inboundAge = lastInboundAtMs.takeIf { it > 0L }
                ?.let { " inbound=${durationLabel(SystemClock.elapsedRealtime() - it)}" }
                .orEmpty()
            inboundAge
        } else {
            ""
        }
        return "rfcomm=$mode ${age} ago$inbound"
    }

    fun stop() {
        val job = connectJob
        val dialJob = callbackJob
        connectJob = null
        callbackJob = null
        closeSocket()
        closeServer()
        scope.launch {
            job?.cancelAndJoin()
            dialJob?.cancelAndJoin()
            if (connectJob == null && callbackJob == null) {
                onState(
                    BluetoothServerState(
                        active = false,
                        connected = false,
                        paired = hasTrustedGlasses(),
                        pairingMode = false,
                        status = "Bluetooth stopped",
                    ),
                )
            }
        }
    }

    fun stopSessionCallback() {
        val dialJob = callbackJob
        callbackJob = null
        closePendingSocket()
        scope.launch {
            dialJob?.cancelAndJoin()
        }
    }

    fun forgetPairing() {
        prefs.edit()
            .remove(KEY_TRUSTED_GLASSES_ADDRESS)
            .remove(KEY_LAST_GLASSES_ADDRESS)
            .apply()
        closeSocket()
        onState(
            BluetoothServerState(
                active = connectJob?.isActive == true,
                connected = false,
                paired = false,
                pairingMode = connectJob?.isActive == true,
                status = "Bluetooth pairing cleared",
            ),
        )
    }

    suspend fun send(message: ControlMessage): Boolean = withContext(Dispatchers.IO) {
        val raw = JsonProtocol.encodeControl(message)
        val activeWriter = writer ?: return@withContext false
        runCatching {
            writeLine(activeWriter, raw)
            true
        }.getOrElse { error ->
            onError("Bluetooth send failed.", error)
            closeSocket()
            onState(disconnectedState("Bluetooth send failed"))
            false
        }
    }

    private suspend fun acceptLoop() {
        val loopJob = currentCoroutineContext()[Job]
        while (serverLoopActive(loopJob)) {
            val acceptedSocket = waitForGlasses(loopJob)
            if (acceptedSocket == null) {
                if (!serverLoopActive(loopJob)) break
                delay(SERVER_RETRY_DELAY_MS)
                continue
            }
            if (!serverLoopActive(loopJob)) {
                runCatching { acceptedSocket.close() }
                break
            }
            if (!claimSocket(acceptedSocket)) {
                runCatching { acceptedSocket.close() }
                continue
            }
            val activeWriter = BufferedWriter(OutputStreamWriter(acceptedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(acceptedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = acceptedSocket.remoteDevice.safeName()
            val peerAddress = acceptedSocket.remoteDevice.safeAddress()
            if (!performHandshake(acceptedSocket, reader, activeWriter)) {
                onLog("Rejected incompatible Bluetooth HUD.")
                releaseSocket(acceptedSocket)
                continue
            }
            rememberTrustedGlasses(peerAddress)
            onState(
                BluetoothServerState(
                    active = true,
                    connected = true,
                    paired = true,
                    pairingMode = false,
                    peerName = peerName,
                    peerAddress = peerAddress,
                    status = "HUD connected",
                ),
            )
            onLog("Bluetooth HUD connected.")
            BridgeDiagnostics.record(appContext, "HUD RFCOMM connected")
            try {
                readGlasses(reader) { serverLoopActive(loopJob) && socket === acceptedSocket }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (serverLoopActive(loopJob)) {
                    Log.d(TAG, "Bluetooth HUD disconnected", error)
                    onLog("Bluetooth HUD disconnected.")
                }
            }
            releaseSocket(acceptedSocket)
            if (serverLoopActive(loopJob)) {
                onState(
                    BluetoothServerState(
                        active = true,
                        connected = false,
                        paired = hasTrustedGlasses(),
                        pairingMode = !hasTrustedGlasses(),
                        status = "Waiting for HUD",
                    ),
                )
            }
        }
    }

    private suspend fun callbackLoop() {
        val loopJob = currentCoroutineContext()[Job]
        var attempts = 0
        while (callbackLoopActive(loopJob) && attempts < CALLBACK_CONNECT_ATTEMPTS) {
            if (socket != null) break
            val connectedSocket = connectToHudCallback(loopJob)
            if (connectedSocket == null) {
                attempts += 1
                if (!callbackLoopActive(loopJob)) break
                delay(CALLBACK_RETRY_DELAY_MS)
                continue
            }
            if (!callbackLoopActive(loopJob)) {
                runCatching { connectedSocket.close() }
                break
            }
            if (!claimSocket(connectedSocket)) {
                runCatching { connectedSocket.close() }
                break
            }
            val activeWriter = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = connectedSocket.remoteDevice.safeName()
            val peerAddress = connectedSocket.remoteDevice.safeAddress()
            if (!performHandshake(connectedSocket, reader, activeWriter)) {
                releaseSocket(connectedSocket)
                onLog("Rejected incompatible Bluetooth HUD callback.")
                attempts += 1
                delay(CALLBACK_RETRY_DELAY_MS)
                continue
            }
            rememberTrustedGlasses(peerAddress)
            onState(
                BluetoothServerState(
                    active = true,
                    connected = true,
                    paired = true,
                    pairingMode = false,
                    peerName = peerName,
                    peerAddress = peerAddress,
                    status = "HUD connected",
                ),
            )
            onLog("Bluetooth HUD connected by callback.")
            BridgeDiagnostics.record(appContext, "HUD RFCOMM callback connected")
            try {
                readGlasses(reader) { callbackLoopActive(loopJob) && socket === connectedSocket }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (callbackLoopActive(loopJob)) {
                    Log.d(TAG, "Bluetooth HUD callback disconnected", error)
                    onLog("Bluetooth HUD disconnected.")
                }
            }
            releaseSocket(connectedSocket)
            break
        }
    }

    @SuppressLint("MissingPermission")
    private fun waitForGlasses(loopJob: Job?): BluetoothSocket? {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            onState(
                BluetoothServerState(
                    active = false,
                    connected = false,
                    paired = hasTrustedGlasses(),
                    pairingMode = false,
                    status = "Bluetooth unavailable",
                ),
            )
            onError("Bluetooth is not available on this phone.", null)
            return null
        }
        if (!hasConnectPermission()) {
            onState(
                BluetoothServerState(
                    active = false,
                    connected = false,
                    paired = hasTrustedGlasses(),
                    pairingMode = false,
                    status = "Bluetooth permission missing",
                ),
            )
            onError("Grant Bluetooth permission to Tasker Bridge.", null)
            return null
        }
        if (!adapter.isEnabled) {
            onState(
                BluetoothServerState(
                    active = false,
                    connected = false,
                    paired = hasTrustedGlasses(),
                    pairingMode = false,
                    status = "Bluetooth disabled",
                ),
            )
            onError("Turn on Bluetooth before starting Tasker Bridge.", null)
            return null
        }

        var listener: BluetoothServerSocket? = null
        return runCatching {
            closeServer()
            listener = adapter.listenUsingRfcommWithServiceRecord(
                Protocol.BLUETOOTH_SERVICE_NAME,
                serviceUuid,
            )
            val activeListener = listener ?: return@runCatching null
            serverSocket = activeListener
            lastListenerRefreshAtMs = SystemClock.elapsedRealtime()
            val paired = hasTrustedGlasses()
            onState(
                BluetoothServerState(
                    active = true,
                    connected = false,
                    paired = paired,
                    pairingMode = !paired,
                    status = if (paired) "Waiting for paired HUD" else "Launch HUD to pair Bluetooth",
                ),
            )
            onLog("Bluetooth phone server listening.")
            BridgeDiagnostics.record(appContext, "RFCOMM listener waiting")
            val accepted = activeListener.accept() ?: return@runCatching null
            closeServer(activeListener)
            if (!serverLoopActive(loopJob)) {
                runCatching { accepted.close() }
                return@runCatching null
            }
            if (!isTrustedGlasses(accepted)) {
                runCatching { accepted.close() }
                onLog("Rejected unpaired Bluetooth HUD.")
                return@runCatching null
            }
            accepted
        }.getOrElse { error ->
            closeServer(listener)
            if (serverLoopActive(loopJob)) {
                Log.d(TAG, "Bluetooth listener failed", error)
                onError("Bluetooth listener failed.", error)
            }
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToHudCallback(loopJob: Job?): BluetoothSocket? {
        if (!callbackLoopActive(loopJob)) return null
        val adapter = bluetoothAdapter() ?: return null
        if (!hasConnectPermission() || !adapter.isEnabled) return null
        val trustedAddress = trustedGlassesAddress()
        if (trustedAddress.isBlank()) return null
        val device = runCatching { adapter.getRemoteDevice(trustedAddress) }
            .getOrNull()
            ?: return null
        onState(
            BluetoothServerState(
                active = true,
                connected = false,
                paired = true,
                pairingMode = false,
                status = "Calling paired HUD",
            ),
        )
        runCatching { adapter.cancelDiscovery() }
        var candidate: BluetoothSocket? = null
        var timeoutJob: Job? = null
        val attemptActive = java.util.concurrent.atomic.AtomicBoolean(true)
        return try {
            candidate = device.createRfcommSocketToServiceRecord(serviceUuid)
            pendingSocket = candidate
            timeoutJob = scope.launch {
                delay(CALLBACK_CONNECT_TIMEOUT_MS)
                if (attemptActive.get() && pendingSocket === candidate) {
                    Log.w(TAG, "Bluetooth HUD callback timed out")
                    runCatching { candidate?.close() }
                }
            }
            candidate.connect()
            candidate
        } catch (error: Throwable) {
            Log.d(TAG, "Bluetooth HUD callback failed", error)
            runCatching { candidate?.close() }
            null
        } finally {
            attemptActive.set(false)
            if (pendingSocket === candidate) pendingSocket = null
            timeoutJob?.cancel()
        }
    }

    private fun performHandshake(
        acceptedSocket: BluetoothSocket,
        reader: BufferedReader,
        activeWriter: BufferedWriter,
    ): Boolean {
        val handshakeActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val timeoutJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (handshakeActive.get() && socket === acceptedSocket) {
                Log.w(TAG, "Bluetooth HUD handshake timed out")
                runCatching { acceptedSocket.close() }
            }
        }
        val result = runCatching {
            val raw = reader.readLine() ?: return@runCatching false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return@runCatching false
            lastInboundAtMs = SystemClock.elapsedRealtime()
            val hello = JsonProtocol.decodeStatus(raw)
            if (hello.type != StatusType.HELLO
                || hello.protocolVersion != Protocol.PROTOCOL_VERSION
                || hello.peerRole != Protocol.HELPER_ROLE
            ) {
                return@runCatching false
            }
            writeLine(
                activeWriter,
                JsonProtocol.encodeControl(
                    ControlMessage.Hello(
                        appVersion = "phone",
                        peerRole = Protocol.PHONE_ROLE,
                    ),
                ),
            )
            true
        }.getOrElse { error ->
            Log.d(TAG, "Bluetooth handshake failed", error)
            false
        }
        handshakeActive.set(false)
        timeoutJob.cancel()
        return result
    }

    private fun readGlasses(
        reader: BufferedReader,
        active: () -> Boolean,
    ) {
        reader.use {
            while (active()) {
                val raw = it.readLine() ?: break
                lastInboundAtMs = SystemClock.elapsedRealtime()
                if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) {
                    onError("Bluetooth message was too large.", null)
                    continue
                }
                runCatching {
                    JsonProtocol.decodeStatus(raw)
                }.onSuccess { message ->
                    onMessage(message)
                }.onFailure { error ->
                    Log.w(TAG, "Bad Bluetooth payload", error)
                    onError("Could not parse Bluetooth message.", error)
                }
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        return manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeName(): String =
        runCatching { this?.name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeAddress(): String =
        runCatching { this?.address.orEmpty() }.getOrDefault("")

    private fun trustedGlassesAddress(): String =
        prefs.getString(KEY_TRUSTED_GLASSES_ADDRESS, "").orEmpty()

    private fun hasTrustedGlasses(): Boolean {
        migrateTrustedGlasses()
        return trustedGlassesAddress().isNotBlank()
    }

    private fun isTrustedGlasses(socket: BluetoothSocket): Boolean {
        migrateTrustedGlasses()
        val trustedAddress = trustedGlassesAddress()
        if (trustedAddress.isBlank()) return true
        val address = socket.remoteDevice.safeAddress()
        return address.equals(trustedAddress, ignoreCase = true)
    }

    private fun migrateTrustedGlasses() {
        if (trustedGlassesAddress().isNotBlank()) return
        val legacyAddress = prefs.getString(KEY_LAST_GLASSES_ADDRESS, "").orEmpty()
        if (legacyAddress.isBlank()) return
        prefs.edit()
            .putString(KEY_TRUSTED_GLASSES_ADDRESS, legacyAddress)
            .apply()
    }

    private fun rememberTrustedGlasses(address: String) {
        if (address.isBlank()) return
        prefs.edit()
            .putString(KEY_TRUSTED_GLASSES_ADDRESS, address)
            .putString(KEY_LAST_GLASSES_ADDRESS, address)
            .apply()
    }

    private fun serverLoopActive(loopJob: Job?): Boolean =
        connectJob === loopJob && loopJob?.isActive == true

    private fun callbackLoopActive(loopJob: Job?): Boolean =
        callbackJob === loopJob && loopJob?.isActive == true

    private fun claimSocket(candidate: BluetoothSocket): Boolean =
        synchronized(connectionLock) {
            if (socket != null) {
                false
            } else {
                val now = SystemClock.elapsedRealtime()
                lastConnectionOpenedAtMs = now
                lastInboundAtMs = now
                socket = candidate
                true
            }
        }

    private fun releaseSocket(target: BluetoothSocket) {
        synchronized(connectionLock) {
            if (socket !== target) {
                runCatching { target.close() }
                return
            }
            synchronized(writeLock) {
                runCatching { writer?.close() }
                writer = null
            }
            runCatching { target.close() }
            socket = null
            lastConnectionOpenedAtMs = 0L
            lastInboundAtMs = 0L
        }
    }

    private fun closeServer(target: BluetoothServerSocket? = null) {
        val activeServer = serverSocket
        if (target != null && activeServer !== target) {
            runCatching { target.close() }
            return
        }
        runCatching { activeServer?.close() }
        if (serverSocket === activeServer) {
            serverSocket = null
        }
    }

    private fun closeSocket() {
        synchronized(connectionLock) {
            synchronized(writeLock) {
                runCatching { writer?.close() }
                writer = null
            }
            closePendingSocket()
            runCatching { socket?.close() }
            socket = null
            lastConnectionOpenedAtMs = 0L
            lastInboundAtMs = 0L
        }
    }

    private fun closePendingSocket() {
        val pending = pendingSocket
        pendingSocket = null
        runCatching { pending?.close() }
    }

    private fun writeLine(activeWriter: BufferedWriter, raw: String) {
        synchronized(writeLock) {
            activeWriter.write(raw)
            activeWriter.newLine()
            activeWriter.flush()
        }
    }

    private fun disconnectedState(status: String): BluetoothServerState =
        BluetoothServerState(
            active = connectJob?.isActive == true,
            connected = false,
            paired = hasTrustedGlasses(),
            pairingMode = connectJob?.isActive == true && !hasTrustedGlasses(),
            status = status,
        )

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

    companion object {
        private const val TAG = "TaskerBridge-BT"
        private const val PREFS_NAME = "tasker_bridge_bluetooth"
        private const val KEY_TRUSTED_GLASSES_ADDRESS = "trusted_glasses_address"
        private const val KEY_LAST_GLASSES_ADDRESS = "last_glasses_address"
        private const val SERVER_RETRY_DELAY_MS = 30_000L
        private const val CALLBACK_RETRY_DELAY_MS = 5_000L
        private const val CALLBACK_CONNECT_TIMEOUT_MS = 12_000L
        private const val CALLBACK_CONNECT_ATTEMPTS = 8
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
    }
}
