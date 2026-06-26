package com.anezium.taskerbridge.phone

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.util.Log

class BridgeCompanionService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            -> glassesPresent("presence event ${event.event}")
            else -> Log.i(TAG, "glasses presence event ${event.event}")
        }
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        glassesPresent("association ${associationInfo.id}")
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "glasses out of range: association ${associationInfo.id}")
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceAppeared(address: String) {
        glassesPresent("legacy presence")
    }

    @Deprecated("Pre-T presence callback; AssociationInfo variant forwards here")
    override fun onDeviceDisappeared(address: String) {
        Log.i(TAG, "glasses out of range")
    }

    private fun glassesPresent(source: String) {
        Log.i(TAG, "glasses present: $source")
        if (BleWakeServer.isArmed(this)) {
            BleWakeServer.ensureStarted(this)
        }
    }

    companion object {
        private const val TAG = "TaskerBridge-CDM"
    }
}
