package com.commcrete.stardust.room.new_db.message

/**
 * Why an outgoing package never reached its destination.
 *
 * Reported through `StardustAPICallbacks.onMessageSendFailed` and persisted on the message row
 * itself, in [MessageExtraData], so a failure survives a restart and the conversation can still
 * show which message did not go out.
 *
 * Persisted BY NAME: members may be added, never renamed or reordered, or an already-written row
 * stops parsing back.
 *
 * Every reason except [ACK_TIMEOUT] means the package was never handed to the radio at all.
 * [ACK_TIMEOUT] is the one case where it WAS transmitted and only the confirmation is missing —
 * the message may well have arrived, which is why it usually deserves different wording from the
 * rest.
 *
 * Unlike [com.commcrete.stardust.util.FileReceiver.FileFailure], these carry wording: [message]
 * for logs and diagnostics, [userMessage] for something short enough to put in front of a person.
 * An app is still free to ignore both and use its own strings — nothing in the SDK renders them.
 */
enum class SendFailureReason(
    /** Diagnostic wording: what happened, in the terms an engineer reading a log needs. */
    val message: String,
    /** Short wording for a person looking at the message that failed. */
    val userMessage: String,
) {
    /** No radio at all: neither a BLE connection nor an open USB port. */
    NOT_CONNECTED(
        message = "no transport connected — neither BLE nor USB is available",
        userMessage = "No radio connected",
    ),

    /**
     * BLE reports connected, but the characteristic writes go through is not there — services not
     * discovered yet, or a reconnect swapped the GATT out mid-send.
     */
    WRITE_CHANNEL_UNAVAILABLE(
        message = "BLE connected but the write characteristic is not available",
        userMessage = "Radio not ready",
    ),

    /** The USB port was gone, or it refused the write. */
    USB_WRITE_FAILED(
        message = "USB port unavailable or the write was rejected",
        userMessage = "Radio not ready",
    ),

    /** The GATT write kept failing to start and ran out of attempts. */
    GATT_WRITE_FAILED(
        message = "the BLE write failed on every attempt",
        userMessage = "Could not reach the radio",
    ),

    /** No registered app user, so there is no address to send from. */
    NO_APP_USER(
        message = "no app user is registered, so the package has no source address",
        userMessage = "Device not set up",
    ),

    /** The package had already been sent once and will not be sent again. */
    RETRY_LIMIT_REACHED(
        message = "retry limit reached",
        userMessage = "Could not be sent",
    ),

    /**
     * A PTT frame sat in the queue past the point where playing it would still make sense. Dropped
     * deliberately: late speech plays over the next recording and is worse than silence.
     */
    DROPPED_STALE(
        message = "dropped as stale — queued too long to still be worth sending",
        userMessage = "Too late to send",
    ),

    /**
     * Written to the radio, but the recipient never confirmed it. The only reason here that does
     * not mean the message stayed on this device.
     */
    ACK_TIMEOUT(
        message = "written to the radio but never confirmed by the recipient",
        userMessage = "Not confirmed",
    ),
}
