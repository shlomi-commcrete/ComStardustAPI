package com.commcrete.stardust.util

import com.commcrete.stardust.ble.ClientConnection

object BittelConnectivityManager {

    internal var clientConnection : ClientConnection?  = null

    init {
        clientConnection = ClientConnection(context = DataManager.context)
    }
}