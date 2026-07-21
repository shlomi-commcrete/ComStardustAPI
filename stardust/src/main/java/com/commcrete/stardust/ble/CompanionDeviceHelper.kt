package com.commcrete.stardust.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcelable
import androidx.annotation.RequiresApi
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.regex.Pattern

/**
 * Stage 5 — CompanionDeviceManager (CDM) association.
 *
 * CDM gives a system-managed device association: the OS shows the device picker, the association
 * persists and is reconciled by the platform, and (API 31+) the app can observe device presence for
 * auto-connect without holding the scan permission. This is the modern replacement for the app's
 * hand-rolled scan + bond UX and dovetails with [PairingRepository] adoption.
 *
 * CDM inherently spans the SDK↔host boundary: [associate] must be launched from a host [Activity]
 * (the OS returns an [IntentSender] the host launches), and the host must forward the chooser's
 * result back via [handleAssociationResult]. Presence-based auto-connect additionally requires the
 * host to declare a `CompanionDeviceService` in its manifest — see the KDoc on [startObservingHint].
 *
 * This helper is self-contained and additive; nothing else depends on it.
 */
object CompanionDeviceHelper {

    private val TAG = CompanionDeviceHelper::class.java.simpleName

    /** Case-insensitive name match for our radios, unified with [PairingRepository] tokens. */
    private val NAME_PATTERN: Pattern = Pattern.compile("(?i).*(bittle|bittel|stardust).*")

    /** Whether the platform supports CDM at all. */
    fun isSupported(): Boolean =
        DataManager.appContext.packageManager
            .hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun manager(context: android.content.Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /**
     * Starts CDM association from a host [activity]. The OS surfaces its device picker; when a
     * device is chosen the platform hands back an [IntentSender] via [onLaunch], which the host
     * MUST launch (e.g. `ActivityResultLauncher<IntentSenderRequest>` or
     * `startIntentSenderForResult`). The host then forwards the result [Intent] to
     * [handleAssociationResult]. [onError] reports picker/association failures.
     */
    fun associate(
        activity: Activity,
        onLaunch: (IntentSender) -> Unit,
        onError: (CharSequence) -> Unit,
    ) {
        val cdm = manager(activity) ?: return onError("CompanionDeviceManager unavailable")

        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothDeviceFilter.Builder().setNamePattern(NAME_PATTERN).build()
            )
            .setSingleDevice(false)
            .build()

        val callback = object : CompanionDeviceManager.Callback() {
            // API < 33
            @Deprecated("Superseded by onAssociationPending on API 33+", ReplaceWith(""))
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                onLaunch(chooserLauncher)
            }

            // API 33+
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onAssociationPending(intentSender: IntentSender) {
                onLaunch(intentSender)
            }

            override fun onFailure(error: CharSequence?) {
                Timber.tag(TAG).w("CDM association failed: $error")
                onError(error ?: "association failed")
            }
        }

        // Deprecated on 33+ but functional across 26+, and drives both callback variants above.
        @Suppress("DEPRECATION")
        cdm.associate(request, callback, null)
    }

    /**
     * Forwards the result of the CDM chooser (delivered to the host's Activity) into the existing
     * bond/adoption flow. Returns true if a device was extracted and routed.
     */
    @SuppressLint("MissingPermission")
    fun handleAssociationResult(data: Intent?): Boolean {
        val device = extractDevice(data) ?: run {
            Timber.tag(TAG).w("No device in CDM association result")
            return false
        }
        Timber.tag(TAG).d("CDM associated ${device.address}; routing into bond flow")
        Scopes.getMainCoroutine().launch {
            DataManager.getClientConnection().bondToBleDevice(device, device.name)
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun extractDevice(data: Intent?): BluetoothDevice? {
        data ?: return null
        return when (val extra = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> extra
            is ScanResult -> extra.device
            else -> null
        }
    }

    /** MAC addresses of devices currently CDM-associated with this app. */
    @SuppressLint("MissingPermission")
    fun getAssociatedAddresses(): List<String> {
        val cdm = manager(DataManager.appContext) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
            } else {
                @Suppress("DEPRECATION")
                cdm.associations
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read CDM associations")
            emptyList()
        }
    }

    /**
     * Presence-based auto-connect hint. Requires the association to exist AND the host app to
     * declare a `CompanionDeviceService` (`BIND_COMPANION_DEVICE_SERVICE`) plus the
     * `REQUEST_COMPANION_RUN_IN_BACKGROUND` / `..._USE_DATA_IN_BACKGROUND` permissions in its
     * manifest — the OS then delivers onDeviceAppeared/onDeviceDisappeared to that service, where
     * the host should call [PairingRepository.adopt]. The SDK cannot host that service itself, so
     * this only starts the platform observation for an associated [address].
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun startObservingHint(address: String) {
        val cdm = manager(DataManager.appContext) ?: return
        try {
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(address)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "startObservingDevicePresence failed for $address")
        }
    }
}
