@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message

import kotlinx.serialization.Serializable

/**
 * One acknowledgement of an SOS this user sent, persisted on that SOS's own row as an
 * entry in [MessageExtraData.Sos.acks].
 *
 * An SOS is addressed to the radio's primary SOS destination, which may be a group — so
 * several people can acknowledge the same SOS, and each one is a separate entry rather
 * than a field that the next acker overwrites. Entries are appended in arrival order, so
 * the first is the first responder.
 *
 * Serialized with the row, so the field names are part of the stored format: add, never
 * rename.
 */
@Serializable
data class SosAck(
    /**
     * Who acknowledged — the id the ack packet came from, normalized the same way message
     * sender/receiver ids are. For a group SOS this is the individual member who
     * responded, NOT the group the SOS was addressed to.
     */
    val ackedBy: String,
    /** When the ack was recorded, epoch milliseconds. */
    val ackedAtMs: Long,
    /**
     * What the ack did to the SOS. Omitted while [SosAckKind.CONFIRMED], so every ack written
     * before this field existed reads back as the confirmation it was.
     */
    val kind: SosAckKind = SosAckKind.CONFIRMED,
)

/**
 * One SOS_ACK opcode carries all three: an ack from someone else confirms the SOS, and the
 * sender's own ack — from this app or from the paired radio — stands it down. Stored by name:
 * add, never rename.
 */
@Serializable
enum class SosAckKind {
    /** Someone else answered the SOS. [SosAck.ackedBy] is who. */
    CONFIRMED,

    /** This user cancelled it from the app. [SosAck.ackedBy] is this user's App ID. */
    CANCELLED,

    /** Cancelled on the paired radio itself. [SosAck.ackedBy] is its Device ID. */
    CANCELLED_FROM_DEVICE,
}
