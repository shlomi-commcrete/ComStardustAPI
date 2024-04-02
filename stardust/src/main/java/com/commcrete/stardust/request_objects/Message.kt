package com.commcrete.stardust.request_objects

import android.os.Parcelable
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import kotlinx.android.parcel.Parcelize
import java.util.*


@Parcelize
data class Message(
    var senderID: String = "",
    var text: String = "",
    var epochTimeMs: Long = Date().time,
    var seen: Boolean = false
) : Parcelable

fun Message.getMessageItem () : MessageItem {
    return MessageItem(senderID = this.senderID, text = this.text, epochTimeMs = this.epochTimeMs, seen = SeenStatus.SEEN, senderName = "")
}