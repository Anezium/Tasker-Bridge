package com.anezium.taskerbridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.JsonProtocol
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
import kotlinx.coroutines.CancellationException
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

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    fun start() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { acceptLoop() }
    }

    fun restart() {
        val oldJob = connectJob
        connectJob = null
        closeSocket()
        closeServer()
        connectJob = scope.launch { acceptLoop() }
        scope.launch { oldJob?.cancelAndJoin() }
    }

    fun stop() {
        val job = connectJob
        connectJob = null
        closeSocket()
        closeServer()
        scope.launch {
            job?.cancelAndJoin()
            if (connectJob == null) {
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
            socket = acceptedSocket
            val activeWriter = BufferedWriter(OutputStreamWriter(acceptedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(acceptedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = acceptedSocket.remoteDevice.safeName()
            val peerAddress = acceptedSocket.remoteDevice.safeAddress()
            if (!performHandshake(reader, activeWriter)) {
                onLog("Rejected incompatible Bluetooth HUD.")
                closeSocket()
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
            try {
                readGlasses(reader, loopJob)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (serverLoopActive(loopJob)) {
                    Log.d(TAG, "Bluetooth HUD disconnected", error)
                    onLog("Bluetooth HUD disconnected.")
                }
            }
            closeSocket()
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

    private fun performHandshake(
        reader: BufferedReader,
        activeWriter: BufferedWriter,
    ): Boolean {
        return runCatching {
            val raw = reader.readLine() ?: return false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return false
            val hello = JsonProtocol.decodeStatus(raw)
            if (hello.type != StatusType.HELLO
                || hello.protocolVersion != Protocol.PROTOCOL_VERSION
                || hello.peerRole != Protocol.HELPER_ROLE
            ) {
                return false
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
    }

    private fun readGlasses(
        reader: BufferedReader,
        loopJob: Job?,
    ) {
        reader.use {
            while (serverLoopActive(loopJob)) {
                val raw = it.readLine() ?: break
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
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
        runCatching { socket?.close() }
        socket = null
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
        private const val KEY_TRUSTED_GLASSES_ADDRESS = "trusted_glasses_address"
        private const val KEY_LAST_GLASSES_ADDRESS = "last_glasses_address"
        private const val SERVER_RETRY_DELAY_MS = 30_000L
    }
}
