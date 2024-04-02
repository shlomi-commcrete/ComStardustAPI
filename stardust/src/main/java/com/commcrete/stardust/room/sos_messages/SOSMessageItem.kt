package com.commcrete.stardust.room.sos_messages

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Entity(tableName = "sos_messages_table")
@Parcelize
data class SOSMessageItem (
    @PrimaryKey(autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "text")
    var text : String,
) : Parcelable {
    companion object{
        const val NEW_MESSAGE = "New Message"
    }

    fun isSOSValid () : Boolean{
        return (text.isNotEmpty() && text != NEW_MESSAGE)
    }
}
