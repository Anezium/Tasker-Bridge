package com.anezium.taskerbridge.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.anezium.taskerbridge.shared.ControlMessage
import com.anezium.taskerbridge.shared.JsonProtocol
import com.anezium.taskerbridge.shared.Protocol
import com.anezium.taskerbridge.shared.StatusMessage
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import java.io.File

class CxrPhoneController(
    private val context: Context,
    private val onAuthorized: (Boolean) -> Unit,
    private val onConnectionChanged: (cxr: Boolean, bt: Boolean) -> Unit,
    private val onHelperMessage: (StatusMessage) -> Unit,
    private val onInstallStatus: (message: String, busy: Boolean) -> Unit,
    private val onHelperInstalled: (Boolean) -> Unit = {},
    private val onHelperOpened: (Boolean) -> Unit = {},
    private val onLog: (String) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) {
    private var cxrConnected = false
    private var btConnected = false
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var token: String = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty()
    private var link: CXRLink? = null
    private var pendingInstallApk: File? = null
    private var installStarted = false

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            onLog("CXR-L service connected: $connected")
            onConnectionChanged(cxrConnected, btConnected)
            maybeUploadPendingHelper()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            btConnected = connected
            onLog("Glasses Bluetooth connected: $connected")
            onConnectionChanged(cxrConnected, btConnected)
            maybeUploadPendingHelper()
        }

        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
    }

    private val commandCallback = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (key != Protocol.STATUS_CHANNEL || payload == null) return
            runCatching {
                val caps = Caps.fromBytes(payload)
                val raw = caps.readStringPairPayload() ?: return
                val message = JsonProtocol.decodeStatus(raw)
                Log.d(TAG, "received helper status ${message.type}")
                onHelperMessage(message)
            }.onFailure {
                onError("Failed to parse glasses helper message", it)
            }
        }
    }

    private val appCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            installStarted = false
            pendingInstallApk?.delete()
            pendingInstallApk = null
            val message = if (success) {
                "Helper installed on glasses."
            } else {
                "Helper install failed. Check Hi Rokid and retry."
            }
            onInstallStatus(message, false)
            onLog(message)
            onHelperInstalled(success)
        }

        override fun onUnInstallAppResult(success: Boolean) = onLog("Helper uninstall: $success")

        override fun onOpenAppResult(success: Boolean) {
            val message = if (success) "Helper opened on glasses." else "Helper launch failed."
            onInstallStatus(message, false)
            onLog(message)
            onHelperOpened(success)
        }

        override fun onStopAppResult(success: Boolean) = onLog("Helper stop: $success")
        override fun onGlassAppResume(resumed: Boolean) = onLog("Helper resumed: $resumed")
        override fun onQueryAppResult(installed: Boolean) = onLog("Helper installed: $installed")
    }

    fun isRequiredRokidAppInstalled(context: Context): Boolean =
        isGlobalHiRokidInstalled(context)

    fun isAuthorized(): Boolean = token.isNotBlank()

    fun isConnected(): Boolean = cxrConnected || btConnected

    fun requestAuthorization(activity: Activity, requestCode: Int) {
        if (!isGlobalHiRokidInstalled(activity)) {
            onError("Global Hi Rokid is not installed.", null)
            return
        }
        runCatching {
            val intent = Intent().setComponent(ComponentName(GLOBAL_AI_APP_PACKAGE, AUTH_ACTIVITY_CLASS))
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
        }.recoverCatching {
            val fallback = Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(fallback, requestCode)
        }.onSuccess {
            onLog("Hi Rokid authorization opened.")
        }.onFailure {
            onError("Failed to open Hi Rokid authorization.", it)
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        val result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        when (result) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
                onAuthorized(true)
            }
            is AuthResult.AuthFail -> {
                prefs.edit().remove(KEY_AUTH_TOKEN).apply()
                token = ""
                onAuthorized(false)
            }
            else -> {
                prefs.edit().remove(KEY_AUTH_TOKEN).apply()
                token = ""
                onAuthorized(false)
            }
        }
    }

    fun connect() {
        if (token.isBlank()) {
            onError("Missing CXR-L authorization token.", null)
            return
        }
        if (!isWifiEnabled()) {
            onError("Turn on phone Wi-Fi first. Hi Rokid uses it for CXR-L install.", null)
            return
        }
        val nextLink = CXRLink(context).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    Protocol.HELPER_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
            setCXRCustomCmdCbk(commandCallback)
        }
        link = nextLink
        cxrConnected = false
        btConnected = false
        onConnectionChanged(false, false)
        val bound = bindGlobalHiRokidService(nextLink, token)
        if (bound) {
            onLog("Binding to global Hi Rokid service...")
        } else {
            onError("CXR-L service bind failed. Open Hi Rokid, then retry.", null)
        }
    }

    fun installHelper() {
        onInstallStatus("Preparing helper APK...", true)
        if (token.isBlank()) {
            val message = "Authorize Rokid before installing the helper."
            onInstallStatus(message, false)
            onError(message, null)
            return
        }
        if (link == null) {
            connect()
        }
        val apk = extractBundledHelperApk() ?: helperApkCandidates().firstOrNull { it.exists() && it.isFile }
        if (apk == null) {
            val message = "Helper APK not found in this phone build."
            onInstallStatus(message, false)
            onError(message, null)
            return
        }
        val packageName = runCatching { readPackageName(apk) }.getOrElse {
            val message = "Could not read helper APK."
            onInstallStatus(message, false)
            onError(message, it)
            return
        }
        if (packageName != Protocol.HELPER_PACKAGE) {
            val message = "Bundled helper package mismatch: $packageName"
            onInstallStatus(message, false)
            onError(message, null)
            return
        }
        pendingInstallApk = apk
        installStarted = false
        val message = if (cxrConnected && btConnected) {
            "Uploading helper APK to glasses..."
        } else {
            "Waiting for CXR-L and Bluetooth before install..."
        }
        onInstallStatus(message, true)
        onLog(message)
        maybeUploadPendingHelper()
    }

    fun ensureHelperRunning() {
        val activeLink = link
        if (activeLink == null) {
            onInstallStatus("Waiting for CXR-L before opening helper...", true)
            connect()
            return
        }
        if (!cxrConnected || !btConnected) {
            onInstallStatus("Waiting for CXR-L and Bluetooth before opening helper...", true)
            return
        }
        onInstallStatus("Checking helper on glasses...", true)
        runCatching {
            activeLink.appIsInstalled(object : IGlassAppCbk {
                override fun onInstallAppResult(success: Boolean) = Unit
                override fun onUnInstallAppResult(success: Boolean) = Unit
                override fun onOpenAppResult(success: Boolean) = Unit
                override fun onStopAppResult(success: Boolean) = Unit
                override fun onGlassAppResume(resumed: Boolean) = Unit

                override fun onQueryAppResult(installed: Boolean) {
                    onLog("Helper installed: $installed")
                    if (installed) {
                        onInstallStatus("Helper already installed. Opening HUD...", true)
                        launchHelper()
                    } else {
                        installHelper()
                    }
                }
            })
        }.onFailure {
            onInstallStatus("Helper check failed. Opening HUD...", true)
            onError("Helper install check failed. Trying to open HUD.", it)
            launchHelper()
        }
    }

    fun launchHelper() {
        val activeLink = link
        if (activeLink == null) {
            onError("Connect Rokid before launching the helper.", null)
            return
        }
        onInstallStatus("Opening helper on glasses...", true)
        activeLink.appStart(Protocol.HELPER_MAIN_ACTIVITY, appCallback)
    }

    fun stopHelper() {
        link?.appStop(appCallback) ?: onError("CXR-L is not connected.", null)
    }

    fun sendTaskList(message: ControlMessage.TaskList) {
        sendControl(message)
    }

    fun sendLaunchResult(message: ControlMessage.LaunchResult) {
        sendControl(message)
    }

    fun sendStatus(message: String, urgent: Boolean = false) {
        sendControl(ControlMessage.SetStatus(message, urgent))
    }

    private fun sendControl(message: ControlMessage) {
        val activeLink = link
        if (activeLink == null || (!cxrConnected && !btConnected)) {
            Log.d(TAG, "skip sendControl ${message.type}: CXR-L not ready")
            return
        }
        val raw = JsonProtocol.encodeControl(message)
        val caps = Caps().apply {
            write("json")
            write(raw)
        }
        runCatching {
            activeLink.sendCustomCmd(Protocol.CONTROL_CHANNEL, caps.serialize())
        }.onSuccess { result ->
            Log.d(TAG, "sendControl ${message.type}: $result")
            onLog("Sent ${message.type}")
        }.onFailure { error ->
            Log.w(TAG, "sendControl ${message.type} failed", error)
            onError("CXR-L command send failed. Reconnecting...", error)
            cxrConnected = false
            btConnected = false
            onConnectionChanged(false, false)
        }
    }

    private fun helperApkCandidates(): List<File> {
        val appContext = context.applicationContext
        return listOfNotNull(
            appContext.getExternalFilesDir(null)?.resolve("tasker-bridge-glasses-debug.apk"),
            appContext.filesDir.resolve("tasker-bridge-glasses-debug.apk"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "tasker-bridge-glasses-debug.apk"),
        )
    }

    private fun extractBundledHelperApk(): File? {
        val dir = File(context.cacheDir, "cxrl-upload").apply { mkdirs() }
        val output = dir.resolve("tasker-bridge-glasses-${System.currentTimeMillis()}.apk")
        return runCatching {
            context.assets.open("tasker-bridge-glasses-debug.apk").use { input ->
                output.outputStream().use { outputStream -> input.copyTo(outputStream) }
            }
            output
        }.getOrNull()
    }

    private fun maybeUploadPendingHelper() {
        val apk = pendingInstallApk ?: return
        val activeLink = link ?: return
        if (installStarted || !cxrConnected || !btConnected) return
        installStarted = true
        onInstallStatus("Uploading helper APK to glasses...", true)
        runCatching {
            activeLink.appUploadAndInstall(apk.absolutePath, appCallback)
        }.onFailure {
            installStarted = false
            onInstallStatus("Helper upload failed. Retry install.", false)
            onError("Helper upload failed.", it)
        }
    }

    private fun bindGlobalHiRokidService(link: CXRLink, authToken: String): Boolean {
        return runCatching {
            val connection = findServiceConnection(link)
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(GLOBAL_AI_APP_PACKAGE)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, context.applicationContext.packageName)
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            onError("Reflection bind failed.", it)
            false
        }
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection {
        var type: Class<*>? = link.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { field ->
                ServiceConnection::class.java.isAssignableFrom(field.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(link) as ServiceConnection
            }
            type = type.superclass
        }
        error("CXR-L ServiceConnection field not found")
    }

    private fun isGlobalHiRokidInstalled(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    GLOBAL_AI_APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0)
            }
        }.isSuccess
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun readPackageName(apkFile: File): String {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES,
        )
        return info?.packageName?.takeIf { it.isNotBlank() }
            ?: error("Cannot read helper APK package name")
    }

    private fun Caps.readStringPairPayload(): String? {
        if (size() == 0) return null
        if (size() == 1) return at(0).string
        return at(1).string ?: at(0).string
    }

    companion object {
        const val AUTH_REQUEST_CODE = 4107
        private const val TAG = "TaskerBridge-CXRL"
        private const val PREFS_NAME = "cxr_l_auth"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"
    }
}
