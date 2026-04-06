package com.commcrete.stardust.room.new_db.chat

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSummaryDao {

    /**
     * Reactive list of all chats ordered by most recent message first.
     * Room re-emits automatically on every insert / update / delete in
     * `new_chats_table` or `new_messages_table`.
     *
     * Call [AppMessagesDao.markAllAsSeen] when the user opens a chat to
     * reset that chat's [ChatSummary.unseenCount] to 0.
     */
    @Query("SELECT * FROM chat_summary ORDER BY lastMessageEpochMs DESC")
    fun getAllChats(): Flow<List<ChatSummary>>

    /** Single-chat summary — useful for a header while the user is inside a chat. */
    @Query("SELECT * FROM chat_summary WHERE chatId = :chatId LIMIT 1")
    fun getChat(chatId: String): Flow<ChatSummary?>
}
