package com.commcrete.stardust.room

import androidx.room.TypeConverter
import com.commcrete.stardust.room.new_db.chat.ChatType
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


class Converters {

    class StringArrayConverter{
        @TypeConverter
        fun fromString(value: String?): Array<String?>? {
            return Gson().fromJson<Array<String?>>(value, Array<String>::class.java)
        }

        @TypeConverter
        fun toString(value: Array<String?>?): String? {
            return Gson().toJson(value)
        }
    }

    class DoubleArrayConverter{
        @TypeConverter
        fun fromDouble(value: String?): List<Double?>? {
            val listType = object : TypeToken<List<Double>>() {}.type
            return Gson().fromJson(value, listType)
        }

        @TypeConverter
        fun toDouble(value: List<Double?>?): String? {
            return Gson().toJson(value)
        }
    }

    class EnumConverter {
        @TypeConverter
        fun fromSeenStatus(status: MessageState): Int = status.id

        @TypeConverter
        fun toSeenStatus(statusId: Int): MessageState =
            MessageState.entries.first { it.id == statusId }

        @TypeConverter
        fun fromChatType(type: ChatType): String = type.name

        @TypeConverter
        fun toChatType(value: String): ChatType = ChatType.valueOf(value)

        @TypeConverter
        fun fromMessageType(type: MessageType): String = type.name

        @TypeConverter
        fun toMessageType(value: String): MessageType = MessageType.valueOf(value)

        @TypeConverter
        fun fromContactType(type: ContactType): String = type.name

        @TypeConverter
        fun toContactType(value: String): ContactType = ContactType.valueOf(value)

        @TypeConverter
        fun fromMessageExtraData(extraData: MessageExtraData?): String? {
            if (extraData == null) return null
            return json.encodeToString(extraData)
        }

        @TypeConverter
        fun toMessageExtraData(value: String?): MessageExtraData? {
            if (value.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<MessageExtraData>(value) }.getOrNull()
        }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }
        }
    }
}