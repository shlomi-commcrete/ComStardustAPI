package com.commcrete.stardust.request_objects

import com.google.gson.Gson

class RegisterUser (var displayName : String? = "",
                    var appId : String? = "",
                    var deviceId : String? = "",)


fun RegisterUser.toJson() : String {
    return Gson().toJson(this)
}
