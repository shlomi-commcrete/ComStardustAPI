package com.commcrete.stardust

import com.commcrete.stardust.stardust.model.StardustControlByte

data class StardustAPIPackage(
    val source : String,
    val destination : String,
    val requireAck : Boolean = false,
    val isLR : Boolean = false
    )
