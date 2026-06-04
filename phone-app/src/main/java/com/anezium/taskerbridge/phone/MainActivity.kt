package com.anezium.taskerbridge.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var runtime: BridgeRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 18)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 18)

        runtime = BridgeRuntime.get(applicationContext)
        runtime.start()
        requestPermissionsThenAutoStart()

        setContent {
            val state by runtime.state.collectAsState()
            PhoneScreen(
                state = state,
                onStartBridge = { runtime.startBridgeFromUi(this) },
                onLaunchHelper = { runtime.launchHelper() },
                onSelectTask = { index -> runtime.selectTask(index) },
                onRunTask = { taskName -> runtime.runTaskFromPhone(taskName) },
            )
        }
    }

    @Deprecated("CXR-L authorization still uses startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CxrPhoneController.AUTH_REQUEST_CODE) {
            runtime.handleAuthorizationResult(resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            startForegroundBridge()
            runtime.autoStartFromActivity(this)
        }
    }

    private fun requestPermissionsThenAutoStart() {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return
        }
        startForegroundBridge()
        runtime.autoStartFromActivity(this)
    }

    private fun startForegroundBridge() {
        runCatching { BridgeForegroundService.start(this) }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 20
    }
}

@Composable
private fun PhoneScreen(
    state: PhoneUiState,
    onStartBridge: () -> Unit,
    onLaunchHelper: () -> Unit,
    onSelectTask: (Int) -> Unit,
    onRunTask: (String) -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = PhoneBg,
            surface = PhonePanel,
            primary = BridgeGreen,
            secondary = BridgeAmber,
            error = BridgeRed,
            onBackground = PhoneText,
            onSurface = PhoneText,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = PhoneBg) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopStatusBar(state)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ErrorBanner(state.error)
                    Section("Bridge") {
                        StatusRow("Service", if (state.bridgeServiceActive) "foreground" else "starting", state.bridgeServiceActive)
                        StatusRow("Hi Rokid", if (state.requiredRokidAppInstalled) "installed" else "missing", state.requiredRokidAppInstalled)
                        StatusRow("Authorization", if (state.authorized) "ready" else "needed", state.authorized)
                        StatusRow("CXR-L", if (state.cxrConnected) "connected" else "connecting", state.cxrConnected)
                        StatusRow("Glasses BT", if (state.glassBtConnected) "connected" else "waiting", state.glassBtConnected)
                        BridgeButton("Start bridge", state.requiredRokidAppInstalled, onStartBridge)
                        OutlinedBridgeButton("Launch HUD", state.cxrConnected || state.glassBtConnected, onLaunchHelper)
                        SmallText(state.helperInstallStatus)
                    }

                    Section("Tasker") {
                        StatusRow("Tasker", state.taskerStatusLabel(), state.taskerReady())
                        SmallText("HUD refreshes on open or resume. ${state.lastStatus}")
                        TaskList(
                            tasks = state.tasks,
                            selectedIndex = state.selectedIndex,
                            helperSelectedIndex = state.helperSelectedIndex,
                            onSelectTask = onSelectTask,
                            onRunTask = onRunTask,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopStatusBar(state: PhoneUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(PhonePanel)
            .drawBehind {
                drawLine(
                    color = PhoneBorder,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Tasker Bridge", color = PhoneText, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(
            state.bridgeSummary(),
            color = PhoneMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PhonePanel, PanelShape)
            .border(1.dp, PhoneBorder, PanelShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = PhoneText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun BridgeButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = BridgeGreen,
            contentColor = Color(0xFF071006),
            disabledContainerColor = Color(0xFF263026),
            disabledContentColor = PhoneMuted,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutlinedBridgeButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = PhoneMuted, fontSize = 13.sp)
        Text(
            value,
            color = if (ok) BridgeGreen else BridgeAmber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TaskList(
    tasks: List<com.anezium.taskerbridge.shared.TaskerTask>,
    selectedIndex: Int,
    helperSelectedIndex: Int,
    onSelectTask: (Int) -> Unit,
    onRunTask: (String) -> Unit,
) {
    if (tasks.isEmpty()) {
        SmallText("No named Tasker tasks found.")
        return
    }
    Column(
        modifier = Modifier.heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tasks.take(32).forEachIndexed { index, task ->
            val selected = index == selectedIndex
            val helperSelected = index == helperSelectedIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) PhoneSelected else Color.Transparent, RowShape)
                    .border(1.dp, if (helperSelected) BridgeGreen else PhoneBorder, RowShape)
                    .clickable { onSelectTask(index) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.name,
                        color = PhoneText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.projectName.isNotBlank()) {
                        Text(task.projectName, color = PhoneMuted, fontSize = 12.sp, maxLines = 1)
                    }
                }
                OutlinedButton(onClick = { onRunTask(task.name) }) {
                    Text("Run")
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    if (error.isBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF321716), PanelShape)
            .border(1.dp, BridgeRed, PanelShape)
            .padding(12.dp),
    ) {
        Text(error, color = Color(0xFFFFB4AB), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallText(text: String) {
    Text(text, color = PhoneMuted, fontSize = 13.sp, lineHeight = 18.sp)
}

private fun PhoneUiState.bridgeReady(): Boolean =
    bridgeServiceActive && authorized && cxrConnected && glassBtConnected

private fun PhoneUiState.bridgeSummary(): String = when {
    bridgeReady() && taskerReady() -> "HUD connected, ${tasks.size} Tasker tasks available."
    !requiredRokidAppInstalled -> "Global Hi Rokid is missing on this phone."
    !authorized -> "Hi Rokid authorization will open automatically."
    !cxrConnected -> "CXR-L is connecting in the foreground service."
    !glassBtConnected -> "Waiting for the glasses Bluetooth link."
    else -> lastStatus
}

private fun PhoneUiState.taskerStatusLabel(): String = when {
    !taskerInstalled -> "missing"
    !taskerEnabled -> "disabled"
    !externalAccess -> "external access off"
    !taskerRunPermissionGranted -> "permission needed"
    else -> "${tasks.size} tasks"
}

private fun PhoneUiState.taskerReady(): Boolean =
    taskerInstalled && taskerEnabled && externalAccess && taskerRunPermissionGranted

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(TaskerRepository.PERMISSION_RUN_TASKS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private val PhoneBg = Color(0xFF101312)
private val PhonePanel = Color(0xFF171D1A)
private val PhoneSelected = Color(0xFF1F2A20)
private val PhoneBorder = Color(0xFF2B3831)
private val PhoneText = Color(0xFFE7EEE8)
private val PhoneMuted = Color(0xFF9DAAA0)
private val BridgeGreen = Color(0xFF66E83A)
private val BridgeAmber = Color(0xFFF0C84B)
private val BridgeRed = Color(0xFFFF6B5E)
private val PanelShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(8.dp)
