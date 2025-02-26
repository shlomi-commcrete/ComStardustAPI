package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.commcrete.stardust.util.SharedPreferencesUtil
import timber.log.Timber

open class BleMediaConnector (){

    fun getPreferredDevice(audioManager: AudioManager, type: Int, context: Context): AudioDeviceInfo? {
        // Retrieve the default device type from SharedPreferences
        val defaultType = if (type == AudioManager.GET_DEVICES_OUTPUTS) {
            SharedPreferencesUtil.getOutputDevice(context)
        } else {
            null
        }
        val defaultInputType = if (type == AudioManager.GET_DEVICES_INPUTS) {
            SharedPreferencesUtil.getInputDevice(context)
        } else {
            null
        }

        // Fetch all devices of the specified type (inputs/outputs)
        val audioDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(type)
        } else {
            TODO("VERSION.SDK_INT < M")
        }

        if( type == AudioManager.GET_DEVICES_OUTPUTS && defaultType == AudioDeviceInfo.TYPE_UNKNOWN) {
            return null
        }

        if( type == AudioManager.GET_DEVICES_INPUTS && defaultInputType == AudioDeviceInfo.TYPE_UNKNOWN) {
            return null
        }

        // Define the hierarchy of device types based on input/output
        val deviceTypes = when (type) {
            AudioManager.GET_DEVICES_INPUTS -> AudioDeviceHierarchyInput.values().map { it.deviceInfo }
            AudioManager.GET_DEVICES_OUTPUTS -> AudioDeviceHierarchyOutput.values().map { it.deviceInfo }
            else -> emptyList()
        }

        // Filter devices to match the specified hierarchy
        val matchingDevices = audioDevices?.filter {
            it.type in deviceTypes
        }

        // If defaultType is set, prioritize devices matching the default type
        if(type == AudioManager.GET_DEVICES_OUTPUTS)  {
            val prioritizedDevices = if (defaultType != null) {
                matchingDevices?.sortedWith { device1, device2 ->
                    val device1IsDefault = if (device1.type == defaultType) 0 else 1
                    val device2IsDefault = if (device2.type == defaultType) 0 else 1

                    val device1HierarchyIndex = AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device1.type }
                    val device2HierarchyIndex = AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device2.type }

                    // Compare by default priority first, then by hierarchy
                    when {
                        device1IsDefault != device2IsDefault -> device1IsDefault - device2IsDefault
                        else -> device1HierarchyIndex - device2HierarchyIndex
                    }
                }
            } else {
                matchingDevices?.sortedBy { device ->
                    AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device.type }
                }
            }
            return prioritizedDevices?.firstOrNull()

        }

        // If defaultType is set, prioritize devices matching the default type
        if(type == AudioManager.GET_DEVICES_INPUTS)  {
            val prioritizedDevices = if (defaultInputType != null) {
                matchingDevices?.sortedWith { device1, device2 ->
                    val device1IsDefault = if (device1.type == defaultInputType) 0 else 1
                    val device2IsDefault = if (device2.type == defaultInputType) 0 else 1

                    val device1HierarchyIndex = AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device1.type }
                    val device2HierarchyIndex = AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device2.type }

                    // Compare by default priority first, then by hierarchy
                    when {
                        device1IsDefault != device2IsDefault -> device1IsDefault - device2IsDefault
                        else -> device1HierarchyIndex - device2HierarchyIndex
                    }
                }
            } else {
                matchingDevices?.sortedBy { device ->
                    AudioDeviceHierarchyOutput.values().indexOfFirst { it.deviceInfo == device.type }
                }
            }
            return prioritizedDevices?.firstOrNull()

        }
        return null
    }

    enum class AudioDeviceHierarchyInput (val deviceInfo : Int) {
        BLE (AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        Headphones (AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        Headset (AudioDeviceInfo.TYPE_USB_HEADSET),
        USB_Device (AudioDeviceInfo.TYPE_USB_DEVICE),
        Earpiece (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        None (AudioDeviceInfo.TYPE_BUILTIN_MIC)
    }

    enum class AudioDeviceHierarchyOutput (val deviceInfo : Int) {
        BLE (AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        Headphones (AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        Headset (AudioDeviceInfo.TYPE_USB_HEADSET),
        Earpiece (AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        None (AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }
}