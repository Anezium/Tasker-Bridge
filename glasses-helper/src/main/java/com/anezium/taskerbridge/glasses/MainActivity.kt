package com.anezium.taskerbridge.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anezium.taskerbridge.shared.Protocol

class MainActivity : ComponentActivity() {
    private lateinit var runtime: HelperRuntime
    private var lastInputAtMs = 0L
    private var firstResumeHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        runtime = HelperRuntime.get(applicationContext)
        val startDeferred = requestBluetoothPermissionsIfNeeded()
        if (!startDeferred) {
            runtime.start()
        }
        setContent {
            val state by runtime.state.collectAsState()
            BackHandler {
                hideHudOrNavigateBack()
            }
            HelperScreen(
                state = state,
                onProjectFocus = runtime::selectProjectAt,
                onPrevious = {
                    if (acceptInput()) {
                        Log.d(TAG, "accessibility previous")
                        runtime.previousTask()
                    }
                },
                onNext = {
                    if (acceptInput()) {
                        Log.d(TAG, "accessibility next")
                        runtime.nextTask()
                    }
                },
                onActivateSelected = {
                    if (acceptInput()) {
                        Log.d(TAG, "accessibility activate selected")
                        launchSelectedTaskAndHideHud()
                    }
                },
                onTaskFocus = runtime::selectTaskAt,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!firstResumeHandled) {
            firstResumeHandled = true
            return
        }
        if (::runtime.isInitialized) {
            runtime.resume()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSIONS_REQUEST && ::runtime.isInitialized) {
            runtime.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && ::runtime.isInitialized) {
            runtime.close()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
            return super.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KEYCODE_ROKID_SWIPE_FORWARD -> {
                if (!acceptInput()) return true
                Log.d(TAG, "input next key=${event.keyCode}")
                runtime.nextTask()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KEYCODE_ROKID_SWIPE_BACK -> {
                if (!acceptInput()) return true
                Log.d(TAG, "input previous key=${event.keyCode}")
                runtime.previousTask()
                true
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KEYCODE_ROKID_CLICK -> {
                if (!acceptInput()) return true
                Log.d(TAG, "input launch key=${event.keyCode}")
                launchSelectedTaskAndHideHud()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                Log.d(TAG, "input back")
                hideHudOrNavigateBack()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onDestroy() {
        if (isFinishing && ::runtime.isInitialized) {
            runtime.close()
        }
        super.onDestroy()
    }

    private fun acceptInput(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputAtMs < INPUT_DEBOUNCE_MS) return false
        lastInputAtMs = now
        return true
    }

    private fun hideHudOrNavigateBack() {
        if (runtime.navigateBack()) return
        runtime.close()
        finish()
    }

    private fun launchSelectedTaskAndHideHud() {
        runtime.launchSelectedTask(
            onLaunchRequestSent = {
                runtime.close()
                finish()
            },
        )
    }

    private fun requestBluetoothPermissionsIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val missing = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        ActivityCompat.requestPermissions(
            this,
            missing.toTypedArray(),
            BLUETOOTH_PERMISSIONS_REQUEST,
        )
        return true
    }

    companion object {
        private const val TAG = "TaskerBridge-HUD"
        private const val BLUETOOTH_PERMISSIONS_REQUEST = 82
        private const val KEYCODE_ROKID_CLICK = 202
        private const val KEYCODE_ROKID_SWIPE_FORWARD = 183
        private const val KEYCODE_ROKID_SWIPE_BACK = 184
        private const val INPUT_DEBOUNCE_MS = 180L
    }
}

@Composable
private fun HelperScreen(
    state: HelperUiState,
    onProjectFocus: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onActivateSelected: () -> Unit,
    onTaskFocus: (Int) -> Unit,
) {
    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(state)
                TaskRows(
                    state = state,
                    onProjectFocus = onProjectFocus,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onActivateSelected = onActivateSelected,
                    onTaskFocus = onTaskFocus,
                    modifier = Modifier.weight(1f),
                )
                StatusLine(state)
            }
        }
    }
}

@Composable
private fun Header(state: HelperUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Tasker Bridge",
            color = HudText,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.headerSubtitle(),
            color = state.headerColor(),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskRows(
    state: HelperUiState,
    onProjectFocus: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onActivateSelected: () -> Unit,
    onTaskFocus: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = state.menuModel()
    val projects = model.projects
    val project = model.selectedProject
    val rowCount = model.rowCount
    val selectedRowIndex = model.selectedRowIndex
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (state.tasks.isEmpty() || rowCount == 0) {
            EmptyState(
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
            return@BoxWithConstraints
        }

        val visibleRows = visibleTaskRows(
            viewportHeight = maxHeight,
            taskCount = rowCount,
        )
        val targetFirstVisible = taskWindowStart(
            taskCount = rowCount,
            selectedIndex = selectedRowIndex,
            visibleRows = visibleRows,
        )

        LaunchedEffect(selectedRowIndex, rowCount, visibleRows, state.viewMode, state.selectedProjectName) {
            listState.animateScrollToItem(targetFirstVisible)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(TASK_ROW_SPACING_DP.dp),
            userScrollEnabled = false,
        ) {
            when (state.viewMode) {
                HelperViewMode.PROJECTS -> {
                    itemsIndexed(projects) { index, item ->
                        MenuRow(
                            title = item.displayName,
                            subtitle = taskCountLabel(item.taskIndices.size),
                            isSelected = index == selectedRowIndex,
                            actionLabel = "Open project",
                            onFocus = { onProjectFocus(index) },
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onActivate = onActivateSelected,
                        )
                    }
                }
                HelperViewMode.TASKS -> {
                    val taskIndices = project?.taskIndices.orEmpty()
                    itemsIndexed(taskIndices) { index, taskIndex ->
                        val task = state.tasks[taskIndex]
                        MenuRow(
                            title = task.name,
                            subtitle = "",
                            isSelected = index == selectedRowIndex,
                            actionLabel = "Run task",
                            onFocus = { onTaskFocus(taskIndex) },
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onActivate = onActivateSelected,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    actionLabel: String,
    onFocus: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onActivate: () -> Unit,
) {
    val accessibilityLabel = if (subtitle.isBlank()) title else "$title, $subtitle"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TASK_ROW_HEIGHT_DP.dp)
            .clickable(
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onActivate,
            )
            .onFocusChanged { focusState ->
                if (focusState.isFocused) onFocus()
            }
            .focusable()
            .clearAndSetSemantics {
                role = Role.Button
                selected = isSelected
                stateDescription = if (isSelected) "Selected" else "Not selected"
                contentDescription = accessibilityLabel
                customActions = listOf(
                    CustomAccessibilityAction(label = ACCESSIBILITY_SELECT_ACTION_LABEL) {
                        onFocus()
                        true
                    },
                    CustomAccessibilityAction(label = ACCESSIBILITY_PREVIOUS_ACTION_LABEL) {
                        onPrevious()
                        true
                    },
                    CustomAccessibilityAction(label = ACCESSIBILITY_NEXT_ACTION_LABEL) {
                        onNext()
                        true
                    },
                )
                onClick(label = actionLabel) {
                    onActivate()
                    true
                }
            }
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) HudFocus else HudBorder,
                shape = RowShape,
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (isSelected) HudText else HudText.copy(alpha = 0.82f),
                fontSize = 18.sp,
                fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = HudMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun visibleTaskRows(
    viewportHeight: Dp,
    taskCount: Int,
): Int {
    val rows = ((viewportHeight.value + TASK_ROW_SPACING_DP) / TASK_ROW_STRIDE_DP).toInt()
    return rows
        .coerceAtLeast(1)
        .coerceAtMost(Protocol.MAX_TASKS_ON_HUD)
        .coerceAtMost(taskCount)
}

private fun taskWindowStart(
    taskCount: Int,
    selectedIndex: Int,
    visibleRows: Int,
): Int {
    if (taskCount <= visibleRows) return 0
    val safeSelectedIndex = selectedIndex.coerceIn(0, taskCount - 1)
    val maxStart = taskCount - visibleRows
    return (safeSelectedIndex - visibleRows + 1).coerceIn(0, maxStart)
}

@Composable
private fun EmptyState(
    state: HelperUiState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, HudBorder, RowShape)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            state.status,
            color = HudMuted,
            fontSize = 18.sp,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun StatusLine(state: HelperUiState) {
    val success = state.lastLaunchSuccess
    val color = when (success) {
        true -> HudFocus
        false -> HudError
        null -> HudMuted
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            state.status,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.bridgeState,
            color = HudMuted.copy(alpha = 0.74f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun HelperUiState.headerSubtitle(): String = when {
    !phoneConnected -> "Waiting for phone Bluetooth"
    !taskerInstalled -> "Tasker not detected on phone"
    !taskerEnabled -> "Tasker disabled"
    !externalAccess -> "External access off"
    tasks.isEmpty() -> "No named tasks"
    else -> menuSubtitle()
}

private fun HelperUiState.menuSubtitle(): String {
    val model = menuModel()
    val projects = model.projects
    if (projects.isEmpty()) return "No named tasks"
    return when (viewMode) {
        HelperViewMode.PROJECTS -> "Project ${model.safeProjectIndex + 1}/${projects.size}"
        HelperViewMode.TASKS -> {
            val project = model.selectedProject ?: return "Project ${model.safeProjectIndex + 1}/${projects.size}"
            "${project.displayName} ${model.selectedRowIndex + 1}/${project.taskIndices.size}"
        }
    }
}

private fun taskCountLabel(count: Int): String =
    if (count == 1) "1 task" else "$count tasks"

private fun HelperUiState.headerColor(): Color = when {
    phoneConnected && taskerInstalled && taskerEnabled && externalAccess && tasks.isNotEmpty() -> HudFocus
    else -> HudWarn
}

private const val TASK_ROW_HEIGHT_DP = 52f
private const val TASK_ROW_SPACING_DP = 7f
private const val TASK_ROW_STRIDE_DP = TASK_ROW_HEIGHT_DP + TASK_ROW_SPACING_DP
private const val ACCESSIBILITY_SELECT_ACTION_LABEL = "Select"
private const val ACCESSIBILITY_PREVIOUS_ACTION_LABEL = "Previous"
private const val ACCESSIBILITY_NEXT_ACTION_LABEL = "Next"

private val HudText = Color(0xFFEAF3EB)
private val HudMuted = Color(0xFF9AA79E)
private val HudFocus = Color(0xFF66E83A)
private val HudWarn = Color(0xFFF0C84B)
private val HudError = Color(0xFFFF6B5E)
private val HudBorder = Color(0x665C6C61)
private val RowShape = RoundedCornerShape(7.dp)
