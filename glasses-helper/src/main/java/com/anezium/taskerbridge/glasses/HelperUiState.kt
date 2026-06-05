package com.anezium.taskerbridge.glasses

import com.anezium.taskerbridge.shared.TaskerTask

data class HelperUiState(
    val tasks: List<TaskerTask> = emptyList(),
    val selectedIndex: Int = 0,
    val viewMode: HelperViewMode = HelperViewMode.PROJECTS,
    val selectedProjectIndex: Int = 0,
    val selectedProjectName: String = "",
    val phoneConnected: Boolean = false,
    val phoneName: String = "",
    val taskerInstalled: Boolean = false,
    val taskerEnabled: Boolean = false,
    val externalAccess: Boolean = false,
    val bridgeState: String = "Starting Bluetooth",
    val status: String = "Waiting for phone",
    val lastLaunchTask: String = "",
    val lastLaunchSuccess: Boolean? = null,
)

enum class HelperViewMode {
    PROJECTS,
    TASKS,
}

data class TaskProject(
    val name: String,
    val taskIndices: List<Int>,
) {
    val displayName: String
        get() = name.ifBlank { NO_PROJECT_NAME }
}

fun List<TaskerTask>.taskProjects(): List<TaskProject> {
    val groups = linkedMapOf<String, MutableList<Int>>()
    forEachIndexed { index, task ->
        groups.getOrPut(task.projectGroupName()) { mutableListOf() }.add(index)
    }
    return groups.map { (name, indices) -> TaskProject(name, indices) }
}

fun TaskerTask.projectGroupName(): String =
    projectName.trim().ifBlank { NO_PROJECT_NAME }

private const val NO_PROJECT_NAME = "No project"
