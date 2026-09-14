package com.commcrete.stardust.ble;


import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.commcrete.stardust.StardustAPICallbacks;
import com.commcrete.stardust.enums.ScanFailure;
import com.commcrete.stardust.util.DataManager;
import com.commcrete.stardust.util.PermissionTracking;


import java.util.ArrayList;
import java.util.List;

public class BleScanner {
    private static final String TAG = "BleScanner";

    private BluetoothLeScanner bluetoothLeScanner = null;
    private BluetoothAdapter bluetoothAdapter;
    private Context context;

    private List<ScanResult> scanResults = new ArrayList<>();
    public MutableLiveData<List<ScanResult>> scanResultsLiveData = new MutableLiveData<>();

    private ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            notifyResults();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            // Add your logic here
//            Log.d("scanResults", "onBatchScanResults");

            if (!results.isEmpty()) {
                for (ScanResult result : results) {
                    if ((result.getDevice().getName() != null && isStartWithBittle(result.getDevice().getName())) ||
                            (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null && isStartWithBittle(result.getScanRecord().getDeviceName()))) {
                        if (!isContainScanResult(result)) {
                            scanResults.add(result);
                        }
                    }
                }
            }
            notifyResults();
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            ScanFailure failure = mapScanFailure(errorCode);
            Log.e(TAG, "scan failed, code " + errorCode + " -> " + failure);
            reportFailure(failure);
        }
    };

    /** Maps the platform's {@code ScanCallback.SCAN_FAILED_*} codes onto {@link ScanFailure}. */
    private static ScanFailure mapScanFailure(int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return ScanFailure.ALREADY_STARTED;
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return ScanFailure.APPLICATION_REGISTRATION_FAILED;
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return ScanFailure.INTERNAL_ERROR;
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return ScanFailure.FEATURE_UNSUPPORTED;
            case ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES:
                return ScanFailure.OUT_OF_HARDWARE_RESOURCES;
            case ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY:
                return ScanFailure.SCANNING_TOO_FREQUENTLY;
            default:
                return ScanFailure.UNKNOWN;
        }
    }

    /**
     * Hands the reason to the host app.  Always on the main thread: {@link ScanCallback} is
     * delivered on a binder thread, and hosts route these straight into UI.
     */
    private void reportFailure(ScanFailure failure) {
        new Handler(Looper.getMainLooper()).post(() -> {
            StardustAPICallbacks callbacks = DataManager.INSTANCE.getCallbacks();
            if (callbacks != null) {
                callbacks.onScanFailure(failure);
            }
        });
    }

    public BleScanner(Context context) {
        this.context = context;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    private void notifyResults() {
        scanResultsLiveData.postValue(getScanResults());
    }

    public MutableLiveData<List<ScanResult>> getScanResultsLiveData() {
        return scanResultsLiveData;
    }

    /**
     * Delegates to {@link PermissionTracking#hasBlePermissions} so the API-level split lives in one
     * place.  This used to test BLUETOOTH_SCAN unconditionally, which silently disabled scanning on
     * API 26-30: the platform does not define that permission before API 31, so checkSelfPermission
     * reports it denied there and startScan() bailed out before ever reaching the scanner.
     *
     * The S+ branch also requires BLUETOOTH_CONNECT and the location pair, and both are genuinely
     * needed here - onBatchScanResults() reads result.getDevice().getName(), which needs CONNECT
     * from API 31, and our BLUETOOTH_SCAN is not flagged neverForLocation, so results are only
     * delivered when a location permission is held.
     */
    private boolean checkBlePermissions() {
        return PermissionTracking.INSTANCE.hasBlePermissions(context);
    }

    /**
     * Everything that has to be true before the platform will deliver a single scan result.  Each
     * branch reports a distinct {@link ScanFailure} - previously all of these returned a bare
     * {@code false} (or threw), which the host could not tell apart from "no device nearby".
     *
     * @return true if scanning can proceed.
     */
    private boolean checkScanPreconditions() {
        if (!checkBlePermissions()) {
            Log.w(TAG, "cannot scan: required permissions not granted");
            reportFailure(ScanFailure.MISSING_PERMISSIONS);
            return false;
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "cannot scan: no Bluetooth adapter");
            reportFailure(ScanFailure.BLUETOOTH_UNAVAILABLE);
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "cannot scan: Bluetooth is off");
            reportFailure(ScanFailure.BLUETOOTH_DISABLED);
            return false;
        }
        if (!isLocationServicesEnabled()) {
            Log.w(TAG, "cannot scan: location services are off");
            reportFailure(ScanFailure.LOCATION_SERVICES_DISABLED);
            return false;
        }
        return true;
    }

    /**
     * Whether the device-wide Location toggle is on.  Switching location off switches Bluetooth
     * scanning off on every API level; the one exception is apps declaring
     * {@code usesPermissionFlags="neverForLocation"} on BLUETOOTH_SCAN, which this library does not
     * (see AndroidManifest.xml).  Should that change, this check can be limited to API <= 30.
     */
    private boolean isLocationServicesEnabled() {
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        }
        // minSdk is 26, so API 26-27 still needs the pre-P provider check.
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @SuppressLint("MissingPermission")
    public boolean startScan() {
//        Log.d("scanResults", "startScan");
        if (!checkScanPreconditions()) {
            return false;
        }

        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder()
                .setReportDelay(1000)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanSettingsBuilder.setLegacy(true);
        }

        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(new ScanFilter.Builder().build());

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            // Adapter can report enabled while the LE scanner is still unavailable, e.g. mid
            // adapter-restart.  Reaching startScan() with this null used to be an NPE.
            Log.w(TAG, "cannot scan: LE scanner unavailable");
            reportFailure(ScanFailure.BLUETOOTH_UNAVAILABLE);
            return false;
        }
        bluetoothLeScanner.startScan(scanFilters, scanSettingsBuilder.build(), scanCallback);
//        Log.d("scanResults", "scanning");
        return true;
    }

    /**
     * Deliberately not gated on {@link #checkBlePermissions()}: a permission revoked mid-scan would
     * otherwise turn stopping into a no-op and leave the scanner running.  Stopping is always
     * attempted, and the reference is cleared either way - a SecurityException here means the system
     * already tore the scan down with the permission.
     */
    @SuppressLint("MissingPermission")
    public boolean stopScan() {
        if (bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException e) {
                // IllegalStateException: adapter turned off underneath us - the scan is gone either way.
                Log.w("scanResults", "stopScan failed, dropping scanner reference", e);
            } finally {
                bluetoothLeScanner = null;
            }
        }
        return true;
    }

    public List<ScanResult> getScanResults() {
        return scanResults;
    }

    public boolean isContainScanResult(ScanResult result) {
        for (ScanResult scanResult : scanResults) {
            if (scanResult.getDevice().getAddress().equals(result.getDevice().getAddress())) {
                return true;
            }
        }
        return false;
    }

    private boolean isStartWithBittle(String name) {
        return name.toLowerCase().contains("bittle") || name.toLowerCase().contains("stardust");
    }
}