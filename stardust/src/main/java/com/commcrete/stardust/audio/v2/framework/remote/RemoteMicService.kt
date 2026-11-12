package com.commcrete.stardust.audio.v2.framework.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.commcrete.stardust.audio.v2.framework.MicCapture
import com.commcrete.stardust.audio.v2.framework.MicSilenceWatch
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The remote microphone: a microphone foreground service that runs in the CONSUMER's package — this
 * library's manifest declares it, and the merger puts it in the consumer's APK — not in the host app's.
 *
 * Why it exists: an ATAK plugin runs inside ATAK's process, and ATAK declares no microphone foreground
 * service, so Android mutes its microphone a few seconds after the phone locks. A service of the
 * consumer's own package, started while the host is on screen, keeps a live microphone through a locked
 * screen and Doze (device-tested on Android 14, PIN-locked). [RemoteMic] starts it and binds to it;
 * [RemoteCaptureSource] reads it.
 *
 * Presentation is the consumer's: the notification and its channel arrive ready-built in the start
 * intent ([RemoteMic.arm]); this service only hands them to Android.
 *
 * Only ATAK may bind (any `com.atakmap.app*` package, by name).
 */
class RemoteMicService : Service() {

    private val lastFailure = AtomicInteger(RemoteMicFailure.NONE)

    /** The live capture, so close()/a second open()/the caller's death can stop it. One at a time. */
    @Volatile private var active: Capture? = null

    private class Capture(val mic: MicCapture, val silence: MicSilenceWatch, val client: IBinder, val onDeath: IBinder.DeathRecipient)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.parcelable<Notification>(EXTRA_NOTIFICATION)
        if (notification == null) {
            // Nothing to show means no foreground service, and a microphone service that is not in the
            // foreground is muted like any background app — so do not pretend to run.
            StardustLog.w(LogType.PTT, TAG, "started without a notification - stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        intent.parcelable<NotificationChannel>(EXTRA_CHANNEL)?.let {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(it)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            StardustLog.i(LogType.PTT, TAG, "armed")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (started while the host was not on screen),
            // SecurityException (no RECORD_AUDIO), or a bad notification. RemoteMic sees the binding drop.
            StardustLog.w(LogType.PTT, TAG, "could not go to the foreground: ${e.javaClass.simpleName}")
            stopSelf()
        }
        // Not sticky: restarted by the system it would not be in the foreground, so it could not record.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** The host disarmed or died: nothing will call again, so stop being a foreground service. */
    override fun onUnbind(intent: Intent?): Boolean {
        stopCapture()
        stopSelf()
        return false
    }

    override fun onDestroy() {
        stopCapture()
        StardustLog.i(LogType.PTT, TAG, "disarmed")
        super.onDestroy()
    }

    private val binder = object : IRemoteMic.Stub() {

        override fun open(rateHz: Int, audioSource: Int, inputDeviceId: Int, client: IBinder): ParcelFileDescriptor? {
            if (!callerIsAtak()) return refuse(RemoteMicFailure.CALLER_REJECTED)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return refuse(RemoteMicFailure.NO_PERMISSION)
            }
            stopCapture()
            lastFailure.set(RemoteMicFailure.NONE)
            return startCapture(rateHz, audioSource, inputDeviceId, client)
        }

        override fun lastFailure(): Int = lastFailure.get()

        override fun close() = stopCapture()
    }

    private fun refuse(code: Int): ParcelFileDescriptor? {
        lastFailure.set(code)
        StardustLog.w(LogType.PTT, TAG, "open refused: $code")
        return null
    }

    /** By package name only, as decided for this product: any ATAK flavour. */
    private fun callerIsAtak(): Boolean =
        packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().any {
            it == ATAK_PACKAGE || it.startsWith("$ATAK_PACKAGE.")
        }

    private fun startCapture(rateHz: Int, audioSource: Int, inputDeviceId: Int, client: IBinder): ParcelFileDescriptor? {
        val device = inputDevice(inputDeviceId)
        val plan = AudioCaptureConfig.CapturePlan(
            captureRate = rateHz,
            audioSource = audioSource,
            preferredInputDevice = device,
            useUsbInput = device?.type in USB_INPUTS,
        )
        val frameSamples = ((rateHz * FRAME_MS) / 1000).coerceAtLeast(160)
        val mic = MicCapture(this, plan, frameSamples)
        if (!mic.open()) {
            mic.release()
            return refuse(RemoteMicFailure.MIC_UNAVAILABLE)
        }
        // This process holds a microphone service, so "not on screen" never mutes it here.
        val silence = MicSilenceWatch(this, backgroundCanMute = false)
        val sessionId = mic.audioSessionId
        sessionId?.let(silence::currentCause)?.let { mutedAtOpen ->
            mic.release()
            return refuse(RemoteMicFailure.toCode(mutedAtOpen))
        }

        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        val onDeath = IBinder.DeathRecipient { mic.stop() }
        runCatching { client.linkToDeath(onDeath, 0) }
            .onFailure { mic.stop() } // already dead: the capture ends at once
        val capture = Capture(mic, silence, client, onDeath)
        active = capture
        sessionId?.let { id ->
            silence.watch(id) { cause ->
                lastFailure.set(RemoteMicFailure.toCode(cause))
                StardustLog.i(LogType.PTT, TAG, "muted mid-capture: $cause")
                mic.stop()
            }
        }
        Thread({ pump(capture, writeEnd, frameSamples) }, "RemoteMicPump").start()
        StardustLog.d(LogType.PTT, TAG, "capturing at $rateHz Hz, device=${device?.type ?: "default"}")
        // AIDL writes a returned ParcelFileDescriptor with PARCELABLE_WRITE_RETURN_VALUE, which closes this copy.
        return readEnd
    }

    /** Microphone → pipe, until a stop, a mute, a read error, or the reader closing its end. */
    private fun pump(capture: Capture, writeEnd: ParcelFileDescriptor, frameSamples: Int) {
        val shorts = ShortArray(frameSamples)
        val bytes = ByteBuffer.allocate(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
            try {
                // On the stop flag, not only on a negative read: a stopped AudioRecord can return 0 instead of
                // an error code, and a loop waiting for a negative spun at 100% CPU with the mic never
                // released (device-tested). Every stop path (close, mute, caller's death) sets the flag.
                while (!capture.mic.stopRequested) {
                    val n = capture.mic.read(shorts)
                    // Negative: the device went away.
                    if (n < 0) break
                    if (n == 0) continue
                    bytes.clear()
                    bytes.asShortBuffer().put(shorts, 0, n)
                    out.write(bytes.array(), 0, n * 2)
                }
            } catch (e: IOException) {
                // EPIPE: the reader closed its end (key-up, or the host died). That is the end of this
                // capture, not a fault.
            } finally {
                capture.silence.stop()
                capture.mic.release()
                runCatching { capture.client.unlinkToDeath(capture.onDeath, 0) }
                if (active === capture) active = null
            }
        }
    }

    private fun stopCapture() {
        active?.mic?.stop()
    }

    private fun inputDevice(id: Int): AudioDeviceInfo? {
        if (id < 0) return null
        return getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.id == id }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(key, T::class.java)
        else getParcelableExtra(key) as? T

    companion object {
        private const val TAG = "RemoteMicService"

        internal const val EXTRA_NOTIFICATION = "com.commcrete.stardust.remotemic.NOTIFICATION"
        internal const val EXTRA_CHANNEL = "com.commcrete.stardust.remotemic.CHANNEL"

        /** Only this service posts in the consumer's package under this id. */
        private const val NOTIFICATION_ID = 0x5D_0001

        private const val ATAK_PACKAGE = "com.atakmap.app"

        /** Same frame size as MicCaptureSource's, so the pipe carries whole capture frames. */
        private const val FRAME_MS = 40

        private val USB_INPUTS = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
