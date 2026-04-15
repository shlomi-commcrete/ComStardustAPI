package com.commcrete.stardust.request_objects

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.util.*

@Parcelize
data class Message(
    var senderID: String = "",
    var text: String = "",
    var epochTimeMs: Long = Date().time,
    var seen: Boolean = false
) : Parcelable