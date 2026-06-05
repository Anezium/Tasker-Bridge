package com.anezium.taskerbridge.glasses

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch { acceptLoop() }
    }

    fun stop() {
        scope.launch {
            acceptJob?.cancelAndJoin()
            acceptJob = null
            closeSocket()
            closeServer()
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth stopped"))
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

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth unavailable"))
            return
        }
        if (!hasConnectPermission()) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth permission missing"))
            onError("Grant Bluetooth permission to the glasses helper.", null)
            return
        }
        if (!adapter.isEnabled) {
            onState(BluetoothClientState(active = false, connected = false, status = "Bluetooth disabled"))
            return
        }

        while (acceptJob?.isActive == true) {
            runCatching {
                closeServer()
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    Protocol.BLUETOOTH_SERVICE_NAME,
                    serviceUuid,
                )
                onState(BluetoothClientState(active = true, connected = false, status = "Waiting for phone"))
                onLog("Bluetooth HUD server listening.")
                val accepted = serverSocket?.accept() ?: return@runCatching
                if (!isTrustedPhone(accepted)) {
                    runCatching { accepted.close() }
                    onLog("Rejected unpaired Bluetooth phone.")
                    return@runCatching
                }
                closeSocket()
                socket = accepted
                val activeWriter = BufferedWriter(OutputStreamWriter(accepted.outputStream, Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(accepted.inputStream, Charsets.UTF_8))
                writer = activeWriter
                val peerName = accepted.remoteDevice.safeName()
                val peerAddress = accepted.remoteDevice.safeAddress()
                if (!performHandshake(reader, activeWriter)) {
                    runCatching { accepted.close() }
                    closeSocket()
                    onLog("Rejected incompatible Bluetooth phone.")
                    return@runCatching
                }
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
                readPhone(reader)
            }.onFailure { error ->
                if (acceptJob?.isActive == true) {
                    onError("Bluetooth server failed.", error)
                }
            }
            closeSocket()
            if (acceptJob?.isActive == true) {
                onState(BluetoothClientState(active = true, connected = false, status = "Waiting for phone"))
            }
        }
    }

    private fun performHandshake(
        reader: BufferedReader,
        activeWriter: BufferedWriter,
    ): Boolean {
        return runCatching {
            val raw = reader.readLine() ?: return false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return false
            val hello = JsonProtocol.decodeControl(raw)
            if (hello !is ControlMessage.Hello
                || hello.protocolVersion != Protocol.PROTOCOL_VERSION
                || hello.peerRole != Protocol.PHONE_ROLE
            ) {
                return false
            }
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
            true
        }.getOrElse { error ->
            Log.d(TAG, "Bluetooth handshake failed", error)
            false
        }
    }

    private fun readPhone(reader: BufferedReader) {
        reader.use {
            while (acceptJob?.isActive == true) {
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

    private fun isTrustedPhone(socket: BluetoothSocket): Boolean {
        val trustedAddress = trustedPhoneAddress()
        if (trustedAddress.isBlank()) return true
        val address = socket.remoteDevice.safeAddress()
        return address.equals(trustedAddress, ignoreCase = true)
    }

    private fun rememberTrustedPhone(address: String) {
        if (address.isBlank() || trustedPhoneAddress().isNotBlank()) return
        prefs.edit().putString(KEY_TRUSTED_PHONE_ADDRESS, address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeName(): String =
        runCatching { this?.name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeAddress(): String =
        runCatching { this?.address.orEmpty() }.getOrDefault("")

    private fun closeServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
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
        private const val KEY_TRUSTED_PHONE_ADDRESS = "trusted_phone_address"
    }
}
