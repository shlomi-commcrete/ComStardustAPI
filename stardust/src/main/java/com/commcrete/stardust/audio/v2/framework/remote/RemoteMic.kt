package com.commcrete.stardust.audio.v2.framework.remote

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.StardustLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Arms the remote microphone ([RemoteMicService]) so that PTT keeps a live microphone while the phone is
 * locked. While [state] is [State.Armed], every v2 PTT recording captures through it; otherwise PTT uses the
 * local microphone exactly as before.
 *
 * Why: the host (ATAK) declares no microphone foreground service, so Android mutes a microphone opened in
 * its process a few seconds after the phone locks. The service runs in the consumer's own package instead,
 * where this library's manifest declares it.
 *
 * Android allows starting a microphone foreground service only while the app is on screen, so [arm] must be
 * called then; once armed, the microphone keeps working locked, in Doze and in the background, until
 * [disarm], a reboot, or the service's process dying.
 *
 * One per host process, like the rest of the v2 wiring.
 *
 * Threading: [arm] and [disarm] are main-thread calls (they bind a service). [state] is updated on the main
 * thread.
 */
object RemoteMic {

    sealed interface State {
        /** Never armed, or [disarm]ed. */
        object NotArmed : State

        /** The service was started and its binding is being established. */
        object Arming : State

        /** PTT captures through the remote microphone. */
        object Armed : State

        /** The last [arm] could not arm, or an armed service went away. PTT uses the local microphone. */
        data class Unavailable(val reason: Reason) : State
    }

    enum class Reason {
        /** [arm] was called while the host was not on screen; Android refuses to start the service then. */
        NOT_ON_SCREEN,

        /** The consumer's package has no RECORD_AUDIO grant (its own grant — the host's does not count). */
        PERMISSION_MISSING,

        /** Android refused to start or bind the service. */
        START_REFUSED,

        /** The armed service's process died or the binding dropped; arm again while on screen. */
        DISCONNECTED,
    }

    private const val TAG = "RemoteMic"

    private val _state = MutableStateFlow<State>(State.NotArmed)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var service: IRemoteMic? = null
    private var connection: ServiceConnection? = null
    private var serviceIntent: Intent? = null

    /**
     * Starts and binds the remote microphone service in [servicePackage] — the consumer's package, where
     * the merged manifest declares it (for a plugin: its pluginContext.packageName).
     *
     * [notification] and [channel] are the consumer's: built with a context of [servicePackage] (so its
     * icon resolves) and shown for as long as the microphone stays armed. Android requires one.
     *
     * Safe to call again: a no-op while armed or arming. Returns the resulting [state].
     */
    fun arm(context: Context, servicePackage: String, channel: NotificationChannel, notification: Notification): State {
        if (_state.value == State.Armed || _state.value == State.Arming) return _state.value
        if (!isOnScreen()) return fail(Reason.NOT_ON_SCREEN)
        if (context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, servicePackage) !=
            PackageManager.PERMISSION_GRANTED
        ) return fail(Reason.PERMISSION_MISSING)

        val component = ComponentName(servicePackage, RemoteMicService::class.java.name)
        val start = Intent().setComponent(component)
            .putExtra(RemoteMicService.EXTRA_NOTIFICATION, notification)
            .putExtra(RemoteMicService.EXTRA_CHANNEL, channel)
        try {
            context.startForegroundService(start)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException / IllegalStateException.
            StardustLog.w(LogType.PTT, TAG, "start refused: ${e.javaClass.simpleName}")
            return fail(Reason.START_REFUSED)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IRemoteMic.Stub.asInterface(binder)
                _state.value = State.Armed
                StardustLog.i(LogType.PTT, TAG, "armed")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Its process died. Android would rebind it, but not as a foreground service, so it could
                // not record: drop the binding and wait for the consumer to arm again while on screen.
                service = null
                StardustLog.w(LogType.PTT, TAG, "remote microphone lost")
                release(context)
                _state.value = State.Unavailable(Reason.DISCONNECTED)
            }
        }
        val bindIntent = Intent().setComponent(component)
        val bound = try {
            context.bindService(bindIntent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) {
            runCatching { context.stopService(bindIntent) }
            return fail(Reason.START_REFUSED)
        }
        connection = conn
        serviceIntent = bindIntent
        _state.value = State.Arming
        return _state.value
    }

    /** Stops the remote microphone; PTT returns to the local one. Idempotent. */
    fun disarm(context: Context) {
        release(context)
        _state.value = State.NotArmed
    }

    /** The armed service, or null. Read by the v2 wiring once per recording. */
    internal fun service(): IRemoteMic? = service?.takeIf { it.asBinder().isBinderAlive }

    private fun release(context: Context) {
        service = null
        connection?.let { runCatching { context.unbindService(it) } }
        serviceIntent?.let { runCatching { context.stopService(it) } }
        connection = null
        serviceIntent = null
    }

    private fun fail(reason: Reason): State {
        StardustLog.i(LogType.PTT, TAG, "not armed: $reason")
        val failed = State.Unavailable(reason)
        _state.value = failed
        return failed
    }

    private fun isOnScreen(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
