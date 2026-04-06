package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import com.commcrete.stardust.room.messages.SeenStatus

/**
 * Read-only view combining [ContactEntity] with [AppMessageEntity].
 * Provides everything a chat-list row needs — no extra fields on the chat entity.
 *
 * Room re-emits a new [List] from [ChatSummaryDao.getAllChats] automatically
 * whenever any row in `new_chats_table` or `messages` changes.
 *
 * "Unseen" = messages with state [SeenStatus.RECEIVED] (2)
 *         or state [SeenStatus.RECEIVING] (4).
 *
 * State int values (stored in DB):
 *   SENT=0, SEEN=1, RECEIVED=2, FAILED=3, RECEIVING=4, ARCHIVED=5
 */
@DatabaseView(
    viewName = "chat_summary",
    value = """
        SELECT
            c.id                                                               AS chatId,
            c.name                                                             AS name,
            c.image                                                            AS image,
            (SELECT m.text
             FROM   messages m
             WHERE  m.chat_id = c.id AND m.state != 5
             ORDER  BY m.epoch_time_ms DESC LIMIT 1)                           AS lastMessageText,
            (SELECT m.sender_name
             FROM   messages m
             WHERE  m.chat_id = c.id AND m.state != 5
             ORDER  BY m.epoch_time_ms DESC LIMIT 1)                           AS lastSenderName,
            (SELECT m.epoch_time_ms
             FROM   messages m
             WHERE  m.chat_id = c.id AND m.state != 5
             ORDER  BY m.epoch_time_ms DESC LIMIT 1)                           AS lastMessageEpochMs,
            (SELECT COUNT(*)
             FROM   messages m
             WHERE  m.chat_id = c.id AND m.state IN (2, 4))               AS unseenCount
        FROM chats c
    """
)
data class ChatSummary(
    val chatId: String,
    val name: String,
    val image: String?,
    val lastMessageText: String?,
    val lastSenderName: String?,
    val lastMessageEpochMs: Long?,
    @ColumnInfo(name = "unseenCount")
    val unseenCount: Int,
)
