package com.commcrete.stardust.transport

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import com.commcrete.stardust.ble.BlePermissions
import com.commcrete.stardust.ble.PairingRepository
import com.commcrete.stardust.enums.ScanFailure
import com.commcrete.stardust.util.DataManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * One radio the user could pair with, whether it was just seen advertising or is already bonded to
 * the phone.
 *
 * [alreadyBonded] is the only difference the pairing UI has to care about — a bonded radio is
 * frequently NOT advertising (it may be connected elsewhere), so it can only ever come from the OS
 * bond registry, never from the scan. Both kinds connect through the same
 * [com.commcrete.stardust.StardustAPI.connect].
 */
data class DiscoveredDevice(
    val address: String,
    val name: String,
    /** Signal strength when it came from a scan; null for a bonded radio that isn't advertising. */
    val rssi: Int?,
    val alreadyBonded: Boolean,
)

/**
 * Everything a pairing screen needs, on one stream: the device list is present in every state, so
 * the UI renders `state.devices` unconditionally and only varies the spinner / error banner.
 *
 * This is why scan failures live here rather than on a separate callback — "nothing found" and
 * "could not scan" arrive on the same stream and cannot be confused for one another.
 */
sealed interface ScanState {

    /** Devices known so far. Already-bonded radios are present from the first emission. */
    val devices: List<DiscoveredDevice>

    /** A scan is running; [devices] grows as results arrive. */
    data class Scanning(override val devices: List<DiscoveredDevice>) : ScanState

    /**
     * No scan is running: either it was never started, it was stopped, or the SDK's scan window
     * elapsed. [devices] is the final list — a radio powered on after this is not reported until
     * the host scans again.
     */
    data class Stopped(override val devices: List<DiscoveredDevice>) : ScanState

    /** The scan could not run. [devices] still carries whatever was already known (bonded radios). */
    data class Failed(val reason: Blocker, override val devices: List<DiscoveredDevice>) : ScanState
}

/**
 * Owns the [ScanState] stream. Wraps the existing `BleScanner` rather than replacing it: the scanner
 * keeps doing the platform work and pushes results, failures and stop events in here, where they are
 * merged with the OS bond registry into the single list the host renders.
 */
object DeviceDiscovery {

    private val TAG = DeviceDiscovery::class.java.simpleName

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Stopped(emptyList()))
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Address → last scan result, so `connect(address)` can reuse the advertised name. */
    private val scanResults = LinkedHashMap<String, ScanResult>()

    /** Address → device, insertion-ordered so the UI list doesn't jump around. */
    private val found = LinkedHashMap<String, DiscoveredDevice>()

    /**
     * Starts (or restarts) discovery and returns the stream. Bonded radios are published
     * immediately, before the first advertisement arrives, so a pre-paired device is offered even
     * when it isn't advertising at all.
     */
    fun start(): StateFlow<ScanState> {
        synchronized(this) {
            scanResults.clear()
            found.clear()
            seedBonded()
        }
        _scanState.value = ScanState.Scanning(snapshot())
        // Preconditions are checked inside startScan(); a failure arrives through onScanFailure()
        // before this returns, so don't overwrite the state afterwards.
        DataManager.getBleScanner().startScan()
        return scanState
    }

    /** Stops discovery. Idempotent. */
    fun stop() {
        DataManager.getBleScanner().stopScan()
    }

    /** The scan result an address was seen in, if it came from a scan rather than the bond registry. */
    internal fun scanResultFor(address: String): ScanResult? = synchronized(this) {
        scanResults[address]
    }

    // ── Sinks used by BleScanner. Public for Java interop; not part of the host API. ──

    /** Called by `BleScanner` whenever its result list changes. */
    @SuppressLint("MissingPermission")
    fun onScanResults(results: List<ScanResult>) {
        val bonded = bondedAddresses()
        synchronized(this) {
            for (result in results) {
                val device = result.device ?: continue
                val address = device.address ?: continue
                scanResults[address] = result
                found[address] = DiscoveredDevice(
                    address = address,
                    name = displayName(result) ?: address,
                    rssi = result.rssi,
                    alreadyBonded = bonded.contains(address.uppercase()),
                )
            }
        }
        // A result arriving after the window closed still updates the list, but must not claim a
        // scan is running.
        val devices = snapshot()
        _scanState.value =
            if (_scanState.value is ScanState.Scanning) ScanState.Scanning(devices)
            else ScanState.Stopped(devices)
    }

    /** Called by `BleScanner` when the platform or a precondition rejects the scan. */
    fun onScanFailure(failure: ScanFailure) {
        val reason = refine(failure)
        Timber.tag(TAG).w("scan failed: $failure -> $reason")
        _scanState.value = ScanState.Failed(reason, snapshot())
    }

    /** Called by `BleScanner` when the scan stops (host request or the scan window elapsing). */
    fun onScanStopped() {
        // Don't mask a failure: Failed already means "not scanning", and the reason is what the host
        // has to act on.
        if (_scanState.value is ScanState.Failed) return
        _scanState.value = ScanState.Stopped(snapshot())
    }

    // ── Internals ──

    /**
     * [ScanFailure.MISSING_PERMISSIONS] says a permission is absent but not which one — the scanner
     * gates on the whole set. Resolve it here so the host can prompt for the right thing.
     */
    private fun refine(failure: ScanFailure): Blocker {
        if (failure != ScanFailure.MISSING_PERMISSIONS) return failure.toBlocker()
        val ctx = DataManager.appContext
        return when {
            !BlePermissions.hasScanPermission(ctx) -> Blocker.SCAN_PERMISSION_MISSING
            !BlePermissions.hasConnectPermission(ctx) -> Blocker.CONNECT_PERMISSION_MISSING
            else -> Blocker.LOCATION_PERMISSION_MISSING
        }
    }

    private fun seedBonded() {
        for (device in PairingRepository.getAdoptableDevices()) {
            found[device.address] = DiscoveredDevice(
                address = device.address,
                name = device.name,
                rssi = null,
                alreadyBonded = true,
            )
        }
    }

    private fun bondedAddresses(): Set<String> =
        PairingRepository.getAdoptableDevices().mapTo(mutableSetOf()) { it.address.uppercase() }

    private fun snapshot(): List<DiscoveredDevice> = synchronized(this) { found.values.toList() }

    /**
     * Advertised name first: it needs only SCAN permission, while the cached device name needs
     * CONNECT from API 31 and is null without it.
     */
    @SuppressLint("MissingPermission")
    private fun displayName(result: ScanResult): String? =
        result.scanRecord?.deviceName ?: runCatching { result.device?.name }.getOrNull()
}