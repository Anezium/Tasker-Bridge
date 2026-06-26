package com.anezium.taskerbridge.glasses

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

data class BluetoothClientState(
    val active: Boolean,
    val connected: Boolean,
    val peerName: String = "",
    val peerAddress: String = "",
    val status: String = "",
)

class BluetoothBridgeClient(
    context: Context,
    private val onState: (BluetoothClientState) -> Unit,
    private val onMessage: (ControlMessage) -> Unit,
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
    private var acceptJob: Job? = null

    @Volatile
    private var serverJob: Job? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var trustedConnectFailures: Int = 0

    @Volatile
    private var lastConnectAttemptUsedFallback: Boolean = false

    @Volatile
    private var nextFallbackSearchAtMs: Long = 0L

    fun start() {
        if (acceptJob?.isActive != true) {
            val newJob = scope.launch(start = CoroutineStart.LAZY) { connectLoop() }
            acceptJob = newJob
            newJob.start()
        }
        if (serverJob?.isActive != true) {
            val newServerJob = scope.launch(start = CoroutineStart.LAZY) { listenLoop() }
            serverJob = newServerJob
            newServerJob.start()
        }
    }

    fun restart(status: String = "Reconnecting phone") {
        val oldJob = acceptJob
        val oldServerJob = serverJob
        acceptJob = null
        serverJob = null
        closeSocket()
        closeServer()
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            oldJob?.cancelAndJoin()
            onState(BluetoothClientState(active = true, connected = false, status = status))
            connectLoop()
        }
        val newServerJob = scope.launch(start = CoroutineStart.LAZY) {
            oldServerJob?.cancelAndJoin()
            listenLoop()
        }
        acceptJob = newJob
        serverJob = newServerJob
        newJob.start()
        newServerJob.start()
    }

    fun stop() {
        val job = acceptJob
        val listenJob = serverJob
        acceptJob = null
        serverJob = null
        closeSocket()
        closeServer()
        scope.launch {
            job?.cancelAndJoin()
            listenJob?.cancelAndJoin()
            if (acceptJob == null && serverJob == null) {
                onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth stopped"))
            }
        }
    }

    suspend fun send(message: StatusMessage): Boolean = withContext(Dispatchers.IO) {
        val raw = JsonProtocol.encodeStatus(message)
        val activeWriter = writer ?: return@withContext false
        runCatching {
            writeLine(activeWriter, raw)
            true
        }.getOrElse { error ->
            onError("Bluetooth send failed.", error)
            closeSocket()
            false
        }
    }

    private suspend fun connectLoop() {
        val loopJob = currentCoroutineContext()[Job]
        while (connectLoopActive(loopJob)) {
            val connectedSocket = connectToPhone(loopJob)
            if (connectedSocket == null) {
                if (!connectLoopActive(loopJob)) break
                recordConnectFailure()
                delay(RECONNECT_DELAY_MS)
                continue
            }
            if (!connectLoopActive(loopJob)) {
                runCatching { connectedSocket.close() }
                break
            }
            if (!claimSocket(connectedSocket)) {
                runCatching { connectedSocket.close() }
                delay(RECONNECT_DELAY_MS)
                continue
            }
            val activeWriter = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = connectedSocket.remoteDevice.safeName()
            val peerAddress = connectedSocket.remoteDevice.safeAddress()
            if (!performHandshake(connectedSocket, activeWriter, reader)) {
                releaseSocket(connectedSocket)
                onLog("Rejected incompatible Bluetooth phone.")
                recordConnectFailure()
                delay(RECONNECT_DELAY_MS)
                continue
            }
            trustedConnectFailures = 0
            lastConnectAttemptUsedFallback = false
            nextFallbackSearchAtMs = 0L
            rememberTrustedPhone(peerAddress)
            onState(
                BluetoothClientState(
                    active = true,
                    connected = true,
                    peerName = peerName,
                    peerAddress = peerAddress,
                    status = "Phone connected",
                ),
            )
            onLog("Bluetooth phone connected.")
            try {
                readPhone(reader) { connectLoopActive(loopJob) && socket === connectedSocket }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (connectLoopActive(loopJob)) {
                    Log.d(TAG, "Bluetooth phone disconnected", error)
                    onLog("Bluetooth phone disconnected.")
                }
            }
            releaseSocket(connectedSocket)
            if (connectLoopActive(loopJob)) {
                onState(BluetoothClientState(active = true, connected = false, status = "Looking for phone"))
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPhone(loopJob: Job?): BluetoothSocket? {
        if (!connectLoopActive(loopJob)) return null
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth unavailable"))
            return null
        }
        if (!hasConnectPermission()) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth permission missing"))
            onError("Grant Bluetooth permission to the glasses helper.", null)
            return null
        }
        if (!adapter.isEnabled) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth disabled"))
            return null
        }

        val trustedAddress = trustedPhoneAddress()
        val includeFallback = shouldSearchFallbackPhones(trustedAddress)
        lastConnectAttemptUsedFallback = includeFallback
        val candidates = candidateDevices(adapter, trustedAddress, includeFallback)
        if (candidates.isEmpty()) {
            onState(BluetoothClientState(active = true, connected = false, status = "Pair phone first"))
            return null
        }

        runCatching { adapter.cancelDiscovery() }
        for (device in candidates) {
            if (!connectLoopActive(loopJob)) return null
            onState(
                BluetoothClientState(
                    active = true,
                    connected = false,
                    status = when {
                        trustedAddress.isBlank() -> "Pairing phone"
                        includeFallback -> "Searching paired phones"
                        else -> "Connecting phone"
                    },
                ),
            )
            var candidate: BluetoothSocket? = null
            var timeoutJob: Job? = null
            val attemptActive = java.util.concurrent.atomic.AtomicBoolean(true)
            val connected = try {
                if (!connectLoopActive(loopJob)) return null
                candidate = device.createRfcommSocketToServiceRecord(serviceUuid)
                pendingSocket = candidate
                timeoutJob = scope.launch {
                    delay(CONNECT_ATTEMPT_TIMEOUT_MS)
                    if (attemptActive.get() && pendingSocket === candidate) {
                        Log.w(TAG, "Bluetooth phone connect timed out")
                        runCatching { candidate?.close() }
                    }
                }
                candidate.connect()
                candidate
            } catch (error: Throwable) {
                Log.d(TAG, "Bluetooth connect failed for candidate phone", error)
                runCatching { candidate?.close() }
                null
            } finally {
                attemptActive.set(false)
                if (pendingSocket === candidate) pendingSocket = null
                timeoutJob?.cancel()
            }
            if (connected != null) return connected
        }
        return null
    }

    private suspend fun listenLoop() {
        val loopJob = currentCoroutineContext()[Job]
        while (serverLoopActive(loopJob)) {
            val acceptedSocket = waitForPhoneCallback(loopJob)
            if (acceptedSocket == null) {
                if (!serverLoopActive(loopJob)) break
                delay(RECONNECT_DELAY_MS)
                continue
            }
            if (!serverLoopActive(loopJob)) {
                runCatching { acceptedSocket.close() }
                break
            }
            if (!claimSocket(acceptedSocket)) {
                runCatching { acceptedSocket.close() }
                delay(RECONNECT_DELAY_MS)
                continue
            }
            val activeWriter = BufferedWriter(OutputStreamWriter(acceptedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(acceptedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = acceptedSocket.remoteDevice.safeName()
            val peerAddress = acceptedSocket.remoteDevice.safeAddress()
            if (!performHandshake(acceptedSocket, activeWriter, reader)) {
                releaseSocket(acceptedSocket)
                onLog("Rejected incompatible Bluetooth callback.")
                delay(RECONNECT_DELAY_MS)
                continue
            }
            trustedConnectFailures = 0
            lastConnectAttemptUsedFallback = false
            nextFallbackSearchAtMs = 0L
            rememberTrustedPhone(peerAddress)
            onState(
                BluetoothClientState(
                    active = true,
                    connected = true,
                    peerName = peerName,
                    peerAddress = peerAddress,
                    status = "Phone connected",
                ),
            )
            onLog("Bluetooth phone connected by callback.")
            try {
                readPhone(reader) { serverLoopActive(loopJob) && socket === acceptedSocket }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (serverLoopActive(loopJob)) {
                    Log.d(TAG, "Bluetooth phone callback disconnected", error)
                    onLog("Bluetooth phone disconnected.")
                }
            }
            releaseSocket(acceptedSocket)
            if (serverLoopActive(loopJob)) {
                onState(BluetoothClientState(active = true, connected = false, status = "Waiting for phone callback"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun waitForPhoneCallback(loopJob: Job?): BluetoothSocket? {
        if (!serverLoopActive(loopJob)) return null
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled || !hasConnectPermission()) return null
        var listener: BluetoothServerSocket? = null
        return runCatching {
            closeServer()
            listener = adapter.listenUsingRfcommWithServiceRecord(
                Protocol.BLUETOOTH_SERVICE_NAME,
                serviceUuid,
            )
            val activeListener = listener ?: return@runCatching null
            serverSocket = activeListener
            onLog("Bluetooth callback listener ready.")
            val accepted = activeListener.accept() ?: return@runCatching null
            closeServer(activeListener)
            if (!serverLoopActive(loopJob)) {
                runCatching { accepted.close() }
                return@runCatching null
            }
            accepted
        }.getOrElse { error ->
            closeServer(listener)
            if (serverLoopActive(loopJob)) {
                Log.d(TAG, "Bluetooth callback listener failed", error)
            }
            null
        }
    }

    private fun performHandshake(
        connectedSocket: BluetoothSocket,
        activeWriter: BufferedWriter,
        reader: BufferedReader,
    ): Boolean {
        val handshakeActive = java.util.concurrent.atomic.AtomicBoolean(true)
        val timeoutJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (handshakeActive.get() && socket === connectedSocket) {
                Log.w(TAG, "Bluetooth phone handshake timed out")
                runCatching { connectedSocket.close() }
            }
        }
        val result = runCatching {
            writeLine(
                activeWriter,
                JsonProtocol.encodeStatus(
                    StatusMessage(
                        type = StatusType.HELLO,
                        message = "Helper Bluetooth ready",
                        peerRole = Protocol.HELPER_ROLE,
                    ),
                ),
            )
            val raw = reader.readLine() ?: return@runCatching false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return@runCatching false
            val hello = JsonProtocol.decodeControl(raw)
            hello is ControlMessage.Hello
                && hello.protocolVersion == Protocol.PROTOCOL_VERSION
                && hello.peerRole == Protocol.PHONE_ROLE
        }.getOrElse { error ->
            Log.d(TAG, "Bluetooth handshake failed", error)
            false
        }
        handshakeActive.set(false)
        timeoutJob.cancel()
        return result
    }

    private fun readPhone(
        reader: BufferedReader,
        active: () -> Boolean,
    ) {
        reader.use {
            while (active()) {
                val raw = it.readLine() ?: break
                if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) {
                    onError("Bluetooth message was too large.", null)
                    continue
                }
                runCatching {
                    JsonProtocol.decodeControl(raw)
                }.onSuccess { message ->
                    onMessage(message)
                }.onFailure { error ->
                    Log.w(TAG, "Bad Bluetooth payload", error)
                    onError("Could not parse Bluetooth message.", error)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun candidateDevices(
        adapter: BluetoothAdapter,
        trustedAddress: String,
        includeFallback: Boolean,
    ): List<BluetoothDevice> {
        val bondedDevices = adapter.bondedDevices.orEmpty()
            .sortedBy { it.safeAddress() }
        if (trustedAddress.isBlank()) {
            return bondedDevices
        }
        val trustedDevices = bondedDevices.filter { it.safeAddress().equals(trustedAddress, ignoreCase = true) }
        if (includeFallback) {
            return trustedDevices + bondedDevices.filterNot { it.safeAddress().equals(trustedAddress, ignoreCase = true) }
        }
        return trustedDevices
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

    private fun trustedPhoneAddress(): String =
        prefs.getString(KEY_TRUSTED_PHONE_ADDRESS, "").orEmpty()

    private fun rememberTrustedPhone(address: String) {
        if (address.isBlank() || address.equals(trustedPhoneAddress(), ignoreCase = true)) return
        prefs.edit().putString(KEY_TRUSTED_PHONE_ADDRESS, address).apply()
    }

    private fun recordConnectFailure() {
        if (trustedPhoneAddress().isNotBlank()) {
            trustedConnectFailures = (trustedConnectFailures + 1).coerceAtMost(TRUSTED_FALLBACK_AFTER_FAILURES)
            if (lastConnectAttemptUsedFallback) {
                nextFallbackSearchAtMs = SystemClock.elapsedRealtime() + FALLBACK_SEARCH_COOLDOWN_MS
            }
        }
        lastConnectAttemptUsedFallback = false
    }

    private fun shouldSearchFallbackPhones(trustedAddress: String): Boolean =
        trustedAddress.isNotBlank() &&
            trustedConnectFailures >= TRUSTED_FALLBACK_AFTER_FAILURES &&
            SystemClock.elapsedRealtime() >= nextFallbackSearchAtMs

    private fun connectLoopActive(loopJob: Job?): Boolean =
        acceptJob === loopJob && loopJob?.isActive == true

    private fun serverLoopActive(loopJob: Job?): Boolean =
        serverJob === loopJob && loopJob?.isActive == true

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeName(): String =
        runCatching { this?.name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeAddress(): String =
        runCatching { this?.address.orEmpty() }.getOrDefault("")

    private fun claimSocket(candidate: BluetoothSocket): Boolean =
        synchronized(connectionLock) {
            if (socket != null) {
                false
            } else {
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
            val pending = pendingSocket
            pendingSocket = null
            runCatching { pending?.close() }
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun writeLine(activeWriter: BufferedWriter, raw: String) {
        synchronized(writeLock) {
            activeWriter.write(raw)
            activeWriter.newLine()
            activeWriter.flush()
        }
    }

    companion object {
        private const val TAG = "TaskerBridge-BT"
        private const val PREFS_NAME = "tasker_bridge_bluetooth"
        private const val KEY_TRUSTED_PHONE_ADDRESS = "trusted_phone_address"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 12_000L
        private const val HANDSHAKE_TIMEOUT_MS = 8_000L
        private const val TRUSTED_FALLBACK_AFTER_FAILURES = 3
        private const val FALLBACK_SEARCH_COOLDOWN_MS = 30_000L
    }
}
