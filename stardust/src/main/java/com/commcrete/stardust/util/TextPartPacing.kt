package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.config.Bandwidth
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.config.TextPacketAirtime

/**
 * Timing of the parts of a long text, per carrier. The sender waits up to [sendIntervalMs] after
 * each part (less when the radio reports TxEnd sooner, see [TxEndGate]); the receiver
 * settles a message that has not seen its LAST part after [continuationWindowMs] without a
 * further part. Both derive from [TextPacketAirtime], so the receiver's patience can never fall
 * below the sender's spacing.
 */
internal object TextPartPacing {

    /** How many send intervals of silence after a part end a message that never got LAST. */
    private const val CONTINUATION_INTERVALS = 10

    /** An unknown carrier type (no carrier list yet) is treated as the slowest, LR. */
    fun sendIntervalMs(type: CarrierType?, bandwidth: Bandwidth?): Long =
        TextPacketAirtime.ms(type ?: CarrierType.LR, bandwidth)

    /** Interval on [carrier], at the bandwidth the device last reported for it. */
    fun sendIntervalMs(carrier: Carrier?): Long =
        sendIntervalMs(carrier?.type, carrier?.let(ConfigurationUtils::bandwidthFor))

    /**
     * How long after its latest part a message without LAST stays RECEIVING and open to the next
     * part. Over the air parts arrive slower than they are sent (HR parts sent 800 ms apart were
     * seen arriving 2-3 s apart), hence the wide margin; the bound is what keeps a message whose
     * LAST was lost from absorbing the sender's next message.
     *
     * An unknown carrier gets the slow spacing: waiting too long only delays settling the row,
     * while waiting too little splits one message into several.
     */
    fun continuationWindowMs(type: CarrierType?, bandwidth: Bandwidth?): Long =
        sendIntervalMs(type, bandwidth) * CONTINUATION_INTERVALS

    fun continuationWindowMs(carrier: Carrier?): Long =
        sendIntervalMs(carrier) * CONTINUATION_INTERVALS
}
