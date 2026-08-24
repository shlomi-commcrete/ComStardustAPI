package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageType

/**
 * Row model for a chat-list entry: [com.commcrete.stardust.room.new_db.contact.ContactEntity] joined
 * with the chat's latest [com.commcrete.stardust.room.new_db.message.MessageEntity] plus an unseen
 * count.
 *
 * "Unseen" = messages with state [MessageState.RECEIVED] (2).
 *
 * State int values (stored in DB):
 *   SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5
 */
data class ChatSummary(
    @ColumnInfo(name = "chatId")
    val chatId: String,
    @ColumnInfo(name = "chatType")
    val chatType: ChatType,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "image")
    val image: String?,
    @ColumnInfo(name = "lastMessageType")
    val lastMessageType: MessageType?,
    @ColumnInfo(name = "lastMessageState")
    val lastMessageState: MessageState?,
    @ColumnInfo(name = "lastMessageExtraData")
    val lastMessageExtraData: MessageExtraData?,
    @ColumnInfo(name = "lastSenderId")
    val lastSenderId: String?,
    @ColumnInfo(name = "lastSenderName")
    val lastSenderName: String?,
    @ColumnInfo(name = "lastMessageEpochMs")
    val lastMessageEpochMs: Long?,
    @ColumnInfo(name = "unseenCount")
    val unseenCount: Int,
) {
    /**
     * Compares row content while ignoring stable identity (chatId).
     * Useful for DiffUtil areContentsTheSame-style checks.
     */
    fun hasSameContent(other: ChatSummary): Boolean {
        return chatType == other.chatType &&
                name == other.name &&
                image == other.image &&
                lastMessageType == other.lastMessageType &&
                lastMessageState == other.lastMessageState &&
                lastMessageExtraData == other.lastMessageExtraData &&
                lastSenderId == other.lastSenderId &&
                lastSenderName == other.lastSenderName &&
                lastMessageEpochMs == other.lastMessageEpochMs &&
                unseenCount == other.unseenCount
    }
}