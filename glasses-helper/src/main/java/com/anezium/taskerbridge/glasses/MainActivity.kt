package com.anezium.taskerbridge.glasses

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        runtime = HelperRuntime.get()
        runtime.start()
        setContent {
            val state by runtime.state.collectAsState()
            HelperScreen(state)
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
                runtime.launchSelectedTask()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                Log.d(TAG, "input back")
                moveTaskToBack(true)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun acceptInput(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInputAtMs < INPUT_DEBOUNCE_MS) return false
        lastInputAtMs = now
        return true
    }

    companion object {
        private const val TAG = "TaskerBridge-HUD"
        private const val KEYCODE_ROKID_CLICK = 202
        private const val KEYCODE_ROKID_SWIPE_FORWARD = 183
        private const val KEYCODE_ROKID_SWIPE_BACK = 184
        private const val INPUT_DEBOUNCE_MS = 180L
    }
}

@Composable
private fun HelperScreen(state: HelperUiState) {
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
                TaskRows(state)
                Spacer(modifier = Modifier.weight(1f))
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
private fun TaskRows(state: HelperUiState) {
    val maxRows = Protocol.MAX_TASKS_ON_HUD
    val windowStart = when {
        state.tasks.size <= maxRows -> 0
        state.selectedIndex < maxRows -> 0
        else -> (state.selectedIndex - maxRows + 1).coerceAtMost(state.tasks.size - maxRows)
    }
    val visible = state.tasks.drop(windowStart).take(maxRows)
    if (visible.isEmpty()) {
        EmptyState(state)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        visible.forEachIndexed { localIndex, task ->
            val index = windowStart + localIndex
            val selected = index == state.selectedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) HudFocus else HudBorder,
                        shape = RowShape,
                    )
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "%02d".format(index + 1),
                    color = if (selected) HudFocus else HudMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        color = if (selected) HudText else HudText.copy(alpha = 0.82f),
                        fontSize = 18.sp,
                        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.projectName.isNotBlank()) {
                        Text(
                            task.projectName,
                            color = HudMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: HelperUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
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
    !taskerInstalled -> "Tasker not detected on phone"
    !taskerEnabled -> "Tasker disabled"
    !externalAccess -> "External access off"
    tasks.isEmpty() -> "No named tasks"
    else -> "${tasks.size} tasks"
}

private fun HelperUiState.headerColor(): Color = when {
    taskerInstalled && taskerEnabled && externalAccess && tasks.isNotEmpty() -> HudFocus
    else -> HudWarn
}

private val HudText = Color(0xFFEAF3EB)
private val HudMuted = Color(0xFF9AA79E)
private val HudFocus = Color(0xFF66E83A)
private val HudWarn = Color(0xFFF0C84B)
private val HudError = Color(0xFFFF6B5E)
private val HudBorder = Color(0x665C6C61)
private val RowShape = RoundedCornerShape(7.dp)
