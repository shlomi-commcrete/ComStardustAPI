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
)
