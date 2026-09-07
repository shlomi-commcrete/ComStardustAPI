package com.commcrete.stardust.room.new_db.message

enum class MessageState(val id: Int) {
    SENT(0),
    SEEN(1),
    RECEIVED(2),
    FAILED(3),
    RECEIVING(4),
    ARCHIVED(5),

    /**
     * The user stopped an outgoing file/image transfer before it finished. NOT a
     * failure: nothing went wrong, so the row carries no failure reason — it carries
     * [MessageExtraData.Attachment.cancellation], which says how far the send had got.
     *
     * Ids are persisted, so members may be added but never renumbered.
     */
    CANCELLED(6),
}

