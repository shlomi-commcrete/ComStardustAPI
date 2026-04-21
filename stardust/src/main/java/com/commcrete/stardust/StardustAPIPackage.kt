package com.commcrete.stardust

import com.commcrete.stardust.util.Carrier

data class StardustAPIPackage(
    val senderId: String,
    val receiverId: String,
    val groupId: String? = null,
    val requireAck : Boolean = false,
    val carrier: Carrier? = null,
    val spare : Int = 0
    )