package com.anezium.taskerbridge.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch { connectLoop() }
    }

    fun stop() {
        scope.launch {
            acceptJob?.cancelAndJoin()
            acceptJob = null
            closeSocket()
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

    private suspend fun connectLoop() {
        while (acceptJob?.isActive == true) {
            val connectedSocket = connectToPhone()
            if (connectedSocket == null) {
                delay(RECONNECT_DELAY_MS)
                continue
            }
            socket = connectedSocket
            val activeWriter = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
            writer = activeWriter
            val peerName = connectedSocket.remoteDevice.safeName()
            val peerAddress = connectedSocket.remoteDevice.safeAddress()
            if (!performHandshake(activeWriter, reader)) {
                closeSocket()
                onLog("Rejected incompatible Bluetooth phone.")
                delay(RECONNECT_DELAY_MS)
                continue
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
            try {
                readPhone(reader)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (acceptJob?.isActive == true) {
                    Log.d(TAG, "Bluetooth phone disconnected", error)
                    onLog("Bluetooth phone disconnected.")
                }
            }
            closeSocket()
            if (acceptJob?.isActive == true) {
                onState(BluetoothClientState(active = true, connected = false, status = "Looking for phone"))
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPhone(): BluetoothSocket? {
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

        val candidates = candidateDevices(adapter)
        if (candidates.isEmpty()) {
            onState(BluetoothClientState(active = true, connected = false, status = "Pair phone first"))
            return null
        }

        runCatching { adapter.cancelDiscovery() }
        for (device in candidates) {
            onState(
                BluetoothClientState(
                    active = true,
                    connected = false,
                    status = if (trustedPhoneAddress().isBlank()) "Pairing phone" else "Connecting phone",
                ),
            )
            val connected = runCatching {
                device.createRfcommSocketToServiceRecord(serviceUuid).also { candidate ->
                    candidate.connect()
                }
            }.getOrElse { error ->
                Log.d(TAG, "Bluetooth connect failed for candidate phone", error)
                null
            }
            if (connected != null) return connected
        }
        return null
    }

    private fun performHandshake(
        activeWriter: BufferedWriter,
        reader: BufferedReader,
    ): Boolean {
        return runCatching {
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
            val raw = reader.readLine() ?: return false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return false
            val hello = JsonProtocol.decodeControl(raw)
            hello is ControlMessage.Hello
                && hello.protocolVersion == Protocol.PROTOCOL_VERSION
                && hello.peerRole == Protocol.PHONE_ROLE
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

    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val trustedAddress = trustedPhoneAddress()
        val bondedDevices = adapter.bondedDevices.orEmpty()
        if (trustedAddress.isNotBlank()) {
            return bondedDevices.filter { it.safeAddress().equals(trustedAddress, ignoreCase = true) }
        }
        return bondedDevices.sortedBy { it.safeAddress() }
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
        if (address.isBlank() || trustedPhoneAddress().isNotBlank()) return
        prefs.edit().putString(KEY_TRUSTED_PHONE_ADDRESS, address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeName(): String =
        runCatching { this?.name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun android.bluetooth.BluetoothDevice?.safeAddress(): String =
        runCatching { this?.address.orEmpty() }.getOrDefault("")

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
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
