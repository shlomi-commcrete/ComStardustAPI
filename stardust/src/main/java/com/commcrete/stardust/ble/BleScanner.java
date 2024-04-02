package com.commcrete.stardust.ble;


import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.lifecycle.MutableLiveData;


import java.util.ArrayList;
import java.util.List;

public class BleScanner {
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
            Log.d("scanResults", "onBatchScanResults");

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
    };

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

    private boolean checkBlePermissions() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            // Request permission logic here
            return false;
        } else {
            return true;
        }
    }

    @SuppressLint("MissingPermission")
    public boolean startScan() {
        Log.d("scanResults", "startScan");
        if (!checkBlePermissions()) {
            Log.d("scanResults", "no permissions");
            return false;
        }

        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder()
                .setReportDelay(1000)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        scanSettingsBuilder.setLegacy(true);

        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(new ScanFilter.Builder().build());

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        bluetoothLeScanner.startScan(scanFilters, scanSettingsBuilder.build(), scanCallback);
        Log.d("scanResults", "scanning");
        return true;
    }

    @SuppressLint("MissingPermission")
    public boolean stopScan() {
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
        return name.startsWith("Bittle");
    }
}