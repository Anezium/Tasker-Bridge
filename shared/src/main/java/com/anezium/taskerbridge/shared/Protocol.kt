package com.anezium.taskerbridge.shared

import org.json.JSONArray
import org.json.JSONObject

object Protocol {
    const val CONTROL_CHANNEL = "anezium_tasker_bridge_control"
    const val STATUS_CHANNEL = "anezium_tasker_bridge_status"
    const val HELPER_PACKAGE = "com.anezium.taskerbridge.glasses"
    const val HELPER_MAIN_ACTIVITY = "com.anezium.taskerbridge.glasses.MainActivity"
    const val MAX_TASKS_ON_HUD = 7
}

data class TaskerTask(
    val name: String,
    val projectName: String = "",
)

enum class ControlType {
    HELLO,
    TASK_LIST,
    LAUNCH_RESULT,
    SET_STATUS,
    PING,
}

enum class StatusType {
    READY,
    REQUEST_TASKS,
    LAUNCH_TASK,
    SELECTION_CHANGED,
    ERROR,
    PONG,
}

sealed interface ControlMessage {
    val type: ControlType

    data class Hello(val appVersion: String = "0.1.0") : ControlMessage {
        override val type = ControlType.HELLO
    }

    data class TaskList(
        val tasks: List<TaskerTask>,
        val selectedIndex: Int = 0,
        val taskerInstalled: Boolean = false,
        val taskerEnabled: Boolean = false,
        val externalAccess: Boolean = false,
        val message: String = "",
        val timestampMs: Long = System.currentTimeMillis(),
    ) : ControlMessage {
        override val type = ControlType.TASK_LIST
    }

    data class LaunchResult(
        val taskName: String,
        val success: Boolean,
        val message: String = "",
        val timestampMs: Long = System.currentTimeMillis(),
    ) : ControlMessage {
        override val type = ControlType.LAUNCH_RESULT
    }

    data class SetStatus(
        val message: String,
        val urgent: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : ControlMessage {
        override val type = ControlType.SET_STATUS
    }

    data class Ping(val nonce: String) : ControlMessage {
        override val type = ControlType.PING
    }
}

data class StatusMessage(
    val type: StatusType,
    val message: String = "",
    val taskName: String = "",
    val selectedIndex: Int = -1,
    val timestampMs: Long = System.currentTimeMillis(),
)

object JsonProtocol {
    fun encodeControl(message: ControlMessage): String {
        val json = JSONObject()
            .put("type", message.type.name)
        when (message) {
            is ControlMessage.Hello -> json.put("appVersion", message.appVersion)
            is ControlMessage.TaskList -> json
                .put("tasks", JSONArray().apply {
                    message.tasks.forEach { task ->
                        put(
                            JSONObject()
                                .put("name", task.name)
                                .put("projectName", task.projectName),
                        )
                    }
                })
                .put("selectedIndex", message.selectedIndex)
                .put("taskerInstalled", message.taskerInstalled)
                .put("taskerEnabled", message.taskerEnabled)
                .put("externalAccess", message.externalAccess)
                .put("message", message.message)
                .put("timestampMs", message.timestampMs)
            is ControlMessage.LaunchResult -> json
                .put("taskName", message.taskName)
                .put("success", message.success)
                .put("message", message.message)
                .put("timestampMs", message.timestampMs)
            is ControlMessage.SetStatus -> json
                .put("message", message.message)
                .put("urgent", message.urgent)
                .put("timestampMs", message.timestampMs)
            is ControlMessage.Ping -> json.put("nonce", message.nonce)
        }
        return json.toString()
    }

    fun decodeControl(raw: String): ControlMessage {
        val json = JSONObject(raw)
        return when (ControlType.valueOf(json.getString("type"))) {
            ControlType.HELLO -> ControlMessage.Hello(json.optString("appVersion", "unknown"))
            ControlType.TASK_LIST -> ControlMessage.TaskList(
                tasks = buildList {
                    val tasks = json.optJSONArray("tasks") ?: JSONArray()
                    for (index in 0 until tasks.length()) {
                        val item = tasks.optJSONObject(index) ?: continue
                        val name = item.optString("name")
                        if (name.isNotBlank()) {
                            add(TaskerTask(name = name, projectName = item.optString("projectName")))
                        }
                    }
                },
                selectedIndex = json.optInt("selectedIndex", 0),
                taskerInstalled = json.optBoolean("taskerInstalled", false),
                taskerEnabled = json.optBoolean("taskerEnabled", false),
                externalAccess = json.optBoolean("externalAccess", false),
                message = json.optString("message"),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            )
            ControlType.LAUNCH_RESULT -> ControlMessage.LaunchResult(
                taskName = json.optString("taskName"),
                success = json.optBoolean("success"),
                message = json.optString("message"),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            )
            ControlType.SET_STATUS -> ControlMessage.SetStatus(
                message = json.optString("message"),
                urgent = json.optBoolean("urgent"),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            )
            ControlType.PING -> ControlMessage.Ping(json.optString("nonce"))
        }
    }

    fun encodeStatus(message: StatusMessage): String = JSONObject()
        .put("type", message.type.name)
        .put("message", message.message)
        .put("taskName", message.taskName)
        .put("selectedIndex", message.selectedIndex)
        .put("timestampMs", message.timestampMs)
        .toString()

    fun decodeStatus(raw: String): StatusMessage {
        val json = JSONObject(raw)
        return StatusMessage(
            type = StatusType.valueOf(json.getString("type")),
            message = json.optString("message"),
            taskName = json.optString("taskName"),
            selectedIndex = json.optInt("selectedIndex", -1),
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
        )
    }
}
