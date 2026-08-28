package com.commcrete.stardust.audio.v2.domain

/**
 * Domain layer — the innermost clean-architecture ring.
 *
 * Pure Kotlin only: no Android, no coroutines, no PyTorch/JNI, no Room. Every
 * outer ring may depend on these types; this ring depends on nothing.
 *
 * These identity types replace stringly-typed / [Int]-typed keys scattered
 * through the legacy PTT code so the send gate and receive registry can reason
 * about "which recording" / "which stream" without touching a codec concretely.
 */

/**
 * Stable identity of a codec plugin. Registry key for R1.
 *
 * Use the [CodecId.CODEC2] / [CodecId.WAVTOKENIZER] constants rather than re-typing the string at each
 * call site: these ids are matched by equality across the send, receive and persistence paths, so a typo
 * in one literal silently breaks dispatch there instead of failing to compile.
 */
@JvmInline
value class CodecId(val value: String) {
    companion object {
        val CODEC2 = CodecId("codec2")
        val WAVTOKENIZER = CodecId("wavtokenizer")
    }
}

/** Identity of one key-down..key-up capture. Minted monotonically per recording; ordering == start order. */
@JvmInline
value class RecordingId(val value: Long)

/** Receive-side stream identity (R5). Composed by the transport adapter, e.g. `"senderId|groupId"`. */
@JvmInline
value class StreamKey(val value: String)

/**
 * Start-order token handed out by the transmit gate (R4). Reserved synchronously at capture start,
 * so [TransmitTicket.seq] order equals capture-start order equals on-the-wire order.
 */
@JvmInline
value class TransmitTicket(val seq: Long)

/** Per-recording frame index. Lets the transmit/receive paths detect gaps without a codec-specific header. */
@JvmInline
value class SequenceNumber(val value: Int)
