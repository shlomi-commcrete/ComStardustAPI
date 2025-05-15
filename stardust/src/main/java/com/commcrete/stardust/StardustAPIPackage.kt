package com.commcrete.stardust

import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier

data class StardustAPIPackage(
    val source : String,
    val destination : String,
    val requireAck : Boolean = false,
    val carrier: Carrier? = null,
    val isGroup: Boolean = false
    )
