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

    /**
     * Backup copy of the paired radio's display name. The primary copy is the
     * `bittel_device_name` preference; this one is read only when that comes back empty.
     * Maintained by [com.commcrete.stardust.util.SharedPreferencesUtil] — don't set directly.
     */
    var bittelName: String? = null

    /** Backup copy of the paired radio's MAC address (primary: the `bittel_device` preference). */
    var bittelMacAddress: String? = null

    /**
     * The operator's own contact icon — the same preset key a contact stores in
     * `ContactEntity.image`. Null means "not chosen", and also "not set on this instance":
     * [com.commcrete.stardust.util.SharedPreferencesUtil.setAppUser] carries the saved key over
     * onto a freshly built user that leaves it null, so rebuilding the user never drops it.
     */
    var iconKey: String? = null
}


fun RegisterUser.toJson() : String {
    return Gson().toJson(this)
}
