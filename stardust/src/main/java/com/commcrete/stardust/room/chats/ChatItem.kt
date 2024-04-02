package com.commcrete.stardust.room.chats

import android.os.Parcelable
import androidx.room.*
import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.request_objects.Message
import kotlinx.android.parcel.Parcelize
import org.json.JSONObject

@Entity(tableName = "chats_table",  indices = [Index(
    value = ["chat_id"],
    unique = true
)])
@Parcelize
data class ChatItem (
    @PrimaryKey (autoGenerate = true)
    val id : Int = 0,
    @ColumnInfo(name = "chat_id")
    val chat_id : String,
    @ColumnInfo(name = "last_message_id")
    val lastMessageId : String = "",
    @ColumnInfo(name = "chat_name")
    val name : String = "",
    @ColumnInfo(name = "audio_received")
    var isAudioReceived : Boolean = false,
    @ColumnInfo(name = "enable_background_ptt")
    var enableBackgroundPtt : Boolean = true,
    val chatContacts : String = "",
    val bittelIDS : String = "",
    val smartphoneBittelIDS : String = "",
    val numOfUnseenMessages : Int = 0,
    @Embedded var message: Message? = null,
    @Embedded var user: User? = null,

    ) : Parcelable

fun ChatItem.getChatContacts() : List<String>  {
    return if(this.isChatContactsEmptyOrNull()){
        emptyList()
    }else{
        val mutableList = mutableListOf<String>()
        try {
            val contactsJSON = JSONObject(this.chatContacts)
            for(id in contactsJSON.keys()){
                mutableList.add(id)
            }
            mutableList
        } catch (e : Exception){
            e.printStackTrace()
            emptyList()
        }
    }
}

fun ChatItem.getBittelIDS() : Map<String, String>  {
    return if(this.isBittelIDSEmptyOrNull()){
        emptyMap()
    }else{
        val mutableList = mutableMapOf<String, String>()
        try {
            val contactsJSON = JSONObject(this.bittelIDS)
            for(id in contactsJSON.keys()){
                mutableList[id] = contactsJSON.getString(id)
            }
            mutableList
        } catch (e : Exception){
            e.printStackTrace()
            emptyMap()
        }
    }
}

fun ChatItem.getSmartphoneBittelIDS() : Map<String, String>  {
    return if(this.isBittelIDSEmptyOrNull()){
        emptyMap()
    }else{
        val mutableList = mutableMapOf<String, String>()
        try {
            val contactsJSON = JSONObject(this.smartphoneBittelIDS)
            for(id in contactsJSON.keys()){
                mutableList[id] = contactsJSON.getString(id)
            }
            mutableList
        } catch (e : Exception){
            e.printStackTrace()
            emptyMap()
        }
    }
}


fun ChatItem.getBittelIDByUserID(userId : String) : String?  {
    if(this.isBittelIDSEmptyOrNull()){
        return null
    }else {
        val ids = getBittelIDS()
        if(ids.containsKey(userId)){
            return ids[userId]
        }
    }
    return null
}

fun ChatItem.getSmartphoneBittelIDByUserID(userId : String) : String?  {
    if(this.isSmartphoneBittelIDSEmptyOrNull()){
        return null
    }else {
        val ids = getSmartphoneBittelIDS()
        if(ids.containsKey(userId)){
            return ids[userId]
        }
    }
    return null
}


fun ChatItem.isChatContactsEmptyOrNull() : Boolean{
    return (this.chatContacts.isEmpty() || this.chatContacts == "{}")
}

fun ChatItem.isBittelIDSEmptyOrNull() : Boolean{
    return (this.bittelIDS.isEmpty() || this.bittelIDS == "{}")
}

fun ChatItem.isSmartphoneBittelIDSEmptyOrNull() : Boolean{
    return (this.smartphoneBittelIDS.isEmpty() || this.smartphoneBittelIDS == "{}")
}