package com.commcrete.stardust.util.audio

import com.commcrete.stardust.stardust.model.StardustPackage

interface PttInterface {
    fun getSource(): String

    fun getDestenation(): String?

    fun sendDataToBle(bittelPackage: StardustPackage)

    fun maxPTTTimeoutReached() {}
}