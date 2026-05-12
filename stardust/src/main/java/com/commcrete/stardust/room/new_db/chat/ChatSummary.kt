package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageType

/**
 * Read-only view combining [com.commcrete.stardust.room.new_db.contact.ContactEntity] with [MessageEntity].
 * Provides everything a chat-list row needs — no extra fields on the chat entity.
 *
 * Room re-emits a new [List] from [ChatDao.getAllChatsSummaries] automatically
 * whenever any row in `chats` or `messages` changes.
 *
 * "Unseen" = messages with state [MessageState.RECEIVED] (2)
 *         or state [MessageState.RECEIVING] (4).
 *
 * State int values (stored in DB):
 *   SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5
 */
@DatabaseView(
    viewName = "chat_summary",
    value = """
        SELECT
            c.id                                                               AS chatId,
            c.type                                                             AS chatType,
            c.name                                                             AS name,
            c.image                                                            AS image,
            lm.type                                                            AS lastMessageType,
            lm.state                                                           AS lastMessageState,
            lm.extra_data                                                      AS lastMessageExtraData,
            lm.sender_id                                                       AS lastSenderId,
            COALESCE(ct.name, lm.sender_id)                                    AS lastSenderName,
            lm.epoch_time_ms                                                   AS lastMessageEpochMs,
            COALESCE(unseen.unseen_count, 0)                                   AS unseenCount
        FROM chats c
        LEFT JOIN (
            SELECT m.chat_id, m.type, m.state, m.extra_data, m.sender_id, m.epoch_time_ms
            FROM messages m
            JOIN (
                SELECT chat_id, MAX(epoch_time_ms) AS max_epoch_time_ms
                FROM messages
                WHERE state != 5
                GROUP BY chat_id
            ) latest
                ON latest.chat_id = m.chat_id
               AND latest.max_epoch_time_ms = m.epoch_time_ms
        ) lm
            ON lm.chat_id = c.id
        LEFT JOIN app_contact_user_ids u
            ON u.user_id = lm.sender_id
        LEFT JOIN app_contact_devices d
            ON d.device_id = lm.sender_id
        LEFT JOIN contacts ct
            ON ct.id = COALESCE(u.contact_id, d.contact_id)
        LEFT JOIN (
            SELECT chat_id, COUNT(*) AS unseen_count
            FROM messages
            WHERE state IN (2, 4)
            GROUP BY chat_id
        ) unseen
            ON unseen.chat_id = c.id
    """
)
data class ChatSummary(
    val chatId: String,
    @ColumnInfo(name = "chatType")
    val chatType: ChatType,
    val name: String,
    val image: String?,
    val lastMessageType: MessageType?,
    val lastMessageState: MessageState?,
    val lastMessageExtraData: MessageExtraData?,
    val lastSenderId: String?,
    val lastSenderName: String?,
    val lastMessageEpochMs: Long?,
    @ColumnInfo(name = "unseenCount")
    val unseenCount: Int,
) {
    /**
     * Compares row content while ignoring stable identity (chatId).
     * Useful for DiffUtil areCon tentsTheSame-style checks.
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
