package com.commcrete.stardust.transport

import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The radio this app is paired with, as saved locally.
 *
 * [name] is null until it is known: a radio can finish bonding before Android has resolved its
 * name, and the SDK fills it in later (name-change broadcast, or the radio's GATT Device Name)
 * without the host doing anything — the stream simply emits again.
 */
data class PairedDevice(
    val address: String,
    val name: String?,
)

/**
 * Publishes [PairedDevice] from the saved identity. Refreshed by [SharedPreferencesUtil] on every
 * identity write, so it can never disagree with what startup reconnects from.
 */
internal object PairedDeviceTracker {

    private val _state = MutableStateFlow<PairedDevice?>(null)

    @Volatile
    private var loaded = false

    val state: StateFlow<PairedDevice?>
        get() {
            if (!loaded) refresh()
            return _state.asStateFlow()
        }

    fun refresh() {
        loaded = true
        _state.value = read()
    }

    private fun read(): PairedDevice? {
        val address = SharedPreferencesUtil.getBittelDevice()
            ?.takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
            ?: return null
        val name = SharedPreferencesUtil.getBittelDeviceName()
            .takeIf { it.isNotBlank() && !it.equals("empty", ignoreCase = true) }
        return PairedDevice(address, name)
    }
}
