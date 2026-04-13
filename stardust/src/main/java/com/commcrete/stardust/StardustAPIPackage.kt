package com.commcrete.stardust

import com.commcrete.stardust.util.Carrier

data class StardustAPIPackage(
    val senderId: String,
    val groupId: String? = null,
    val receiverId: String,
    val requireAck : Boolean = false,
    val carrier: Carrier? = null,
    val spare : Int = 0
    )