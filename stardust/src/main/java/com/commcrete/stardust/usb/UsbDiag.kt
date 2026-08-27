package com.commcrete.stardust.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.commcrete.stardust.util.DataManager
import java.util.Locale

/**
 * Temporary, self-contained diagnostics for the "USB never connects" investigation.
 *
 * Everything goes to ONE logcat tag, so a live session is a single filter:
 * ```
 * adb logcat -s UsbDiag
 * ```
 *
 * Deliberately uses [android.util.Log] rather than Timber: `Timber.plant()` is never called
 * anywhere in this library, so every `Timber.tag(...)` line in the USB stack
 * (`SerialInOutputManager`, `UsbPermission`, …) prints NOTHING unless the host app happened to
 * plant a tree. That is itself a likely reason the existing USB logging looks "missing".
 *
 * Every accessor here is defensive — `DataManager.appContext` is `lateinit`, so touching it before
 * `DataManager.init(...)` throws. Diagnostics must never be the thing that crashes the app.
 *
 * Delete this file and its call sites once the root cause is fixed.
 */
internal object UsbDiag {

    const val TAG = "UsbDiag"

    private val t0 = SystemClock.elapsedRealtime()
    private var seq = 0

    /** Sequence number + ms since first diag call — makes ordering and dead gaps obvious. */
    @Synchronized
    private fun stamp(): String {
        seq += 1
        val ms = SystemClock.elapsedRealtime() - t0
        return "#" + seq.toString().padStart(3, '0') + " +" + ms.toString().padStart(6, ' ') + "ms"
    }

    // Explicit Unit returns: Log.* returns Int, which would make `return warn(...)` a type error
    // in the Unit-returning helpers below.
    fun log(where: String, msg: String) { Log.d(TAG, "${stamp()} [$where] $msg") }
    fun warn(where: String, msg: String) { Log.w(TAG, "${stamp()} [$where] $msg") }
    fun error(where: String, msg: String, e: Throwable? = null) { Log.e(TAG, "${stamp()} [$where] $msg", e) }

    /** Loud marker for a conclusion the log itself can prove. */
    fun verdict(msg: String) { Log.e(TAG, "${stamp()} [VERDICT] $msg") }

    private fun contextOrNull(): Context? =
        runCatching { DataManager.appContext }.getOrNull()

    fun usbManagerOrNull(): UsbManager? = runCatching {
        BittelUsbManager2.usbManager
            ?: contextOrNull()?.getSystemService(Context.USB_SERVICE) as? UsbManager
    }.getOrNull()

    // ── environment ──────────────────────────────────────────────────────────

    /**
     * One-shot environment dump. The host's `targetSdkVersion` matters: on Android 14+ a
     * host targeting 34+ does not receive an IMPLICIT broadcast on a RECEIVER_NOT_EXPORTED
     * runtime receiver, which silently kills the permission callback.
     */
    fun env(where: String) {
        val ctx = contextOrNull()
        if (ctx == null) {
            warn(where, "env: DataManager.appContext NOT initialized yet — DataManager.init() has not run")
            return
        }
        val target = runCatching { ctx.applicationInfo.targetSdkVersion }.getOrNull()
        val hasUsbHost = runCatching {
            ctx.packageManager.hasSystemFeature("android.hardware.usb.host")
        }.getOrNull()
        val fieldSet = runCatching { BittelUsbManager2.usbManager != null }.getOrNull()
        log(
            where,
            "env: device=${Build.MANUFACTURER}/${Build.MODEL} sdkInt=${Build.VERSION.SDK_INT} " +
                "hostPackage=${ctx.packageName} hostTargetSdk=$target " +
                "feature(usb.host)=$hasUsbHost usbManagerField=${if (fieldSet == true) "SET" else "NULL"} " +
                "serviceResolvable=${usbManagerOrNull() != null}"
        )
        if (Build.VERSION.SDK_INT >= 34 && (target ?: 0) >= 34) {
            warn(
                where,
                "env: Android 14+ AND host targetSdk>=34 — an IMPLICIT permission broadcast will " +
                    "NOT reach a RECEIVER_NOT_EXPORTED receiver. Check the 'pendingIntent:' line " +
                    "below for 'implicit=true'."
            )
        }
    }

    // ── devices ──────────────────────────────────────────────────────────────

    /**
     * Full device identity. The product name is printed with explicit delimiters and its length,
     * because the permission filter compares it CASE-SENSITIVELY against "FT231X USB UART" while
     * the role matchers lowercase it — a casing or whitespace difference silently drops the device.
     */
    fun describe(device: UsbDevice?): String {
        device ?: return "device=null"
        val raw = runCatching { device.productName }.getOrNull()
        val manufacturer = runCatching { device.manufacturerName }.getOrNull()
        val nameInfo = if (raw == null) {
            "productName=NULL (no permission yet? name is often null until granted)"
        } else {
            val lower = raw.lowercase(Locale.ROOT)
            "productName=<<$raw>> len=${raw.length} lower=<<$lower>>" +
                (if (raw != raw.trim()) " HAS_SURROUNDING_WHITESPACE" else "") +
                (if (raw.any { it.code > 127 }) " HAS_NON_ASCII" else "")
        }
        return "deviceId=${device.deviceId} vid=0x%04X pid=0x%04X ".format(device.vendorId, device.productId) +
            "deviceName=${device.deviceName} manufacturer=$manufacturer interfaces=${device.interfaceCount} " +
            nameInfo
    }

    /** Why this device will or will not be routed anywhere. Prints the whole decision. */
    fun match(where: String, device: UsbDevice?) {
        device ?: return warn(where, "match: device is null")
        val raw = runCatching { device.productName }.getOrNull()
        val lower = raw?.lowercase(Locale.ROOT)

        // Mirrors UsbDevicePermissionHandler.requestNextPermission (:48) exactly, term by term.
        val filterRawFt = raw?.contains("FT231X USB UART") == true
        val filterStardust = lower?.contains("stardust") == true
        val filterJboxHyphen = lower?.contains("j-box") == true
        val filterJbox = lower?.contains("jbox") == true
        val passesFilter = filterRawFt || filterStardust || filterJboxHyphen || filterJbox

        // Mirrors BittelUsbManager2.isJboxAudioDevice (:71) / isStardustDataDevice (:78).
        val audio = lower?.let {
            it.contains("ft231x usb uart ptt") || it.contains("j-box") || it.contains("jbox")
        } == true
        val data = lower?.let {
            it.contains("ft231x usb uart ptt") || it.contains("stardust")
        } == true

        log(
            where,
            "match: permissionFilter=$passesFilter " +
                "[rawFT231X(case-SENSITIVE)=$filterRawFt stardust=$filterStardust j-box=$filterJboxHyphen jbox=$filterJbox] " +
                "roleAudio=$audio roleData=$data → ${
                    when {
                        !passesFilter -> "DROPPED before requesting permission"
                        audio -> "would connect as AUDIO (checked first)"
                        data -> "would connect as DATA"
                        else -> "NO MATCH after grant — nothing will connect"
                    }
                }"
        )
        if (!passesFilter && (audio || data)) {
            verdict(
                "Device is a valid radio by the role matchers but is DROPPED by the case-sensitive " +
                    "permission filter — this alone prevents USB from ever connecting. " +
                    "See CONNECTION_FIXES_AND_UPGRADES.md fix #2."
            )
        }
        if (passesFilter && !audio && !data) {
            verdict("Device passes the permission filter but matches NO role — permission will be granted and then ignored.")
        }
    }

    /** Everything currently attached, with the full decision for each. */
    fun dumpAttachedDevices(where: String) {
        val manager = usbManagerOrNull() ?: return warn(where, "dumpAttachedDevices: UsbManager unavailable")
        val devices = runCatching { manager.deviceList }.getOrNull()
        if (devices == null) {
            warn(where, "dumpAttachedDevices: deviceList threw")
            return
        }
        log(where, "dumpAttachedDevices: count=${devices.size}")
        devices.values.forEachIndexed { i, d ->
            log(where, "  [$i] ${describe(d)} hasPermission=${hasPermission(d)}")
            match("$where[$i]", d)
        }
        if (devices.isEmpty()) {
            warn(where, "dumpAttachedDevices: NO USB devices visible to the app at all — cable/OTG/host-mode issue, not a code issue")
        }
    }

    fun hasPermission(device: UsbDevice?): Boolean? {
        device ?: return null
        return runCatching { usbManagerOrNull()?.hasPermission(device) }.getOrNull()
    }

    // ── permission plumbing ──────────────────────────────────────────────────

    /**
     * Describes the PendingIntent we are about to hand to `UsbManager.requestPermission`.
     *
     * `implicit=true` means the Intent has neither a package nor a component → not delivered to a
     * RECEIVER_NOT_EXPORTED receiver on Android 14+.
     * `immutable=true` means `PendingIntent.send()`'s extras are DISCARDED → the framework's
     * EXTRA_PERMISSION_GRANTED / EXTRA_DEVICE never reach us, so every grant parses as a denial.
     */
    fun describePendingIntent(where: String, intent: Intent, pi: PendingIntent, flags: Int) {
        val implicit = intent.`package` == null && intent.component == null
        val immutableByFlag = flags and PendingIntent.FLAG_IMMUTABLE != 0
        val mutableByFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            flags and PendingIntent.FLAG_MUTABLE != 0
        val immutableReported = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) pi.isImmutable else null
        }.getOrNull()

        log(
            where,
            "pendingIntent: action=${intent.action} package=${intent.`package`} component=${intent.component} " +
                "implicit=$implicit flags=0x%08X ".format(flags) +
                "FLAG_IMMUTABLE=$immutableByFlag FLAG_MUTABLE=$mutableByFlag isImmutable()=$immutableReported"
        )
        if (immutableByFlag || immutableReported == true) {
            verdict(
                "PendingIntent is IMMUTABLE. UsbManager fills EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED " +
                    "at send() time, and an immutable PendingIntent DISCARDS send-time extras — so a " +
                    "granted permission will arrive looking like a denial. Needs FLAG_MUTABLE. " +
                    "See CONNECTION_FIXES_AND_UPGRADES.md fix #1 defect A."
            )
        }
        if (implicit) {
            verdict(
                "PendingIntent Intent is IMPLICIT (no package/component). On Android 14+ this is not " +
                    "delivered to the RECEIVER_NOT_EXPORTED receiver — onReceive never fires at all. " +
                    "Needs intent.setPackage(packageName). See fix #1 defect B."
            )
        }
    }

    /** Full dump of a received broadcast: which extras actually survived transport. */
    fun describeIntent(where: String, intent: Intent?) {
        if (intent == null) return warn(where, "receivedIntent: NULL")
        val extras = runCatching { intent.extras }.getOrNull()
        val keys = runCatching { extras?.keySet()?.joinToString() }.getOrNull()
        log(
            where,
            "receivedIntent: action=${intent.action} package=${intent.`package`} " +
                "extras=${if (extras == null) "NULL" else "{$keys}"}"
        )
        log(
            where,
            "receivedIntent: hasEXTRA_DEVICE=${intent.hasExtra(UsbManager.EXTRA_DEVICE)} " +
                "hasEXTRA_PERMISSION_GRANTED=${intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED)}"
        )
    }

    /**
     * THE decisive check. Cross-references the `granted` extra against the authoritative
     * `UsbManager.hasPermission(device)`:
     *
     * - granted=false but hasPermission=true → the extras were STRIPPED (immutable PendingIntent).
     *   The user DID allow; the code just cannot see it.
     * - granted=false and hasPermission=false → a real denial by the user.
     */
    fun permissionResult(where: String, intent: Intent?, grantedExtra: Boolean, device: UsbDevice?) {
        val extraPresent = intent?.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED) == true
        val deviceFromIntent = runCatching {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }.getOrNull()
        val actual = hasPermission(device ?: deviceFromIntent)

        log(
            where,
            "permissionResult: grantedExtra=$grantedExtra extraPresent=$extraPresent " +
                "deviceFromIntent=${if (deviceFromIntent == null) "NULL" else "id=${deviceFromIntent.deviceId}"} " +
                "trackedDevice=${if (device == null) "NULL" else "id=${device.deviceId}"} " +
                "UsbManager.hasPermission=$actual"
        )
        when {
            !extraPresent -> verdict(
                "EXTRA_PERMISSION_GRANTED was NOT present in the broadcast — the framework's send-time " +
                    "extras were discarded (immutable PendingIntent). Confirms fix #1 defect A."
            )
            !grantedExtra && actual == true -> verdict(
                "granted=false BUT UsbManager.hasPermission=true — permission WAS granted and the code " +
                    "is misreading it. Confirms fix #1 defect A."
            )
            !grantedExtra && actual == false -> log(where, "permissionResult: genuine user denial")
            grantedExtra -> log(where, "permissionResult: grant read correctly — flow should continue to connectToUnknownDevice")
        }
    }

    // ── link state ───────────────────────────────────────────────────────────

    fun linkState(where: String) = log(
        where,
        "linkState: " + runCatching {
            "isUSBConnected=${com.commcrete.stardust.ble.BleManager.isUSBConnected} " +
                "isBleConnected=${com.commcrete.stardust.ble.BleManager.isBleConnected} " +
                "isPaired=${com.commcrete.stardust.ble.BleManager.isPaired.value} " +
                "isJboxAudio=${BittelUsbManager2.isJboxAudioConnected()}"
        }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
    )
}
