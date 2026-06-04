package com.anezium.taskerbridge.glasses

import com.anezium.taskerbridge.shared.TaskerTask

data class HelperUiState(
    val tasks: List<TaskerTask> = emptyList(),
    val selectedIndex: Int = 0,
    val taskerInstalled: Boolean = false,
    val taskerEnabled: Boolean = false,
    val externalAccess: Boolean = false,
    val bridgeState: String = "Starting bridge",
    val status: String = "Syncing from phone",
    val lastLaunchTask: String = "",
    val lastLaunchSuccess: Boolean? = null,
)
