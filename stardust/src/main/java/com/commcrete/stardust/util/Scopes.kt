package com.commcrete.stardust.util

import com.commcrete.stardust.ble.ClientConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object Scopes {

    fun getMainCoroutine (): CoroutineScope {
        return CoroutineScope(Dispatchers.Main)
    }

    fun getDefaultCoroutine (): CoroutineScope {
        return CoroutineScope(Dispatchers.Default)
    }
}