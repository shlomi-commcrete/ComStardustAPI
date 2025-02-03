package com.commcrete.stardust.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.launch

object BluetoothStateManager {

    private val _bluetoothState = MutableLiveData<Boolean>()
    val bluetoothState: MutableLiveData<Boolean> get() = _bluetoothState

    fun initialize(context: Context) {
        Scopes.getMainCoroutine().launch {
            _bluetoothState.value = getBluetoothState()
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                bluetoothState.value = when (state) {
                    BluetoothAdapter.STATE_ON -> true
                    BluetoothAdapter.STATE_OFF -> false
                    else -> false
                }
            }
        }
    }

    private fun getBluetoothState(): Boolean {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled == true
    }




}