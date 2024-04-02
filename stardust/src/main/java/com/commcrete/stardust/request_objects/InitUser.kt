package com.commcrete.stardust.request_objects

import com.google.gson.Gson

class InitUser(private val appId : String = "")

fun InitUser.toJson() : String {
    return Gson().toJson(this)
}