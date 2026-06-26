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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.anezium.taskerbridge.shared.TaskerTask

class MainActivity : ComponentActivity() {
    private lateinit var runtime: BridgeRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 19, 18)
        window.navigationBarColor = android.graphics.Color.rgb(16, 19, 18)

        runtime = BridgeRuntime.get(applicationContext)
        runtime.start()
        requestRequiredPermissions()

        setContent {
            val state by runtime.state.collectAsState()
            PhoneScreen(
                state = state,
                onStartService = { armWakeBridge() },
                onStopService = { stopWakeBridge() },
                onRefreshTasker = { runtime.refreshTasker(sendToGlasses = state.bluetoothConnected) },
                onInstallHud = { runtime.installHudFromUi(this) },
                onLaunchHud = {
                    if (CompanionDeviceCoordinator.hasAssociation(this)) {
                        runtime.launchHudFromUi(this)
                    } else {
                        runtime.armWakeBridgeFromUi(this)
                    }
                },
                onForgetBluetoothPairing = { runtime.forgetBluetoothPairing() },
            )
        }
    }

    @Deprecated("CXR-L authorization still uses startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CxrPhoneController.AUTH_REQUEST_CODE) {
            runtime.handleAuthorizationResult(resultCode, data)
        } else if (requestCode == CompanionDeviceCoordinator.REQUEST_CODE) {
            runtime.handleCompanionAssociationResult(resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun requestRequiredPermissions() {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) {
            runtime.refreshDiagnostics()
        }
    }

    private fun armWakeBridge() {
        runtime.armWakeBridgeFromUi(this)
    }

    private fun stopWakeBridge() {
        runtime.stopBackground()
        runCatching { BridgeForegroundService.stop(this) }
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 20
    }
}

@Composable
private fun PhoneScreen(
    state: PhoneUiState,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRefreshTasker: () -> Unit,
    onInstallHud: () -> Unit,
    onLaunchHud: () -> Unit,
    onForgetBluetoothPairing: () -> Unit,
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

                    Section("Glasses HUD") {
                        StatusRow("Hi Rokid", if (state.requiredRokidAppInstalled) "installed" else "missing", state.requiredRokidAppInstalled)
                        StatusRow("Authorization", if (state.authorized) "saved" else "needed", state.authorized)
                        StatusRow("Setup link", state.setupLinkLabel(), state.cxrConnected || state.glassBtConnected)
                        StatusRow("Bundled version", state.helperBundledVersion, state.helperBundledVersion != "unknown")
                        StatusRow("Installed version", state.helperLastInstalledVersion, state.helperLastInstalledVersion != "none recorded")
                        StatusRow("Running HUD", state.helperRuntimeVersion, state.helperRuntimeCurrent)
                        SmallText(state.helperInstallStatus)
                        BridgeButton(
                            "Install HUD",
                            state.requiredRokidAppInstalled && !state.helperInstallBusy,
                            onInstallHud,
                        )
                        OutlinedBridgeButton(
                            "Launch HUD",
                            state.requiredRokidAppInstalled && !state.helperInstallBusy,
                            onLaunchHud,
                        )
                    }

                    Section("Phone Bridge") {
                        StatusRow("Companion link", if (state.companionLinked) "linked" else "needed", state.companionLinked)
                        StatusRow("Wake bridge", if (state.bluetoothServerActive) "armed" else "off", state.bluetoothServerActive)
                        StatusRow("Active session", if (state.bridgeServiceActive) "running" else "idle", state.bridgeServiceActive)
                        StatusRow("Pairing", state.bluetoothPairingLabel(), state.bluetoothPaired)
                        StatusRow("HUD connection", state.bluetoothHudLabel(), state.bluetoothConnected)
                        SmallText(state.bluetoothStatus.ifBlank { state.lastStatus })
                        SmallText(state.wakeDiagnostics)
                        OutlinedBridgeButton(
                            if (state.companionLinked) "Arm wake bridge" else "Link glasses for wake",
                            !state.bluetoothServerActive || !state.companionLinked,
                            onStartService,
                        )
                        OutlinedBridgeButton("Stop wake bridge", state.bluetoothServerActive || state.bridgeServiceActive, onStopService)
                        OutlinedBridgeButton(
                            "Forget Bluetooth pairing",
                            state.bluetoothPaired || state.bluetoothConnected,
                            onForgetBluetoothPairing,
                        )
                    }

                    Section("Tasker") {
                        StatusRow("Access", state.taskerStatusLabel(), state.taskerReady())
                        SmallText(state.lastStatus)
                        OutlinedBridgeButton("Refresh Tasker", true, onRefreshTasker)
                        TaskList(tasks = state.tasks)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TaskList(tasks: List<TaskerTask>) {
    if (tasks.isEmpty()) {
        SmallText("No named Tasker tasks found.")
        return
    }
    Column(
        modifier = Modifier.heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        tasks.take(48).forEach { task ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PhoneBorder, RowShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
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

private fun PhoneUiState.bridgeSummary(): String = when {
    bluetoothConnected && taskerReady() -> "HUD connected over Bluetooth, ${tasks.size} Tasker tasks available."
    bridgeServiceActive -> "HUD Bluetooth session is active."
    !companionLinked -> "Link the glasses with Android Companion Device for reliable wake."
    bluetoothServerActive -> "Wake bridge is armed; the HUD can connect over Bluetooth."
    !bridgeServiceActive -> "Arm the wake bridge to accept glasses commands."
    else -> lastStatus
}

private fun PhoneUiState.bluetoothHudLabel(): String = when {
    bluetoothConnected -> "connected"
    bridgeServiceActive -> "waiting"
    else -> "offline"
}

private fun PhoneUiState.bluetoothPairingLabel(): String = when {
    bluetoothConnected -> "connected"
    bluetoothPaired -> "locked"
    bluetoothPairingMode -> "pairing"
    else -> "not paired"
}

private fun PhoneUiState.setupLinkLabel(): String = when {
    cxrConnected && glassBtConnected -> "connected"
    cxrConnected || glassBtConnected -> "starting"
    else -> "idle"
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private val PhoneBg = Color(0xFF101312)
private val PhonePanel = Color(0xFF171D1A)
private val PhoneBorder = Color(0xFF2B3831)
private val PhoneText = Color(0xFFE7EEE8)
private val PhoneMuted = Color(0xFF9DAAA0)
private val BridgeGreen = Color(0xFF66E83A)
private val BridgeAmber = Color(0xFFF0C84B)
private val BridgeRed = Color(0xFFFF6B5E)
private val PanelShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(8.dp)
