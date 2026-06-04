package com.anezium.taskerbridge.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.taskerbridge.shared.TaskerTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TaskerSnapshot(
    val installed: Boolean,
    val enabled: Boolean,
    val externalAccess: Boolean,
    val runPermissionGranted: Boolean,
    val tasks: List<TaskerTask>,
    val message: String,
)

data class TaskerRunResult(
    val taskName: String,
    val success: Boolean,
    val message: String,
)

class TaskerRepository(private val context: Context) {
    suspend fun refresh(): TaskerSnapshot = withContext(Dispatchers.IO) {
        val installed = isTaskerInstalled()
        if (!installed) {
            return@withContext TaskerSnapshot(
                installed = false,
                enabled = false,
                externalAccess = false,
                runPermissionGranted = false,
                tasks = emptyList(),
                message = "Tasker is not installed on this phone.",
            )
        }

        val prefs = queryPrefs()
        val runPermissionGranted = hasRunTaskPermission()
        val tasks = queryTasks()
        val message = when {
            prefs != null && !prefs.enabled -> "Tasker is disabled."
            prefs != null && !prefs.externalAccess -> "Enable Tasker external access in Tasker preferences."
            !runPermissionGranted -> "Grant Tasker run permission to Tasker Bridge."
            tasks.isEmpty() -> "No named Tasker tasks found."
            else -> "${tasks.size} Tasker tasks ready."
        }
        TaskerSnapshot(
            installed = true,
            enabled = prefs?.enabled ?: true,
            externalAccess = prefs?.externalAccess ?: true,
            runPermissionGranted = runPermissionGranted,
            tasks = tasks,
            message = message,
        )
    }

    suspend fun runTask(taskName: String): TaskerRunResult = withContext(Dispatchers.IO) {
        if (taskName.isBlank()) {
            return@withContext TaskerRunResult(taskName, false, "Empty task name.")
        }
        val snapshot = refresh()
        if (!snapshot.installed) {
            return@withContext TaskerRunResult(taskName, false, "Tasker is not installed.")
        }
        if (!snapshot.enabled) {
            return@withContext TaskerRunResult(taskName, false, "Tasker is disabled.")
        }
        if (!snapshot.externalAccess) {
            return@withContext TaskerRunResult(taskName, false, "Tasker external access is blocked.")
        }
        if (!snapshot.runPermissionGranted) {
            return@withContext TaskerRunResult(taskName, false, "Tasker run permission is not granted.")
        }
        if (snapshot.tasks.none { it.name == taskName }) {
            return@withContext TaskerRunResult(taskName, false, "Task not found: $taskName")
        }
        val packageName = taskerPackageName()
            ?: return@withContext TaskerRunResult(taskName, false, "Tasker package disappeared.")

        runCatching {
            val intent = Intent(ACTION_TASKER_TASK)
                .setComponent(ComponentName(packageName, "$packageName.ReceiverStaticRunTasks"))
                .putExtra(EXTRA_TASK_NAME, taskName)
                .putExtra(EXTRA_VERSION_NUMBER, "1.1")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            intent.data = Uri.parse("taskerbridge://${SystemClock.elapsedRealtimeNanos()}")
            Log.i(TAG, "Sending Tasker run broadcast for task=${taskName.take(48)}")
            context.sendBroadcast(intent)
        }.fold(
            onSuccess = { TaskerRunResult(taskName, true, "Sent to Tasker: $taskName") },
            onFailure = { TaskerRunResult(taskName, false, it.message ?: "Tasker broadcast failed.") },
        )
    }

    private fun isTaskerInstalled(): Boolean {
        return taskerPackageName() != null
    }

    private fun taskerPackageName(): String? {
        return runCatching {
            context.packageManager.getPackageInfoCompat(TASKER_PACKAGE)
            TASKER_PACKAGE
        }.getOrNull() ?: runCatching {
            context.packageManager.getPackageInfoCompat(LEGACY_TASKER_PACKAGE)
            LEGACY_TASKER_PACKAGE
        }.getOrNull()
    }

    private fun hasRunTaskPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, PERMISSION_RUN_TASKS) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryPrefs(): TaskerPrefs? {
        return runCatching {
            context.contentResolver.query(TASKER_PREFS_URI, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val enabled = cursor.getBooleanColumn("enabled", defaultValue = true)
                val externalAccess = cursor.getBooleanColumn("ext_access", defaultValue = false)
                TaskerPrefs(enabled = enabled, externalAccess = externalAccess)
            }
        }.getOrNull()
    }

    private fun queryTasks(): List<TaskerTask> {
        return runCatching {
            context.contentResolver.query(TASKER_TASKS_URI, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                val projectCol = cursor.getColumnIndex("project_name")
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getStringOrBlank(nameCol)
                        if (name.isNotBlank()) {
                            add(
                                TaskerTask(
                                    name = name,
                                    projectName = cursor.getStringOrBlank(projectCol),
                                ),
                            )
                        }
                    }
                }.distinctBy { it.name.lowercase() }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private data class TaskerPrefs(
        val enabled: Boolean,
        val externalAccess: Boolean,
    )

    companion object {
        private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
        private const val LEGACY_TASKER_PACKAGE = "net.dinglisch.android.tasker"
        private const val ACTION_TASKER_TASK = "net.dinglisch.android.tasker.ACTION_TASK"
        const val PERMISSION_RUN_TASKS = "net.dinglisch.android.tasker.PERMISSION_RUN_TASKS"
        private const val EXTRA_TASK_NAME = "task_name"
        private const val EXTRA_VERSION_NUMBER = "version_number"
        private const val TAG = "TaskerBridge-Tasker"
        private val TASKER_TASKS_URI: Uri = Uri.parse("content://net.dinglisch.android.tasker/tasks")
        private val TASKER_PREFS_URI: Uri = Uri.parse("content://net.dinglisch.android.tasker/prefs")
    }
}

private fun PackageManager.getPackageInfoCompat(packageName: String) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
}

private fun android.database.Cursor.getStringOrBlank(index: Int): String =
    if (index >= 0) getString(index).orEmpty() else ""

private fun android.database.Cursor.getBooleanColumn(name: String, defaultValue: Boolean): Boolean {
    val index = getColumnIndex(name)
    if (index < 0) return defaultValue
    return when (getString(index)?.lowercase()) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> getInt(index) != 0
    }
}
