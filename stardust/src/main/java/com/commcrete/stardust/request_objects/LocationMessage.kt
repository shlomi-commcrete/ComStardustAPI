package com.commcrete.stardust.request_objects

import com.google.gson.Gson
import com.google.gson.JsonObject

data class LocationMessage (
    var src : String = "",
    var dst : String = "",
    val latitude : String = "",
    val longitude : String = "",
    val altitude : String = "",
    var srcPhone : String? = "",
    var srcDisplayName : String? = "",
){

    fun getLocationMessageTemplate() {
        LocationMessage("123", "456")
    }

}

fun LocationMessage.toJson() : JsonObject {
    return Gson().fromJson(Gson().toJson(this) ,  JsonObject::class.java)
}