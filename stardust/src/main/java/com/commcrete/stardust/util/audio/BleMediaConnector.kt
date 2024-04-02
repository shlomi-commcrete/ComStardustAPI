package com.commcrete.stardust.util.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

open class BleMediaConnector (){

    fun getPreferredDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val audioDevices = audioManager.getDevices(
            AudioManager.GET_DEVICES_INPUTS)
        val bleDevices = audioDevices?.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        bleDevices?.let {
            if(it.isNotEmpty()){
                return it[0]
            }
        }
        return null
    }
}