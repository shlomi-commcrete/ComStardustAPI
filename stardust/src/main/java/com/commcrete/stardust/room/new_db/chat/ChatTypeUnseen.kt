package com.commcrete.stardust.room.new_db.chat


import com.commcrete.stardust.room.new_db.message.MessageType

/**
 * Unseen count of split-out messages of one [messageType] in one chat.
 *
 * "Split" message types are those the chats list surfaces as their own indicator (e.g. new
 * locations) instead of folding into the last-message text / unread badge. Which types are split
 * is a runtime decision (see the plugin's `SharedPreferencesUtil.observeLocationSplitEnabled()`),
 * so this is delivered live alongside the chat summaries.
 */
data class ChatTypeUnseen(
    val chatId: String,
    val messageType: MessageType,
    val count: Int,
)