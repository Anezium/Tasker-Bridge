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
import com.anezium.taskerbridge.shared.Protocol
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import java.io.File

data class HelperApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
) {
    val label: String
        get() = "$versionName ($versionCode)"
}

class CxrPhoneController(
    private val context: Context,
    private val onAuthorized: (Boolean) -> Unit,
    private val onConnectionChanged: (cxr: Boolean, bt: Boolean) -> Unit,
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
    private var pendingInstallInfo: HelperApkInfo? = null
    private var pendingForceReinstall = false
    private var installStarted = false
    private var uninstallStarted = false

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

    private val appCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            val installedInfo = pendingInstallInfo
            installStarted = false
            uninstallStarted = false
            pendingForceReinstall = false
            pendingInstallApk?.delete()
            pendingInstallApk = null
            pendingInstallInfo = null
            val message = if (success) {
                installedInfo?.let { info ->
                    rememberInstalledHelper(info)
                    "Helper ${info.label} installed on glasses."
                } ?: "Helper installed on glasses."
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

    private val reinstallCallback = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) = Unit

        override fun onUnInstallAppResult(success: Boolean) {
            onLog("Helper uninstall before reinstall: $success")
            pendingForceReinstall = false
            uninstallStarted = false
            installStarted = false
            maybeUploadPendingHelper()
        }

        override fun onOpenAppResult(success: Boolean) = Unit
        override fun onStopAppResult(success: Boolean) = Unit
        override fun onGlassAppResume(resumed: Boolean) = Unit
        override fun onQueryAppResult(installed: Boolean) = Unit
    }

    fun isRequiredRokidAppInstalled(context: Context): Boolean =
        isGlobalHiRokidInstalled(context)

    fun isAuthorized(): Boolean = token.isNotBlank()

    fun isConnected(): Boolean = cxrConnected || btConnected

    fun bundledHelperVersionLabel(): String =
        readBundledHelperInfo()?.label ?: "unknown"

    fun lastInstalledHelperVersionLabel(): String =
        prefs.getString(KEY_HELPER_INSTALLED_VERSION_NAME, null)
            ?.let { versionName ->
                val versionCode = prefs.getLong(KEY_HELPER_INSTALLED_VERSION_CODE, -1L)
                if (versionCode >= 0) "$versionName ($versionCode)" else versionName
            }
            ?: "none recorded"

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
        disconnect()
        val nextLink = CXRLink(context).apply {
            configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    Protocol.HELPER_PACKAGE,
                ),
            )
            setCXRLinkCbk(linkCallback)
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

    fun disconnect() {
        runCatching { link?.disconnect() }
            .onFailure { Log.w(TAG, "CXR-L disconnect failed", it) }
        link = null
        cxrConnected = false
        btConnected = false
        onConnectionChanged(false, false)
    }

    fun installHelper(forceReinstall: Boolean = false) {
        onInstallStatus("Preparing bundled helper APK...", true)
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
        val helperInfo = runCatching { readHelperApkInfo(apk) }.getOrElse {
            val message = "Could not read helper APK."
            onInstallStatus(message, false)
            onError(message, it)
            return
        }
        if (helperInfo.packageName != Protocol.HELPER_PACKAGE) {
            val message = "Bundled helper package mismatch: ${helperInfo.packageName}"
            onInstallStatus(message, false)
            onError(message, null)
            return
        }
        pendingInstallApk = apk
        pendingInstallInfo = helperInfo
        pendingForceReinstall = forceReinstall
        installStarted = false
        uninstallStarted = false
        val message = if (cxrConnected && btConnected) {
            if (forceReinstall) {
                "Reinstalling helper ${helperInfo.label} on glasses..."
            } else {
                "Uploading helper ${helperInfo.label} to glasses..."
            }
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
                        val bundled = readBundledHelperInfo()
                        if (bundled != null && shouldRefreshInstalledHelper(bundled)) {
                            onInstallStatus("Helper update needed: bundled ${bundled.label}.", true)
                            installHelper(forceReinstall = true)
                        } else {
                            onInstallStatus("Helper current. Opening HUD...", true)
                            launchHelper()
                        }
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
        if (pendingForceReinstall && !uninstallStarted) {
            uninstallStarted = true
            onInstallStatus("Removing old helper before reinstall...", true)
            runCatching {
                activeLink.appUninstall(reinstallCallback)
            }.onFailure {
                onLog("Helper uninstall failed; uploading anyway.")
                pendingForceReinstall = false
                uninstallStarted = false
                maybeUploadPendingHelper()
            }
            return
        }
        installStarted = true
        val label = pendingInstallInfo?.label.orEmpty()
        onInstallStatus("Uploading helper $label to glasses...".trim(), true)
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

    private fun readBundledHelperInfo(): HelperApkInfo? {
        val apk = extractBundledHelperApk() ?: return null
        return runCatching { readHelperApkInfo(apk) }
            .also { apk.delete() }
            .getOrNull()
    }

    private fun readHelperApkInfo(apkFile: File): HelperApkInfo {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES,
        )
        val packageName = info?.packageName?.takeIf { it.isNotBlank() }
            ?: error("Cannot read helper APK package name")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return HelperApkInfo(
            packageName = packageName,
            versionCode = versionCode,
            versionName = info.versionName ?: "unknown",
        )
    }

    private fun shouldRefreshInstalledHelper(bundled: HelperApkInfo): Boolean {
        val installedVersionCode = prefs.getLong(KEY_HELPER_INSTALLED_VERSION_CODE, -1L)
        val installedPackage = prefs.getString(KEY_HELPER_INSTALLED_PACKAGE, "").orEmpty()
        return installedPackage != bundled.packageName || installedVersionCode != bundled.versionCode
    }

    private fun rememberInstalledHelper(info: HelperApkInfo) {
        prefs.edit()
            .putString(KEY_HELPER_INSTALLED_PACKAGE, info.packageName)
            .putLong(KEY_HELPER_INSTALLED_VERSION_CODE, info.versionCode)
            .putString(KEY_HELPER_INSTALLED_VERSION_NAME, info.versionName)
            .putLong(KEY_HELPER_INSTALLED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val AUTH_REQUEST_CODE = 4107
        private const val TAG = "TaskerBridge-CXRL"
        private const val PREFS_NAME = "cxr_l_auth"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_HELPER_INSTALLED_PACKAGE = "helper_installed_package"
        private const val KEY_HELPER_INSTALLED_VERSION_CODE = "helper_installed_version_code"
        private const val KEY_HELPER_INSTALLED_VERSION_NAME = "helper_installed_version_name"
        private const val KEY_HELPER_INSTALLED_AT_MS = "helper_installed_at_ms"
        private const val GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"
    }
}
