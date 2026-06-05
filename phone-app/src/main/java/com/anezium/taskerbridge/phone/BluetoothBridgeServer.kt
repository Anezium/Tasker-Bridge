package com.anezium.taskerbridge.phone

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    private var socket: BluetoothSocket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    fun start() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { reconnectLoop() }
    }

    fun stop() {
        scope.launch {
            connectJob?.cancelAndJoin()
            connectJob = null
            closeSocket()
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

    private suspend fun reconnectLoop() {
        while (connectJob?.isActive == true) {
            val connectedSocket = connectToGlasses()
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
                onLog("Rejected incompatible Bluetooth HUD.")
                closeSocket()
                delay(RECONNECT_DELAY_MS)
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
                readGlasses(reader)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (connectJob?.isActive == true) {
                    Log.d(TAG, "Bluetooth HUD disconnected", error)
                    onLog("Bluetooth HUD disconnected.")
                }
            }
            closeSocket()
            if (connectJob?.isActive == true) {
                onState(
                    BluetoothServerState(
                        active = true,
                        connected = false,
                        paired = hasTrustedGlasses(),
                        pairingMode = !hasTrustedGlasses(),
                        status = "Looking for HUD",
                    ),
                )
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToGlasses(): BluetoothSocket? {
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

        val candidates = candidateDevices(adapter)
        if (candidates.isEmpty()) {
            val paired = hasTrustedGlasses()
            onState(
                BluetoothServerState(
                    active = true,
                    connected = false,
                    paired = paired,
                    pairingMode = !paired,
                    status = if (paired) {
                        "Paired HUD not found"
                    } else {
                        "Launch HUD to pair Bluetooth"
                    },
                ),
            )
            return null
        }

        runCatching { adapter.cancelDiscovery() }
        for (device in candidates) {
            val paired = hasTrustedGlasses()
            onState(
                BluetoothServerState(
                    active = true,
                    connected = false,
                    paired = paired,
                    pairingMode = !paired,
                    status = if (paired) "Connecting paired HUD" else "Pairing HUD",
                ),
            )
            val connected = runCatching {
                device.createRfcommSocketToServiceRecord(serviceUuid).also { candidate ->
                    candidate.connect()
                }
            }.getOrElse { error ->
                Log.d(TAG, "Bluetooth connect failed for candidate device", error)
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
                JsonProtocol.encodeControl(
                    ControlMessage.Hello(
                        appVersion = "phone",
                        peerRole = Protocol.PHONE_ROLE,
                    ),
                ),
            )
            val raw = reader.readLine() ?: return false
            if (raw.length > Protocol.MAX_WIRE_MESSAGE_CHARS) return false
            val hello = JsonProtocol.decodeStatus(raw)
            hello.type == StatusType.HELLO
                && hello.protocolVersion == Protocol.PROTOCOL_VERSION
                && hello.peerRole == Protocol.HELPER_ROLE
        }.getOrElse { error ->
            Log.d(TAG, "Bluetooth handshake failed", error)
            false
        }
    }

    private fun readGlasses(reader: BufferedReader) {
        reader.use {
            while (connectJob?.isActive == true) {
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

    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        migrateTrustedGlasses()
        val trustedAddress = trustedGlassesAddress()
        val bondedDevices = adapter.bondedDevices
            .orEmpty()
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

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String =
        runCatching { name.orEmpty() }.getOrDefault("")

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeAddress(): String =
        runCatching { address.orEmpty() }.getOrDefault("")

    private fun trustedGlassesAddress(): String =
        prefs.getString(KEY_TRUSTED_GLASSES_ADDRESS, "").orEmpty()

    private fun hasTrustedGlasses(): Boolean =
        trustedGlassesAddress().isNotBlank()

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
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
