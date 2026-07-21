@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.commcrete.stardust.room.new_db.message

import com.commcrete.stardust.room.new_db.contact.FullContactData
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Lightweight, display-oriented description of an attachment, cached once when the
 * message is persisted (see [MessageExtraData.Attachment.fileSummary]) so the
 * conversation UI can render a rich summary without re-reading/parsing the file on
 * every list bind.
 *
 * [title] and [size] are generic to any attachment. [extraData] carries
 * attachment-type-specific summary data — e.g. [SharedContactSummary] for a
 * shared-contact CSV. Add a new [FileSummaryExtra] subtype to support a summary
 * for a future attachment kind.
 */
@Serializable
data class FileSummary(
    val title: String,
    val size: Long,
    val extraData: FileSummaryExtra? = null,
)

/**
 * Base for the attachment-type-specific payload carried by [FileSummary.extraData].
 * kotlinx serializes this polymorphically via the [SerialName] on each subtype.
 */
@Serializable
sealed class FileSummaryExtra

/**
 * Summary of the contacts carried by a shared-contact CSV attachment.
 *
 * [totalCount] is the true number of contacts in the file; [contacts] may be a
 * capped subset for display (equal to [totalCount] when not capped). The entries are
 * full [FullContactData], so the message bubble and the "Add contact(s)" flow both
 * read from the summary directly — no re-parse of the file needed.
 */
@Serializable
@SerialName("SharedContacts")
data class SharedContactSummary(
    val totalCount: Int,
    val contacts: List<FullContactData>,
) : FileSummaryExtra()
