package com.commcrete.stardust.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context.BLUETOOTH_SERVICE
import android.os.Build
import com.commcrete.stardust.AdoptableDevice
import com.commcrete.stardust.stardust.StardustInitConnectionHandler
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Single source of truth for "is a Stardust/Bittel device paired with this app".
 *
 * The app used to track paired-state in three independent places — [BleManager.isPaired],
 * the persisted address in SharedPreferences, and the Android OS bond registry — which could
 * silently diverge. The most damaging case: the app record is cleared (e.g. an unpair whose
 * OS `removeBond()` reflection call failed) while Android stays bonded, so the device is
 * "bonded in the phone but not shown as paired in the app" and cannot be re-paired.
 *
 * This repository makes paired-state a DERIVED value: paired == the saved address is still
 * present in the OS bonded set (filtered to Stardust devices). It also exposes the
 * pre-paired-device adoption flow (devices the user bonded from phone Settings or another app).
 */
object PairingRepository {

    private val TAG = PairingRepository::class.java.simpleName

    /** Name/alias tokens that identify one of our radios. Unified across scan + bond matching. */
    private val STARDUST_TOKENS = listOf("bittle", "bittel", "stardust")

    /** True if [device]'s name or alias identifies it as one of our radios. */
    @SuppressLint("MissingPermission")
    fun isStardust(device: BluetoothDevice): Boolean {
        val candidates = buildList {
            device.name?.let { add(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias?.let { add(it) }
        }
        return candidates.any { value ->
            val lower = value.lowercase(Locale.getDefault())
            STARDUST_TOKENS.any { lower.contains(it) }
        }
    }

    /**
     * Currently-bonded Stardust devices, or `null` when the bond set can't be read reliably
     * (Bluetooth off, adapter missing, or CONNECT permission not granted). Callers MUST treat
     * `null` as "unknown" — never as "none" — otherwise reconciliation would wrongly wipe the
     * saved device whenever Bluetooth happens to be off.
     */
    @SuppressLint("MissingPermission")
    private fun bondedStardustDevices(): List<BluetoothDevice>? {
        if (!BlePermissions.hasConnectPermission(DataManager.appContext)) return null
        val manager = DataManager.appContext.getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return try {
            adapter.bondedDevices.orEmpty().filter { isStardust(it) }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Missing BLUETOOTH_CONNECT; cannot read bonded devices")
            null
        }
    }

    private fun savedAddress(): String? =
        SharedPreferencesUtil.getBittelDevice()
            ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }

    /**
     * Recomputes paired-state from the OS bond registry and keeps [BleManager.isPaired] in sync.
     *
     * - saved present AND still bonded  -> paired (happy path)
     * - saved present but NOT bonded    -> the OS bond was removed externally; clear the stale
     *                                      app record so the two agree
     * - no saved device                 -> not paired
     *
     * No-op when the bond set is unreadable (Bluetooth off / no permission) so a transient
     * off-state can never erase a valid pairing.
     */
    @SuppressLint("MissingPermission")
    fun reconcile() {
        val bonded = bondedStardustDevices() ?: run {
            Timber.tag(TAG).d("reconcile skipped: bonded set unreadable (BT off / no permission)")
            return
        }
        val saved = savedAddress()
        when {
            saved == null -> setPaired(false)
            bonded.any { it.address.equals(saved, ignoreCase = true) } -> setPaired(true)
            else -> {
                Timber.tag(TAG).w("Saved device $saved is no longer OS-bonded; clearing stale pairing")
                clearSavedIdentity()
                setPaired(false)
            }
        }
    }

    /** The saved paired address if it is still OS-bonded, else `null`. */
    @SuppressLint("MissingPermission")
    fun currentPairedAddress(): String? {
        val saved = savedAddress() ?: return null
        val bonded = bondedStardustDevices() ?: return saved // unknown -> trust the record
        return saved.takeIf { addr -> bonded.any { it.address.equals(addr, ignoreCase = true) } }
    }

    /**
     * Stardust devices already bonded to the phone that are NOT the app's current device —
     * i.e. paired from phone Settings or another app, and therefore adoptable. Empty when the
     * bond set is unreadable.
     */
    @SuppressLint("MissingPermission")
    fun discoverAdoptable(): List<BluetoothDevice> {
        val bonded = bondedStardustDevices() ?: return emptyList()
        val saved = savedAddress()
        return bonded.filter { saved == null || !it.address.equals(saved, ignoreCase = true) }
    }

    /** [discoverAdoptable] mapped to the public, framework-free API model. */
    @SuppressLint("MissingPermission")
    fun getAdoptableDevices(): List<AdoptableDevice> = discoverAdoptable().map {
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.alias else null)
            ?: it.name ?: it.address
        AdoptableDevice(address = it.address, name = display)
    }

    /**
     * Adopts an already-bonded device: persists its identity, marks it paired, and connects +
     * runs the init handshake to fetch its configuration. No new OS bond is created (the device
     * is already bonded), so this works even for devices paired outside the app.
     */
    @SuppressLint("MissingPermission")
    fun adopt(address: String): Boolean {
        val device = bondedStardustDevices()?.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: run {
                Timber.tag(TAG).w("adopt($address) failed: not a bonded Stardust device")
                return false
            }
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null)
            ?: device.name
        SharedPreferencesUtil.setBittelDevice(device.address)
        display?.let { SharedPreferencesUtil.setBittelDeviceName(it) }
        setPaired(true)
        StardustInitConnectionHandler.updateConnectionState(StardustInitConnectionHandler.State.SEARCHING)
        DataManager.getClientConnection().bondToBleDeviceStartup(device)
        Timber.tag(TAG).d("Adopted pre-paired device ${device.address}")
        return true
    }

    private fun clearSavedIdentity() {
        SharedPreferencesUtil.removeBittelDevice()
        SharedPreferencesUtil.removeBittelDeviceName()
    }

    private fun setPaired(paired: Boolean) {
        Scopes.getMainCoroutine().launch {
            if (BleManager.isPaired.value != paired) BleManager.isPaired.value = paired
        }
    }
}
