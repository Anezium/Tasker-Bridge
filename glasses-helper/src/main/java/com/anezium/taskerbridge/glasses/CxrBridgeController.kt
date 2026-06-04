package com.anezium.taskerbridge.glasses

import android.util.Log
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.JsonProtocol
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.anezium.taskerbridge.shared.StatusType
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

class CxrBridgeController(
    private val onControlMessage: (ControlMessage) -> Unit,
    private val onBridgeState: (String) -> Unit,
    private val onPhoneAvailable: (String) -> Unit,
) {
    private val bridge = CXRServiceBridge()
    private var started = false

    fun start() {
        if (started) {
            onPhoneAvailable("Bridge resumed")
            return
        }
        started = true
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(mac: String?, name: String?, type: Int) {
                onBridgeState("Bridge connected")
                onPhoneAvailable("Bridge connected")
            }

            override fun onConnecting(mac: String?, name: String?, type: Int) {
                onBridgeState("Bridge connecting")
            }

            override fun onDisconnected() {
                onBridgeState("Bridge disconnected")
            }

            override fun onARTCStatus(health: Float, reset: Boolean) = Unit
            override fun onRokidAccountChanged(account: String?) = Unit
        })
        val result = bridge.subscribe(
            Protocol.CONTROL_CHANNEL,
            object : CXRServiceBridge.MsgCallback {
                override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                    if (name != Protocol.CONTROL_CHANNEL) return
                    val raw = when {
                        bytes != null && bytes.isNotEmpty() -> bytes.decodeToString()
                        args != null -> args.readStringPairPayload()
                        else -> null
                    }
                    if (raw.isNullOrBlank()) {
                        Log.w(TAG, "Ignoring empty control payload")
                        return
                    }
                    runCatching {
                        val message = JsonProtocol.decodeControl(raw)
                        Log.d(TAG, "received control ${message.type}")
                        onControlMessage(message)
                    }.onFailure {
                        Log.e(TAG, "Failed to parse control payload", it)
                        sendStatus(StatusMessage(StatusType.ERROR, "Bad control payload: ${it.message}"))
                    }
                }
            },
        )
        onBridgeState("Subscribed ${Protocol.CONTROL_CHANNEL}: $result")
        sendStatus(StatusMessage(StatusType.READY, "Helper ready"))
        Log.d(TAG, "bridge started subscribe=$result")
    }

    fun sendStatus(message: StatusMessage): Int {
        val payload = JsonProtocol.encodeStatus(message)
        val caps = Caps().apply {
            write("json")
            write(payload)
        }
        val result = bridge.sendMessage(Protocol.STATUS_CHANNEL, caps)
        Log.d(TAG, "sendStatus ${message.type}: $result")
        return result
    }

    private fun Caps.readStringPairPayload(): String? {
        if (size() == 0) return null
        if (size() == 1) return at(0).string
        return at(1).string ?: at(0).string
    }

    companion object {
        private const val TAG = "TaskerBridge-Cxr"
        const val BRIDGE_PHONE_NOT_READY = -3
    }
}
