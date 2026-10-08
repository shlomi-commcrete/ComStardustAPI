package com.commcrete.stardust.room.new_db.message

import com.commcrete.stardust.util.FileReceiver

/**
 * What the startup sweep writes on one row it found still RECEIVING.
 *
 * A null [extraData] means "leave the blob alone" — the sweep's update coalesces, so a settlement
 * that has nothing to add to extra_data changes only the state.
 */
internal data class StaleInFlightSettlement(
    val state: MessageState,
    val extraData: MessageExtraData?,
)

/**
 * Decides how a row left in flight by a dead process should be settled.
 *
 * Split out of the sweep itself, and given its file check as [hasPlayableAudio] rather than
 * reaching for the filesystem, so the rule is testable as what it is: a decision about a row.
 *
 * The cases differ because what survives a crash differs:
 *  - a PTT is written through chunk by chunk, so there is usually real audio on disk. That is a
 *    RECEIVED message the user can replay, marked [MessageExtraData.PTT.truncated] because it stops
 *    mid-sentence. With no audio there is nothing to play and it is a failure.
 *  - an attachment is assembled only once every package is in, so an interrupted one has no file at
 *    all and never will: FAILED, with the reason saying the app stopped rather than the transfer
 *    going wrong, and the path cleared because nothing was ever written to it.
 *  - a multi-part text is written through part by part, so the row already holds every part that
 *    arrived — but a text is only whole once its LAST part has arrived, and a live assembly that
 *    saw LAST settles the row itself. One still RECEIVING never saw it: FAILED
 *    [FileReceiver.FileFailure.INTERRUPTED], text untouched, so the parts that did arrive stay
 *    readable. This is the same rule the live assembler applies when its gap timeout or a
 *    disconnect ends a text early — see `IncomingTextAssembler` — with that path's own reason.
 *  - anything else has no business being RECEIVING; settle the state and touch nothing else rather
 *    than leave a row that breathes forever.
 *
 * A SENDING row is settled by its [SendProgress]: SENT if every package the peer needs had
 * really gone out, FAILED if not — and FAILED when there is no progress to go by, since nothing
 * then says it went. This is the same rule the live send paths apply when a disconnect or a dead
 * send loop ends a send early, so a send reads the same whichever of them settled it. The blob is
 * kept: the text, or the local copy of the file, is what the operator would send again, and a
 * failure carries [FileReceiver.FileFailure.INTERRUPTED].
 */
internal fun settlementForStaleInFlight(
    state: MessageState?,
    extraData: MessageExtraData?,
    hasPlayableAudio: (path: String) -> Boolean,
): StaleInFlightSettlement = if (state == MessageState.SENDING) {
    settlementForInterruptedSend(extraData, FileReceiver.FileFailure.INTERRUPTED)
} else when (extraData) {
    is MessageExtraData.PTT ->
        if (hasPlayableAudio(extraData.path)) {
            StaleInFlightSettlement(MessageState.RECEIVED, extraData.copy(truncated = true))
        } else {
            StaleInFlightSettlement(MessageState.FAILED, null)
        }

    is MessageExtraData.Attachment -> StaleInFlightSettlement(
        MessageState.FAILED,
        extraData.copy(path = "", failure = FileReceiver.FileFailure.INTERRUPTED),
    )

    is MessageExtraData.Text -> StaleInFlightSettlement(
        MessageState.FAILED,
        extraData.copy(failure = FileReceiver.FileFailure.INTERRUPTED),
    )

    else -> StaleInFlightSettlement(MessageState.FAILED, null)
}

/**
 * How a SENDING row is settled when its send did not run to its own end — see
 * [settlementForStaleInFlight]. Separate so the live send paths apply the very same rule, each
 * with its own [failure]: DISCONNECTED for a lost link, ERROR for a send loop that died,
 * INTERRUPTED for the startup sweep. The reason is only written when the send did fail.
 */
internal fun settlementForInterruptedSend(
    extraData: MessageExtraData?,
    failure: FileReceiver.FileFailure,
): StaleInFlightSettlement {
    val progress = when (extraData) {
        is MessageExtraData.Text -> extraData.sendProgress
        is MessageExtraData.Attachment -> extraData.sendProgress
        else -> null
    }
    if (progress?.isComplete == true) return StaleInFlightSettlement(MessageState.SENT, null)
    return when (extraData) {
        is MessageExtraData.Attachment ->
            StaleInFlightSettlement(MessageState.FAILED, extraData.copy(failure = failure))
        is MessageExtraData.Text ->
            StaleInFlightSettlement(MessageState.FAILED, extraData.copy(failure = failure))
        else -> StaleInFlightSettlement(MessageState.FAILED, null)
    }
}
