package com.commcrete.stardust.util.audio

import android.content.Context
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.ustadmobile.codec2.Codec2Encoder
import kotlin.random.Random

/**
 * Single source of truth for the **CODEC2 → BLE packet** pipeline.
 *
 * Both the live recording path ([AudioRecorderCodec2]) and the test feeder
 * ([AudioFeederEngine]) feed 8 kHz mono PCM into this class and let it
 * handle the rest:
 *
 * ```
 * 8 kHz PCM
 *   ─► Codec2Encoder (MODE700C, 320-sample frame → 4 bytes)
 *   ─► pack two 4-byte frames into a 7-byte chunk
 *   ─► accumulate 7-byte chunks into 77-byte BLE packets
 *   ─► randomize trailing bytes if they collide with [SUFFIX]
 *   ─► DataManager.sendDataToBle(SEND_PTT)
 * ```
 *
 * Before this class existed `WavRecorder` and `AudioFeederEngine` each
 * had their own copy of the encode + pack + send chain. The two were
 * drifting (slightly different masking, different `isLast` semantics on
 * the final packet, different timeout-enforcement hooks) which made it
 * impossible to claim that "audio fed by the feeder takes the same path
 * as live audio" — exactly the property test feeds need to be useful.
 *
 * State (sample remainder, pending half-pair, packet buffer) is fully
 * encapsulated; [reset] wipes it for a fresh PTT key-down. [finish]
 * flushes any half-filled packet with the `isLast` control bit set so
 * the receiver knows the stream has ended.
 *
 * Thread safety: not safe for concurrent calls. Each instance is meant
 * to be driven from a single audio thread (the recording capture loop
 * or the feeder's chunk callback).
 */
class Codec2SendPipeline(
    private val context: Context,
    /**
     * Resolves the current [Carrier] at send time. Defaults to whatever
     * the caller passed in, but the live path may want to look this up
     * dynamically per-frame from `viewModel`.
     */
    private val carrier: Carrier?,
    /**
     * Local source / device ID to put in the [StardustPackageUtils]
     * source field. Lambda so both static (feeder uses
     * `DataManager.getSource()`) and dynamic (live uses
     * `viewModel.getSource()`) callers can plug in.
     */
    private val sourceProvider: () -> String = { DataManager.getSource() },
    /**
     * Destination device ID, evaluated per-packet. Returning `null`
     * disables transmission entirely — the encoder still runs (so the
     * pipeline drains cleanly via [finish]) but no BLE packet is sent.
     * Used by the test feeder for artifact-only runs.
     */
    private val destinationProvider: () -> String?,
    /**
     * Optional hook invoked once per **transmitted** 77-byte packet —
     * used by [AudioRecorderCodec2] to enforce its max-PTT-timeout. Not invoked
     * for skipped packets (when [destinationProvider] returns null).
     */
    private val onPacketSent: (() -> Unit)? = null,
    /**
     * Optional hook invoked once per encoded **codec2 frame** (4 bytes,
     * 40 ms of 8 kHz mono PCM). Fires AFTER the frame is added to the
     * pack pipeline so callers can keep a side-channel — e.g. the live
     * recorder runs the encoded bytes back through `Codec2Decoder` and
     * appends the result to a local `.wav` file for playback verification.
     * Receives a defensive copy; modifying it has no effect on what gets
     * transmitted.
     */
    private val onEncodedFrame: ((ByteArray) -> Unit)? = null,
) {

    private val codec2Encoder = Codec2Encoder(RecorderUtils.CodecValues.MODE700.mode)
    private val sampleRemainder = ArrayList<Short>()
    private var pending4Bytes: ByteArray? = null
    private val packetBuffer = ArrayList<Byte>()

    /**
     * Push 8 kHz mono PCM into the pipeline. Any whole 320-sample frames
     * are encoded immediately; remaining samples are buffered until the
     * next call (or [finish]).
     */
    fun enqueuePcm(pcm8k: ShortArray) {
        if (pcm8k.isEmpty()) return
        sampleRemainder.addAll(pcm8k.asList())
        while (sampleRemainder.size >= CODEC2_FRAME_SAMPLES) {
            val frame = ShortArray(CODEC2_FRAME_SAMPLES)
            for (i in 0 until CODEC2_FRAME_SAMPLES) frame[i] = sampleRemainder[i]
            repeat(CODEC2_FRAME_SAMPLES) { sampleRemainder.removeAt(0) }
            pushCodec2Frame(frame)
        }
    }

    /**
     * Flush any half-filled state: zero-pad an incomplete codec2 frame
     * if needed, pair the half-pair with a zero second frame, then send
     * whatever remains in the packet buffer marked as the **last**
     * packet of the PTT stream. Idempotent — calling [finish] a second
     * time is a no-op.
     */
    fun finish() {
        if (sampleRemainder.isEmpty() && pending4Bytes == null && packetBuffer.isEmpty()) return
        if (sampleRemainder.isNotEmpty()) {
            val padded = ShortArray(CODEC2_FRAME_SAMPLES)
            for (i in sampleRemainder.indices) padded[i] = sampleRemainder[i]
            sampleRemainder.clear()
            pushCodec2Frame(padded)
        }
        pending4Bytes?.let {
            pushPacked7(packTwoCodec2Frames(it, ByteArray(CODEC2_FRAME_BYTES)))
            pending4Bytes = null
        }
        if (packetBuffer.isNotEmpty()) {
            sendPacket(packetBuffer.toByteArray(), isLast = true)
            packetBuffer.clear()
        }
    }

    /**
     * Wipe all pipeline state so the next [enqueuePcm] starts a fresh
     * PTT stream. Equivalent to constructing a new instance.
     */
    fun reset() {
        sampleRemainder.clear()
        pending4Bytes = null
        packetBuffer.clear()
    }

    // ──────────────────────────────────────────────────────────────────────

    private fun pushCodec2Frame(frame: ShortArray) {
        val chars = CharArray(RecorderUtils.CodecValues.MODE700.charNumOutput)
        codec2Encoder.encode(frame, chars)
        val bytes = ByteArray(chars.size)
        chars.forEachIndexed { index, c -> bytes[index] = c.code.toByte() }
        // Defensive copy so the side-channel listener can't mutate what
        // we're about to pack into the 7-byte chunk for transmission.
        onEncodedFrame?.invoke(bytes.copyOf())
        val pending = pending4Bytes
        if (pending == null) {
            pending4Bytes = bytes
        } else {
            pushPacked7(packTwoCodec2Frames(pending, bytes))
            pending4Bytes = null
        }
    }

    private fun pushPacked7(bytes7: ByteArray) {
        bytes7.forEach { packetBuffer.add(it) }
        while (packetBuffer.size >= PTT_PACKET_BYTES) {
            val payload = packetBuffer.take(PTT_PACKET_BYTES).toByteArray()
            repeat(PTT_PACKET_BYTES) { packetBuffer.removeAt(0) }
            sendPacket(payload, isLast = false)
        }
    }

    private fun sendPacket(payload: ByteArray, isLast: Boolean) {
        // Artifact-only / no-destination runs: still account for the
        // packet conceptually (encoder + pack pipeline kept draining)
        // but skip the BLE write entirely.
        val dest = destinationProvider() ?: return
        val radio = CarriersUtils.getRadioToSend(carrier, functionalityType = FunctionalityType.PTT) ?: return
        val audioIntArray = StardustPackageUtils.byteArrayToIntArray(payload)
        if (audioIntArray.endsWith(SUFFIX)) {
            // Trailing tail of the encoded stream collided with our
            // start-of-text marker — randomize the last two bytes so the
            // receiver doesn't treat this as a control sentinel.
            val num = Random.nextInt(0, 41)
            audioIntArray[audioIntArray.lastIndex] = num
            audioIntArray[audioIntArray.lastIndex - 1] = num
        }
        val pkg = StardustPackageUtils.getStardustPackage(
            source = sourceProvider(),
            destination = dest,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT,
            data = audioIntArray,
        )
        pkg.stardustControlByte.stardustPartType = if (isLast) {
            StardustControlByte.StardustPartType.LAST
        } else {
            StardustControlByte.StardustPartType.MESSAGE
        }
        pkg.stardustControlByte.stardustDeliveryType = radio.second
        pkg.checkXor = StardustPackageUtils.getCheckXor(pkg.getStardustPackageToCheckXor())
        DataManager.sendDataToBle(pkg)
        onPacketSent?.invoke()
    }

    /**
     * Pack two 4-byte CODEC2 mode-700C frames (28 bits each + 4 unused
     * low bits) into a 7-byte (56-bit) frame. The high nibble of `a[3]`
     * is preserved (it carries frame A's tail bits); `b` is shifted into
     * the freed nibble of `a[3]` and the following 3 bytes.
     */
    private fun packTwoCodec2Frames(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(7)
        out[0] = a[0]
        out[1] = a[1]
        out[2] = a[2]
        out[3] = ((a[3].toInt() and 0xF0) or ((b[0].toInt() and 0xFF) ushr 4)).toByte()
        out[4] = (((b[0].toInt() and 0xFF) shl 4) or ((b[1].toInt() and 0xFF) ushr 4)).toByte()
        out[5] = (((b[1].toInt() and 0xFF) shl 4) or ((b[2].toInt() and 0xFF) ushr 4)).toByte()
        out[6] = (((b[2].toInt() and 0xFF) shl 4) or ((b[3].toInt() and 0xFF) ushr 4)).toByte()
        return out
    }

    companion object {
        /** Sample rate the [Codec2Encoder] consumes — feeder resamples to this. */
        const val TARGET_SAMPLE_RATE = 8_000

        /** Codec2 mode 700C operates on 320-sample (40 ms) frames. */
        private const val CODEC2_FRAME_SAMPLES = 320

        /** A single mode-700C frame is 28 bits packed into 4 bytes. */
        private const val CODEC2_FRAME_BYTES = 4

        /** BLE packet payload size (matches `WavRecorder.appendToArray` sentinel). */
        private const val PTT_PACKET_BYTES = 77

        /**
         * Tail-collision sentinel that's randomized in [sendPacket]. Same
         * sequence used by the legacy `WavRecorder.suffix`, kept identical
         * here so live and feeder packets are bit-for-bit comparable.
         */
        private val SUFFIX = arrayOf(-50, -10, -128, -4, -17, 104, 0, 0)
    }
}



