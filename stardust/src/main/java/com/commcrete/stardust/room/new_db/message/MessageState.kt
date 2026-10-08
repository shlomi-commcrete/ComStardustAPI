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

    /**
     * An outgoing multi-part text whose parts are still going out — the send-side twin of
     * [RECEIVING]. Settled to [SENT] when the last part's TxEnd arrives or its send interval
     * runs out, to [FAILED] if the send loop dies, and by the startup sweep if the process
     * died first. A text that fits one package never takes this state: it is saved [SENT].
     *
     * Old SDK builds read id 7 as null (see Converters), so a downgrade shows the row with no
     * state rather than crashing.
     */
    SENDING(7),
}

