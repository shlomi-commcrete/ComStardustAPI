package com.commcrete.stardust.request_objects

import com.commcrete.stardust.room.new_db.internal.normalizeId
import com.google.gson.Gson

class RegisterUser (
    var displayName: String,
    private val _appId: String,
    private val _deviceId: String? = null,
) {

    var appId: String = normalizeId(_appId)
        set(value) { field = normalizeId(value) }

    var deviceId: String? = _deviceId?.let { normalizeId((it)) }
        set(value) { field = value ?.let { normalizeId(it) } }
}


fun RegisterUser.toJson() : String {
    return Gson().toJson(this)
}
