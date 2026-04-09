package com.commcrete.stardust.room.new_db.chat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chats",
    indices = [Index(value = ["id"], unique = true)]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
    @ColumnInfo(name = "name")
    val name: String = "",
    @ColumnInfo(name = "image")
    val image: String? = null,
    @ColumnInfo(name = "type")
    val type: ChatType = ChatType.PRIVATE,
    @ColumnInfo(name = "created_at_ms")
    val createdAt: Long? = 0,
    @ColumnInfo(name = "last_updated_ms")
    val lastUpdatedAt: Long? = 0,
)
