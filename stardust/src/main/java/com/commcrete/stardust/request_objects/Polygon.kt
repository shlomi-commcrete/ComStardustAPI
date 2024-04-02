package com.commcrete.stardust.request_objects

import com.google.gson.Gson
import com.google.gson.JsonObject

data class Polygon (
    var src : String? = "",
    var numOfBean : String? = "",
)
fun Polygon.toJson() : JsonObject {
    return Gson().fromJson(Gson().toJson(this) ,  JsonObject::class.java)
}