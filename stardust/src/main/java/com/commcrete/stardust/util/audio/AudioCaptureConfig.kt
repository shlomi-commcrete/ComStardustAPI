package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import timber.log.Timber

/**
 * Shared capture planning and routing for AI/CODEC2 recorders.
 *
 * Both recorders should capture at the device-native input rate (especially USB/JBOX),
 * run filters at that native rate, then resample only at the encoder handoff.
 */
internal object AudioCaptureConfig {

    data class CapturePlan(
        val captureRate: Int,
        val audioSource: Int,
        val preferredInputDevice: AudioDeviceInfo?,
        val useUsbInput: Boolean,
    )

    private const val TAG = "AudioCaptureConfig"

    @SuppressLint("NewApi")
    fun buildCapturePlan(
        context: Context,
        requestedRate: Int,
        defaultAudioSource: Int,
    ): CapturePlan {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val inputs = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        val preferred = resolvePreferredInput(context, inputs)
        val usb = preferred?.takeIf { it.isUsbInput() } ?: inputs.firstOrNull { it.isUsbInput() }
        val selected = usb ?: preferred
        val useUsb = usb != null
        val captureRate = pickCaptureRate(selected, requestedRate)


        Timber.tag(TAG).d(
            "capturePlan: requested=%d selectedRate=%d source=%d usb=%b deviceType=%s device=%s",
            requestedRate,
            captureRate,
            defaultAudioSource,
            useUsb,
            selected?.type?.toString() ?: "none",
            selected?.productName?.toString() ?: "none",
        )

        return CapturePlan(
            captureRate = captureRate,
            audioSource = defaultAudioSource,
            preferredInputDevice = selected,
            useUsbInput = useUsb,
        )
    }

    @SuppressLint("NewApi")
    fun applyInputRoute(context: Context, audioRecord: AudioRecord?, device: AudioDeviceInfo?) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return

        if (device == null) {
            clearInputRoute(context)
            return
        }

        runCatching { audioRecord?.setPreferredDevice(device) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.setCommunicationDevice(device) }
        }

        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            runCatching { audioManager.startBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = true }
        } else {
            runCatching { audioManager.stopBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = false }
        }
    }

    @SuppressLint("NewApi")
    fun clearInputRoute(context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.isBluetoothScoOn = false }
    }

    private fun resolvePreferredInput(
        context: Context,
        inputs: List<AudioDeviceInfo>,
    ): AudioDeviceInfo? {
        val wantedType = com.commcrete.stardust.util.SharedPreferencesUtil.getInputDevice(context)
        if (wantedType == AudioDeviceInfo.TYPE_UNKNOWN) return null
        return inputs.firstOrNull { it.type == wantedType }
            ?: inputs.firstOrNull { it.isUsbInput() }
            ?: inputs.firstOrNull()
    }

    /**
     * Pick the best capture rate for [device].
     *
     * **Goal:** capture at a rate ≥ [requestedRate] so the resampler only
     * downsizes (or skips if exact match). Upsizing produces content that
     * doesn't exist in the original signal and degrades encoder output.
     *
     * **USB devices:** prefer [requestedRate] if advertised, else highest
     * advertised rate.
     *
     * **Non-USB devices:** smallest advertised rate ≥ [requestedRate].
     *
     * **Fallback:** if no advertised rate meets the minimum, return
     * [requestedRate] and let Android's AudioRecord negotiate — it may
     * succeed at a rate it didn't explicitly advertise. This prevents
     * the encoder from receiving garbage due to low-quality upsampling.
     */
    private fun pickCaptureRate(device: AudioDeviceInfo?, requestedRate: Int): Int {
        if (device == null) return requestedRate
        val advertised = device.sampleRates.toList()

        if (device.isUsbInput()) {
            if (requestedRate in advertised) return requestedRate
            if (advertised.isEmpty()) return 48_000
            // Prefer a rate ≥ requested; fall back to requested if none.
            return advertised.filter { it >= requestedRate }.minOrNull()
                ?: advertised.maxOrNull()?.takeIf { it >= requestedRate }
                ?: requestedRate
        }

        // Non-USB: smallest advertised rate ≥ requestedRate.
        if (advertised.isEmpty()) return requestedRate
        val atOrAbove = advertised.filter { it >= requestedRate }.minOrNull()
        if (atOrAbove != null) return atOrAbove
        // No advertised rate is high enough — fall back to requestedRate
        // so AudioRecord can negotiate. This is safer than picking a rate
        // below the encoder's minimum which would require upsampling.
        return requestedRate
    }

    private fun AudioDeviceInfo.isUsbInput(): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
}

