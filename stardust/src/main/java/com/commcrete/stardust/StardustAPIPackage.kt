package com.commcrete.stardust

import com.commcrete.stardust.util.Carrier

data class StardustAPIPackage(
    val source : String,
    val destination : String,
    val requireAck : Boolean = false,
    val carrier: Carrier? = null,
    val isGroup: Boolean = false,
    val spare : Int = 0
    ) {

    fun getRealSourceId(): String {
        return if(isGroup) destination else source
    }
}