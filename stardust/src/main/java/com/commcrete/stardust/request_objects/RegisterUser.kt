package com.commcrete.stardust.request_objects

import com.commcrete.stardust.request_objects.model.bittel_user.BittelUser
import com.commcrete.stardust.request_objects.model.user_list.User
import com.google.gson.Gson

class RegisterUser (var displayName : String? = "",
                    var phone : String? = "",
                    var password : String? = "",
                    var pttEnabled : Boolean? = true,
                    var status : String? = "",
                    var profileImageUrl : String? = "",
                    var location : Array<Double>? = arrayOf(),
                    var licenseType : String?,
                    var appId : String? = "",
                    var token : String? = "",
                    var bittelId : String? = "",
                    var bittelName : String? = "",
                    var bittelMacAddress : String? = "") {
    var bittelUser : BittelUser? = null
}

fun RegisterUser.toJson() : String {
    return Gson().toJson(this)
}

fun RegisterUser.getDataFromLogin(user: User?, password: String) {
    user?.let { user ->
        this.phone = user.phone
        this.displayName = user.displayName
        this.password = password
        this.status = user.status
        this.location = arrayOf(user.location[0], user.location[1])
        this.licenseType = user.licenceType
        this.appId = user.appId?.get(0) ?: ""
        this.token = user.token
        this.bittelId = if(user.bittelId.isNullOrEmpty()) "" else user.bittelId[0]
        this.bittelName = user.bittelName
        this.bittelMacAddress = user.bittelMacAddress
    }
}