package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.room.chats.ChatItem
import java.util.Locale

@Entity(
    tableName = "new_chats_table",
    indices = [Index(value = ["chat_id"], unique = true)]
)
data class AppChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "chat_id")
    var chatId: String,
    @ColumnInfo(name = "last_message_id")
    val lastMessageId: String = "",
    @ColumnInfo(name = "chat_name")
    val name: String = "",
    @ColumnInfo(name = "audio_received")
    var isAudioReceived: Boolean = false,
    @ColumnInfo(name = "enable_background_ptt")
    var enableBackgroundPtt: Boolean = true,
    @ColumnInfo(name = "isSniffer")
    var isSniffer: Boolean = false,
    val chatContacts: String = "",
    var bittelIDS: String = "",
    val smartphoneBittelIDS: String = "",
    val numOfUnseenMessages: Int = 0,
    @ColumnInfo(name = "is_group")
    val isGroup: Boolean = false,
    @ColumnInfo(name = "is_bittel")
    val isBittel: Boolean = false,
    @ColumnInfo(name = "image_name")
    val imageName: String? = "",
    @Embedded var message: Message? = null,
    @Embedded var user: User? = null,
) {
    init {
        chatId = chatId.lowercase(Locale.ROOT)
        bittelIDS = bittelIDS.lowercase(Locale.ROOT)
    }
}

fun ChatItem.toAppChatEntity(): AppChatEntity = AppChatEntity(
    id = id,
    chatId = chat_id,
    lastMessageId = lastMessageId,
    name = name,
    isAudioReceived = isAudioReceived,
    enableBackgroundPtt = enableBackgroundPtt,
    isSniffer = isSniffer,
    chatContacts = chatContacts,
    bittelIDS = bittelIDS,
    smartphoneBittelIDS = smartphoneBittelIDS,
    numOfUnseenMessages = numOfUnseenMessages,
    isGroup = isGroup,
    isBittel = isBittel,
    imageName = imageName,
    message = message,
    user = user,
)

fun AppChatEntity.toLegacyChatItem(): ChatItem = ChatItem(
    id = id,
    chat_id = chatId,
    lastMessageId = lastMessageId,
    name = name,
    isAudioReceived = isAudioReceived,
    enableBackgroundPtt = enableBackgroundPtt,
    isSniffer = isSniffer,
    chatContacts = chatContacts,
    bittelIDS = bittelIDS,
    smartphoneBittelIDS = smartphoneBittelIDS,
    numOfUnseenMessages = numOfUnseenMessages,
    isGroup = isGroup,
    isBittel = isBittel,
    imageName = imageName,
    message = message,
    user = user,
)

