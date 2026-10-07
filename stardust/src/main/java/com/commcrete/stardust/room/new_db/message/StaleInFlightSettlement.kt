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
 *    arrived. Once the process assembling it is gone no further part can join it — the same moment
 *    a live assembly settles (last part, or the gap timeout) — so it is RECEIVED, blob untouched.
 *  - anything else has no business being RECEIVING; settle the state and touch nothing else rather
 *    than leave a row that breathes forever.
 */
internal fun settlementForStaleInFlight(
    extraData: MessageExtraData?,
    hasPlayableAudio: (path: String) -> Boolean,
): StaleInFlightSettlement = when (extraData) {
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

    is MessageExtraData.Text -> StaleInFlightSettlement(MessageState.RECEIVED, null)

    else -> StaleInFlightSettlement(MessageState.FAILED, null)
}
