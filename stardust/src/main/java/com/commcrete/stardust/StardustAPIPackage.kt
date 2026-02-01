package com.commcrete.stardust

import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.UsersUtils.mRegisterUser

data class StardustAPIPackage(
    val source : String,
    val destination : String,
    val requireAck : Boolean = false,
    val carrier: Carrier? = null,
    val spare : Int = 0
    ) {

    val isGroup: Boolean = GroupsUtils.isGroup(source) || GroupsUtils.isGroup(destination)

    fun getRealSourceId(): String {
        return if(isGroup && (destination != mRegisterUser?.appId)) destination else source
    }
}