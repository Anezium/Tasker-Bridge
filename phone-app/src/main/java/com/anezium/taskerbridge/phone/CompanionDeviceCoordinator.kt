package com.anezium.taskerbridge.phone

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.util.UUID
import java.util.regex.Pattern

object CompanionDeviceCoordinator {
    const val REQUEST_CODE = 4210

    private const val TAG = "TaskerBridge-CDM"
    private val glassesNamePattern = Pattern.compile("rokid|glasses", Pattern.CASE_INSENSITIVE)
    private val rokidGlassesServiceUuid = UUID.fromString("3c36c196-e056-4e4f-b88e-2cb249365f00")

    fun hasAssociation(context: Context): Boolean {
        val manager = manager(context) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                manager.associations.isNotEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "read associations failed: ${it.message}")
            false
        }
    }

    fun requestAssociation(activity: Activity, onFailure: (String) -> Unit) {
        if (hasAssociation(activity)) {
            startObserving(activity)
            onFailure("Glasses already linked")
            return
        }
        val bonded = bondedDevices(activity)
        val likely = bonded.filter(::looksLikeGlasses)
        when {
            likely.size == 1 -> associateWithAddress(activity, likely.first().address, onFailure)
            bonded.isEmpty() -> associateByScan(activity, onFailure)
            else -> pickBondedDevice(activity, likely.ifEmpty { bonded }, onFailure)
        }
    }

    fun handleAssociationResult(context: Context, resultCode: Int, data: Intent?): String? {
        if (resultCode != Activity.RESULT_OK) return null
        val address = extractAddress(data) ?: return null
        startObserving(context)
        Log.i(TAG, "associated with glasses endpoint")
        return address
    }

    fun startObserving(context: Context) {
        val manager = manager(context) ?: return
        associatedAddresses(context).forEach { address ->
            runCatching {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }.onFailure {
                Log.w(TAG, "observe presence failed: ${it.message}")
            }
        }
    }

    private fun pickBondedDevice(
        activity: Activity,
        devices: List<BluetoothDevice>,
        onFailure: (String) -> Unit,
    ) {
        val labels = devices.map { device ->
            runCatching { device.alias ?: device.name }.getOrNull() ?: "Bluetooth device"
        }
        AlertDialog.Builder(activity)
            .setTitle("Select your glasses")
            .setItems(labels.toTypedArray()) { _, index ->
                associateWithAddress(activity, devices[index].address, onFailure)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onFailure("Companion link cancelled")
            }
            .show()
    }

    private fun associateWithAddress(
        activity: Activity,
        address: String,
        onFailure: (String) -> Unit,
    ) {
        associate(activity, onFailure) {
            setSingleDevice(true)
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setAddress(address)
                    .build(),
            )
        }
    }

    private fun associateByScan(activity: Activity, onFailure: (String) -> Unit) {
        associate(activity, onFailure) {
            addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setNamePattern(glassesNamePattern)
                    .build(),
            )
            addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(glassesNamePattern)
                    .build(),
            )
        }
    }

    private fun associate(
        activity: Activity,
        onFailure: (String) -> Unit,
        configure: AssociationRequest.Builder.() -> Unit,
    ) {
        val manager = manager(activity) ?: run {
            onFailure("Companion device service unavailable")
            return
        }
        val request = AssociationRequest.Builder().apply {
            configure()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setDeviceProfile(AssociationRequest.DEVICE_PROFILE_GLASSES)
            }
        }.build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            @Deprecated("Android 12L delivers the chooser through onDeviceFound")
            override fun onDeviceFound(intentSender: IntentSender) {
                launchChooser(activity, intentSender, onFailure)
            }

            override fun onFailure(error: CharSequence?) {
                val message = error?.toString().orEmpty().ifBlank { "Association failed" }
                Log.w(TAG, "association failed: $message")
                onFailure(message)
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            manager.associate(request, callback, null)
        }.onFailure {
            Log.w(TAG, "associate call failed", it)
            onFailure(it.message ?: "Association failed")
        }
    }

    private fun launchChooser(
        activity: Activity,
        intentSender: IntentSender,
        onFailure: (String) -> Unit,
    ) {
        runCatching {
            activity.startIntentSenderForResult(
                intentSender,
                REQUEST_CODE,
                null,
                0,
                0,
                0,
            )
        }.onFailure {
            Log.w(TAG, "chooser launch failed", it)
            onFailure(it.message ?: "Could not open device chooser")
        }
    }

    private fun associatedAddresses(context: Context): List<String> {
        val manager = manager(context) ?: return emptyList()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.toList()
            }
        }.getOrElse {
            Log.w(TAG, "read associated addresses failed: ${it.message}")
            emptyList()
        }
    }

    private fun bondedDevices(context: Context): List<BluetoothDevice> = runCatching {
        context.getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.bondedDevices
            ?.toList()
    }.getOrElse {
        Log.w(TAG, "bonded device lookup failed: ${it.message}")
        null
    }.orEmpty()

    private fun looksLikeGlasses(device: BluetoothDevice): Boolean = runCatching {
        sequenceOf(device.alias, device.name).filterNotNull().any {
            glassesNamePattern.matcher(it).find()
        } || device.uuids?.any { it.uuid == rokidGlassesServiceUuid } == true
    }.getOrDefault(false)

    private fun extractAddress(data: Intent?): String? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val association = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                AssociationInfo::class.java,
            )
            association?.deviceMacAddress?.let { return it.toString() }
        }
        @Suppress("DEPRECATION")
        return when (val device = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> device.address
            is ScanResult -> device.device?.address
            else -> null
        }
    }

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)
}
