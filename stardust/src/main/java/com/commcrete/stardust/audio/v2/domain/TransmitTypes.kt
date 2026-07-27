package com.commcrete.stardust.audio.v2.domain

/**
 * Domain layer — control signals shared by the send gate and the codec ports.
 * Pure Kotlin; no framework dependencies.
 */

/**
 * When does a frame "count as transmitted" for the R4 ordering gate?
 *
 * - [ACK_TRACKED]  — classic CODEC2 (opcode 0x15): a frame is committed only once its link ACK
 *                    arrives (bounded retransmit, then resolve-with-loss so the gate can never hang).
 * - [FIRE_AND_FORGET] — WavTokenizer (opcode 0x3A): a frame is committed once handed to the
 *                    single-writer FIFO transport queue.
 */
enum class TransmitSemantics { ACK_TRACKED, FIRE_AND_FORGET }

/**
 * Why a recording's outbound buffer sealed. The transmit gate advances the head recording when it
 * reaches [LAST] **or** on any of the failure reasons — never only on the happy path — which is what
 * keeps a crashed/hung recording N from stalling recording N+1 forever (R4).
 */
enum class TerminalReason { LAST, ERROR, TIMEOUT, CANCELLED }
