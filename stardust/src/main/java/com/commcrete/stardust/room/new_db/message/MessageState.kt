package com.commcrete.stardust.room.new_db.message

enum class MessageState(val id: Int) {
    SENT(0),
    SEEN(1),
    RECEIVED(2),
    FAILED(3),
    RECEIVING(4),
    ARCHIVED(5),
}

