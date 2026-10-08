package com.commcrete.stardust.room.new_db.message

import kotlinx.serialization.Serializable

/**
 * How many packages of an outgoing multi-package message have really gone out, against how many
 * the peer needs to end up with the whole message. Written onto the row as each package goes, so
 * that whatever ends a send early — a disconnect, the send loop dying, or the process dying and
 * the startup sweep finding the row — can still say whether the message was in fact delivered to
 * the air ([MessageState.SENT]) or stopped short ([MessageState.FAILED]).
 *
 * **A package counts as sent** when the radio reports its TxEnd (or TXFinish), or when its send
 * interval runs out with the link still up — the pacing's own assumption, and the only signal on
 * firmware that does not report TxEnd per package (see `TxEndGate`). A package whose interval is
 * cut short by a disconnect does NOT count, even though it may have gone out just before the
 * link dropped and taken its TxEnd with it: an early settlement errs towards FAILED, because a
 * message reported sent that never arrived is the worse mistake.
 *
 * [partsNeeded] is not always the package count:
 *  - a text needs every part — the receiver joins them, and has no way to rebuild a missing one;
 *  - a file needs its start package (without it the receiver never learns a transfer is coming)
 *    plus any `total - spare` of the rest, because Reed-Solomon parity rebuilds up to `spare`
 *    missing packages. Must agree with `FileReceiver`'s own completeness rule.
 */
@Serializable
data class SendProgress(
    val partsSent: Int,
    val partsNeeded: Int,
) {
    val isComplete: Boolean get() = partsSent >= partsNeeded
}
