package com.anezium.taskerbridge.glasses

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.JsonProtocol
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CxrBridgeState(
    val active: Boolean,
    val connected: Boolean,
    val status: String,
)

class CxrFallbackBridge(
    private val onState: (CxrBridgeState) -> Unit,
    private val onMessage: (ControlMessage) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var bridge: CXRServiceBridge? = null
    private var generation = 0

    fun start() {
        main.post {
            if (bridge != null) return@post
            generation += 1
            val currentGeneration = generation
            val cxr = CXRServiceBridge()
            bridge = cxr
            onState(CxrBridgeState(active = true, connected = false, status = "CXR connecting"))
            cxr.setStatusListener(statusListener(currentGeneration))
            val result = cxr.subscribe(Protocol.CONTROL_CHANNEL, msgCallback(currentGeneration))
            onLog("CXR fallback subscribed: $result")
        }
    }

    fun stop() {
        main.post {
            generation += 1
            val activeBridge = bridge
            bridge = null
            runCatching { activeBridge?.disconnectCXRDevice() }
                .onFailure { Log.w(TAG, "CXR disconnect failed", it) }
            onState(CxrBridgeState(active = false, connected = false, status = "CXR stopped"))
        }
    }

    suspend fun send(message: StatusMessage): Boolean = withContext(Dispatchers.Main.immediate) {
        val activeBridge = bridge ?: return@withContext false
        val raw = JsonProtocol.encodeStatus(message)
        runCatching {
            val result = activeBridge.sendMessage(
                Protocol.STATUS_CHANNEL,
                Caps().apply { write(raw) },
            )
            result >= 0
        }.getOrElse {
            Log.w(TAG, "CXR status send failed: ${it.message}")
            false
        }
    }

    private fun statusListener(listenerGeneration: Int) = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, deviceType: Int) {
            emitState(
                listenerGeneration,
                CxrBridgeState(active = true, connected = true, status = "Phone connected via CXR"),
            )
        }

        override fun onDisconnected() {
            emitState(
                listenerGeneration,
                CxrBridgeState(active = true, connected = false, status = "CXR disconnected"),
            )
        }

        override fun onConnecting(name: String?, mac: String?, deviceType: Int) {
            emitState(
                listenerGeneration,
                CxrBridgeState(active = true, connected = false, status = "CXR connecting"),
            )
        }

        override fun onARTCStatus(latency: Float, connected: Boolean) {
            if (connected) {
                emitState(
                    listenerGeneration,
                    CxrBridgeState(active = true, connected = true, status = "Phone connected via CXR"),
                )
            }
        }

        override fun onRokidAccountChanged(account: String?) = Unit
        override fun onAudioNoise(noise: Float) = Unit
    }

    private fun msgCallback(listenerGeneration: Int) = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(msgType: String?, caps: Caps?, data: ByteArray?) {
            if (msgType != Protocol.CONTROL_CHANNEL) return
            val payload = decodePayload(caps, data)
            if (payload.isBlank()) return
            runCatching { JsonProtocol.decodeControl(payload) }
                .onSuccess { message -> emitMessage(listenerGeneration, message) }
                .onFailure { Log.w(TAG, "Bad CXR control payload", it) }
        }
    }

    private fun emitState(listenerGeneration: Int, state: CxrBridgeState) {
        main.post {
            if (listenerGeneration == generation && bridge != null) {
                onState(state)
            }
        }
    }

    private fun emitMessage(listenerGeneration: Int, message: ControlMessage) {
        main.post {
            if (listenerGeneration == generation && bridge != null) {
                onMessage(message)
            }
        }
    }

    private fun decodePayload(caps: Caps?, data: ByteArray?): String {
        if (data != null && data.isNotEmpty()) {
            val raw = data.toString(Charsets.UTF_8).trim()
            if (raw.startsWith("{")) return raw
            val serialized = runCatching {
                val parsed = Caps.fromBytes(data)
                if (parsed.size() > 0) parsed.at(0).string else ""
            }.getOrDefault("")
            if (serialized.isNotBlank()) return serialized
            if (raw.isNotBlank()) return raw
        }
        return if (caps != null && caps.size() > 0) {
            runCatching { caps.at(0).string }.getOrDefault("")
        } else {
            ""
        }
    }

    companion object {
        private const val TAG = "TaskerBridge-CXR"
    }
}
