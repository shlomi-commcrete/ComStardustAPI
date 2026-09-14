package com.commcrete.stardust.enums

/**
 * Why a BLE scan could not start, or stopped producing results.
 *
 * Delivered through [com.commcrete.stardust.StardustAPICallbacks.onScanFailure] so the host app can
 * tell the user what to fix.  Without this the whole class of failures below is indistinguishable
 * from "the radio is not nearby" - which is exactly how the Android 10 scan regression presented.
 *
 * The first group is detected before the scan starts; the rest are the platform's own
 * `ScanCallback.SCAN_FAILED_*` codes, mapped so callers never see a raw int.
 */
enum class ScanFailure {

    /**
     * The device-wide Location toggle is off.  Turning off location services turns off Bluetooth
     * scanning on every API level, the sole exception being apps that declare
     * `usesPermissionFlags="neverForLocation"` on BLUETOOTH_SCAN - which this library deliberately
     * does not (see the comment in AndroidManifest.xml).  If that flag is ever adopted, this check
     * becomes API 30-and-below only.
     *
     * Host should send the user to `Settings.ACTION_LOCATION_SOURCE_SETTINGS`.
     */
    LOCATION_SERVICES_DISABLED,

    /**
     * A permission required to scan on this API level has not been granted: BLUETOOTH_SCAN plus the
     * location pair from API 31, or ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION below it.  See
     * [com.commcrete.stardust.util.PermissionTracking.hasBlePermissions].
     */
    MISSING_PERMISSIONS,

    /** The Bluetooth adapter exists but is turned off. */
    BLUETOOTH_DISABLED,

    /** No Bluetooth adapter, or the LE scanner is unavailable on this device. */
    BLUETOOTH_UNAVAILABLE,

    /** `SCAN_FAILED_ALREADY_STARTED` - a scan with the same callback is already running. */
    ALREADY_STARTED,

    /** `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` - the app could not be registered. */
    APPLICATION_REGISTRATION_FAILED,

    /** `SCAN_FAILED_INTERNAL_ERROR` - the Bluetooth stack failed internally. */
    INTERNAL_ERROR,

    /** `SCAN_FAILED_FEATURE_UNSUPPORTED` - this device cannot do what the scan settings ask for. */
    FEATURE_UNSUPPORTED,

    /** `SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES` - no hardware slot free for another scan. */
    OUT_OF_HARDWARE_RESOURCES,

    /**
     * `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` - the app tripped the platform's scan-start throttle
     * (5 starts per 30 seconds since Android 7).  Back off rather than retrying immediately.
     */
    SCANNING_TOO_FREQUENTLY,

    /** A `SCAN_FAILED_*` code this library does not recognise. */
    UNKNOWN,
}
