package com.commcrete.stardust.util.audio

import com.commcrete.stardust.stardust.model.StardustPackage

interface PttInterface {

    fun getSource(): String

    fun getDestination(): String

    fun sendDataToBle(pkg: StardustPackage)

    fun maxPTTTimeoutReached() {}
}