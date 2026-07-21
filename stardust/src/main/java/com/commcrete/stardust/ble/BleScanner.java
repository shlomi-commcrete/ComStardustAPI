package com.commcrete.stardust.ble;


import static android.content.Context.BLUETOOTH_SERVICE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.MutableLiveData;


import com.commcrete.stardust.util.DataManager;

import java.util.ArrayList;
import java.util.List;

public class BleScanner {
    private static final long SCAN_TIMEOUT_MS = 30_000L;

    private BluetoothLeScanner bluetoothLeScanner = null;
    private BluetoothAdapter bluetoothAdapter;
    private List<ScanResult> scanResults = new ArrayList<>();
    public MutableLiveData<List<ScanResult>> scanResultsLiveData = new MutableLiveData<>();
    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());

    private ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            // Collect here too, not just in the batch callback — otherwise single-result mode
            // (reportDelay 0) would never populate the list.
            addIfMatch(result);
            notifyResults();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                addIfMatch(result);
            }
            notifyResults();
        }
    };

    /** Adds a scan result if it's one of our radios and not already collected. */
    @SuppressLint("MissingPermission")
    private void addIfMatch(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        // Prefer the advertised name (needs only SCAN permission); fall back to the cached device
        // name. Either identifying as one of our radios is enough.
        String advertisedName = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
        String deviceName = result.getDevice().getName();
        if (isStartWithBittle(advertisedName) || isStartWithBittle(deviceName)) {
            if (!isContainScanResult(result)) {
                scanResults.add(result);
            }
        }
    }

    public BleScanner() {
        BluetoothManager bluetoothManager = (BluetoothManager) DataManager.appContext.getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    private void notifyResults() {
        scanResultsLiveData.postValue(getScanResults());
    }

    public MutableLiveData<List<ScanResult>> getScanResultsLiveData() {
        return scanResultsLiveData;
    }

    private boolean checkBlePermissions() {
        // Request permission logic here
        return ActivityCompat.checkSelfPermission(DataManager.appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public boolean startScan() {
        if (!checkBlePermissions()) {
            return false;
        }
        // Guard against a null scanner (adapter off/absent) — getBluetoothLeScanner() returns null
        // when Bluetooth is disabled, which previously NPE'd here.
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return false;
        }
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            return false;
        }

        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder()
                .setReportDelay(1000)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        scanSettingsBuilder.setLegacy(true);

        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(new ScanFilter.Builder().build());

        bluetoothLeScanner = scanner;
        bluetoothLeScanner.startScan(scanFilters, scanSettingsBuilder.build(), scanCallback);

        // Bound battery use: stop scanning automatically after a timeout instead of running forever.
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        scanTimeoutHandler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
        return true;
    }

    @SuppressLint("MissingPermission")
    public boolean stopScan() {
        scanTimeoutHandler.removeCallbacksAndMessages(null);
        if (!checkBlePermissions()) {
            return false;
        }
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            bluetoothLeScanner = null;
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
        if (name == null) return false;
        String lower = name.toLowerCase();
        // Unified with PairingRepository / bonded-device matching — includes the "bittel" spelling,
        // which the scan filter previously missed (so a "bittel"-named unit never showed up).
        return lower.contains("bittle") || lower.contains("bittel") || lower.contains("stardust");
    }
}