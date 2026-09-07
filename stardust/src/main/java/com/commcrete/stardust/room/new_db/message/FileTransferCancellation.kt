@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message

import kotlinx.serialization.Serializable

/**
 * How far an outgoing file/image transfer had got when the user stopped it, persisted on
 * the message row as [MessageExtraData.Attachment.cancellation] alongside
 * [MessageState.CANCELLED].
 *
 * A transfer is split into data packages carrying the file itself plus `spare`
 * Reed-Solomon parity packages. The receiver rebuilds the file from the data packages,
 * repairing up to `spare` missing ones from the parity it received — so a cancel that
 * landed once the parity packages had started going out may still leave the receiver
 * with a complete file, while one that landed earlier cannot. That is what [phase]
 * answers.
 *
 * Counts are of packages actually queued to the radio; a package that found no radio to
 * go out on is not counted as sent.
 *
 * Serialized with the row, so field names and enum names are part of the stored format:
 * add, never rename.
 */
@Serializable
data class FileTransferCancellation(
    /** Where the cancel landed relative to the parity packages. */
    val phase: CancelPhase,
    /** Data packages sent before the cancel, out of [dataPackages]. */
    val dataPackagesSent: Int,
    /** Parity packages sent before the cancel, out of [sparePackages]. */
    val sparePackagesSent: Int,
    /** Data packages this transfer was split into. */
    val dataPackages: Int,
    /** Parity packages this transfer carried; 0 when it carried none. */
    val sparePackages: Int,
) {
    /** Packages sent before the cancel, data and parity together. */
    val packagesSent: Int get() = dataPackagesSent + sparePackagesSent

    /** Packages this transfer was split into, data and parity together. */
    val totalPackages: Int get() = dataPackages + sparePackages

    companion object {
        /**
         * Classifies a cancel from the counters alone. Pure and separate from the sender
         * so the phase boundaries are testable.
         */
        fun of(
            dataPackagesSent: Int,
            sparePackagesSent: Int,
            dataPackages: Int,
            sparePackages: Int,
        ): FileTransferCancellation {
            val total = dataPackages.coerceAtLeast(0) + sparePackages.coerceAtLeast(0)
            val dataSent = dataPackagesSent.coerceIn(0, dataPackages.coerceAtLeast(0))
            val spareSent = sparePackagesSent.coerceIn(0, sparePackages.coerceAtLeast(0))

            val phase = when {
                total == 0 || dataSent + spareSent == 0 -> CancelPhase.NOT_STARTED
                dataSent + spareSent >= total -> CancelPhase.AFTER_ALL_SENT
                spareSent == 0 -> CancelPhase.BEFORE_SPARE
                else -> CancelPhase.DURING_SPARE
            }
            return FileTransferCancellation(
                phase = phase,
                dataPackagesSent = dataSent,
                sparePackagesSent = spareSent,
                dataPackages = dataPackages.coerceAtLeast(0),
                sparePackages = sparePackages.coerceAtLeast(0),
            )
        }
    }
}

/**
 * Where a cancel landed in the send. The distinction is not cosmetic: it is what says
 * whether the receiver could still end up with the file.
 */
enum class CancelPhase {
    /** Nothing had gone out yet. The receiver has, at most, the transfer's start package. */
    NOT_STARTED,

    /**
     * Packages were still going out and NO parity package had been sent yet. The
     * receiver is missing part of the file and has nothing to repair it with, so the
     * file cannot arrive.
     */
    BEFORE_SPARE,

    /**
     * Parity packages had started going out when the cancel landed, so the receiver may
     * have had enough to rebuild the file: it can legitimately end up with the file even
     * though the sender stopped. Compare [FileTransferCancellation.sparePackagesSent]
     * with what was still missing to judge how likely that is.
     */
    DURING_SPARE,

    /**
     * The cancel arrived after the last package had already been queued. Nothing was
     * held back; the transfer was effectively complete.
     */
    AFTER_ALL_SENT,
}
