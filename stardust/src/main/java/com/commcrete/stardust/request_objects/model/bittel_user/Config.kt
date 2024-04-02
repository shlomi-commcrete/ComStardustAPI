package com.commcrete.stardust.request_objects.model.bittel_user

import com.google.gson.Gson

data class Config(
    val appId: String,
    val bittelId: String
)

fun Config.toJson() : String{
    return Gson().toJson(this)
}