package com.anezium.taskerbridge.phone

import com.anezium.taskerbridge.shared.TaskerTask

data class PhoneUiState(
    val bridgeServiceActive: Boolean = false,
    val requiredRokidAppInstalled: Boolean = false,
    val authorized: Boolean = false,
    val cxrConnected: Boolean = false,
    val glassBtConnected: Boolean = false,
    val helperInstallStatus: String = "Helper not installed yet.",
    val helperInstallBusy: Boolean = false,
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
