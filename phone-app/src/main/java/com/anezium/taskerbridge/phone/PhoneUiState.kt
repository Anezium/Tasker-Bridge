package com.anezium.taskerbridge.phone

import com.anezium.taskerbridge.shared.TaskerTask

data class PhoneUiState(
    val bridgeServiceActive: Boolean = false,
    val bluetoothServerActive: Boolean = false,
    val bluetoothConnected: Boolean = false,
    val bluetoothPaired: Boolean = false,
    val bluetoothPairingMode: Boolean = false,
    val bluetoothPeerName: String = "",
    val bluetoothPeerAddress: String = "",
    val bluetoothStatus: String = "Bluetooth idle.",
    val requiredRokidAppInstalled: Boolean = false,
    val authorized: Boolean = false,
    val cxrConnected: Boolean = false,
    val glassBtConnected: Boolean = false,
    val helperInstallStatus: String = "Helper not installed yet.",
    val helperInstallBusy: Boolean = false,
    val helperBundledVersion: String = "unknown",
    val helperLastInstalledVersion: String = "none recorded",
    val taskerInstalled: Boolean = false,
    val taskerEnabled: Boolean = false,
    val externalAccess: Boolean = false,
    val taskerRunPermissionGranted: Boolean = false,
    val tasks: List<TaskerTask> = emptyList(),
    val selectedIndex: Int = 0,
    val helperSelectedIndex: Int = -1,
    val lastStatus: String = "Idle.",
    val error: String = "",
)
