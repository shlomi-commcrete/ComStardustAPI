package com.commcrete.stardust.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.SystemClock
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.StardustLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Temporary diagnostics for two BLE investigations (2026-10-08):
 *  1. auto-reconnect after the radio dies is slow, and sometimes never succeeds;
 *  2. after an unpair, a scan does not find the radio until the radio is restarted.
 *
 * Everything goes to ONE logcat tag, so a reproduction is a single filter:
 * ```
 * adb logcat -s BleDiag
 * ```
 * `[VERDICT]` lines (Log.e) mark a conclusion the log itself proves.
 *
 * Privacy (CLAUDE.md §8): never prints a MAC, device name, App ID or packet. The radio's address is
 * kept in memory only, to recognise it in scan results and in the OS connected-device list. GATT
 * clients are printed as an identity hash (`g@1a2b3c`), which is enough to tell two clients apart.
 *
 * Same pattern as `usb/UsbDiag.kt`: android.util.Log, never Timber, and every platform call is
 * wrapped so diagnostics can never be what crashes the host. Delete this file and its call sites
 * once both root causes are fixed.
 */
internal object BleDiag {

    const val TAG = "BleDiag"

    /** Android blocks the 6th scan start inside a 30s window for one app UID — ATAK's, here. */
    private const val SCAN_START_LIMIT = 5
    private const val SCAN_START_WINDOW_MS = 30_000L

    private val t0 = SystemClock.elapsedRealtime()
    private var seq = 0

    @Synchronized
    private fun stamp(): String {
        seq += 1
        val ms = SystemClock.elapsedRealtime() - t0
        return "#" + seq.toString().padStart(4, '0') + " +" + ms.toString().padStart(7, ' ') + "ms"
    }

    fun log(where: String, msg: String) { StardustLog.d(TAG, "${stamp()} [$where] $msg") }
    fun warn(where: String, msg: String) { StardustLog.w(TAG, "${stamp()} [$where] $msg") }
    fun verdict(msg: String) { StardustLog.e(TAG, "${stamp()} [VERDICT] $msg") }

    /** Identity of a GATT client object; distinguishes clients without printing the device. */
    fun id(gatt: BluetoothGatt?): String =
        if (gatt == null) "null" else "g@" + Integer.toHexString(System.identityHashCode(gatt))

    // ── handshake state, mirrored here so BLE lines can print it ─────────────

    @Volatile
    var initState: String = "?"
        private set

    fun onInitState(state: Any) {
        val next = state.toString()
        if (next == initState) return
        log("init", "state $initState -> $next")
        initState = next
    }

    // ── the radio, kept in memory only ───────────────────────────────────────

    @Volatile
    private var radioAddress: String? = null

    fun rememberRadio(address: String?) {
        if (!address.isNullOrBlank()) radioAddress = address
    }

    /**
     * Whether Android still has an LE link to the radio from ANY client on the phone. After an
     * unpair this must become false — while it is true the radio is connected, so it is not
     * advertising and no scan can find it.
     */
    fun osLinkToRadio(where: String) {
        val address = radioAddress ?: return log(where, "osLink: radio unknown (nothing connected or unpaired yet)")
        val result = runCatching {
            val manager = DataManager.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            @Suppress("MissingPermission")
            val connected = manager.getConnectedDevices(BluetoothProfile.GATT)
            val device = manager.adapter?.getRemoteDevice(address)
            @Suppress("MissingPermission")
            val bond = device?.bondState
            Triple(connected.any { it.address.equals(address, ignoreCase = true) }, connected.size, bond)
        }
        result.onSuccess { (radioUp, total, bond) ->
            log(where, "osLink: radioLeConnected=$radioUp totalLeConnected=$total bond=${bondName(bond)}")
        }.onFailure {
            warn(where, "osLink: unreadable (${it.javaClass.simpleName})")
        }
    }

    /** Re-checks the OS link a few times after an unpair; a link that survives is the verdict. */
    fun watchOsLinkAfterUnpair() {
        Scopes.getDefaultCoroutine().launch {
            var elapsedMs = 0L
            for (checkAtMs in longArrayOf(0L, 2_000L, 5_000L, 10_000L)) {
                delay(checkAtMs - elapsedMs)
                elapsedMs = checkAtMs
                osLinkToRadio("unpair+${checkAtMs}ms")
            }
            val stillUp = runCatching {
                val manager = DataManager.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                @Suppress("MissingPermission")
                manager.getConnectedDevices(BluetoothProfile.GATT)
                    .any { it.address.equals(radioAddress, ignoreCase = true) }
            }.getOrNull()
            if (stillUp == true) {
                verdict("radio is STILL LE-connected to this phone 10s after unpair — it will not " +
                    "advertise, so scans cannot find it. Run `adb shell dumpsys bluetooth_manager` now " +
                    "and check 'ACL holders' for which client holds it.")
            }
        }
    }

    private fun bondName(state: Int?): String = when (state) {
        BluetoothDevice.BOND_NONE -> "NONE"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_BONDED -> "BONDED"
        null -> "?"
        else -> state.toString()
    }

    // ── scanning ─────────────────────────────────────────────────────────────

    private val scanStarts = ArrayDeque<Long>()
    private val seenAddresses = HashSet<String>()
    private var rawResults = 0
    private var unnamed = 0
    private var matched = 0
    private var radioSeen = false
    private var scanStartedAtMs = 0L

    @Synchronized
    fun scanStart(alreadyRunning: Boolean) {
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > SCAN_START_WINDOW_MS) scanStarts.removeFirst()
        scanStarts.addLast(now)
        seenAddresses.clear()
        rawResults = 0
        unnamed = 0
        matched = 0
        radioSeen = false
        scanStartedAtMs = now
        log("scan", "start: startsInLast30s=${scanStarts.size} previousScanStillRunning=$alreadyRunning")
        if (alreadyRunning) {
            warn("scan", "start while a scan is still running with the same callback — the platform " +
                "answers SCAN_FAILED_ALREADY_STARTED")
        }
        if (scanStarts.size > SCAN_START_LIMIT) {
            verdict("${scanStarts.size} scan starts from this SDK in 30s — Android throttles the app " +
                "UID (shared with ATAK and every plugin) and silently delivers NO results. Look for " +
                "'scanning too frequently' in the full logcat.")
        }
        osLinkToRadio("scan-start")
    }

    /** Counting only — never logs per result. */
    @Synchronized
    fun scanSeen(result: ScanResult, matchedAsRadio: Boolean) {
        rawResults += 1
        val address = runCatching { result.device.address }.getOrNull() ?: return
        val firstSighting = seenAddresses.add(address)
        val advName = result.scanRecord?.deviceName
        @Suppress("MissingPermission")
        val cachedName = runCatching { result.device.name }.getOrNull()
        if (firstSighting) {
            if (advName == null && cachedName == null) unnamed += 1
            if (matchedAsRadio) matched += 1
        }
        if (!radioSeen && address.equals(radioAddress, ignoreCase = true)) {
            radioSeen = true
            log("scan", "THE RADIO is advertising: nameInAdvert=${advName != null} cachedName=${cachedName != null} " +
                "matchedByNameFilter=$matchedAsRadio rssi=${result.rssi} " +
                "after=${SystemClock.elapsedRealtime() - scanStartedAtMs}ms")
            if (!matchedAsRadio) {
                verdict("the radio IS advertising but the name filter rejected it (no matching name in " +
                    "the advert or the cache) — it is dropped before the host ever sees it.")
            }
        }
    }

    @Synchronized
    fun scanFailed(code: Int) = warn("scan", "onScanFailed code=$code")

    @Synchronized
    fun scanStop(wasRunning: Boolean) {
        if (!wasRunning) return
        val ranMs = SystemClock.elapsedRealtime() - scanStartedAtMs
        log("scan", "stop after ${ranMs}ms: callbacks=$rawResults uniqueDevices=${seenAddresses.size} " +
            "unnamed=$unnamed matchedAsRadio=$matched radioSeen=$radioSeen radioKnown=${radioAddress != null}")
        if (rawResults == 0) {
            verdict("scan ran ${ranMs}ms and the platform delivered ZERO results of any device — the scan " +
                "was throttled or never really started (not a radio problem).")
        } else if (radioAddress != null && !radioSeen) {
            verdict("other devices were seen but the radio never advertised during this scan — it is " +
                "connected somewhere (see osLink lines) or its firmware is not advertising.")
        }
    }
}
