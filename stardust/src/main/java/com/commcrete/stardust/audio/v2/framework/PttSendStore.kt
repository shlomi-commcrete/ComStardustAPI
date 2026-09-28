package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.RegisteredUserUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.commcrete.stardust.room.StardustStorage
import timber.log.Timber
import java.io.File

/**
 * Framework ring — the v2 send-side history row (legacy `PttSendManager.savePttMessage`). Persists a
 * `state=SENT` PTT row pointing at the recording's mirror WAV, so a sent PTT appears in the sender's
 * chat history. Gated by `DataManager.getSavePTTFilesRequired()` (same as legacy) — saving off, no row.
 * When saving IS on the row is written unconditionally, even if the mirror produced no file; see
 * [onFinalized]. That is a deliberate divergence from legacy, which skipped the row along with the file.
 *
 * [mirrorFile] is the single source of truth for the per-recording WAV path, shared with the
 * [WavLocalMirror] wiring so the row's path matches the file that mirror actually wrote.
 */
class PttSendStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun mirrorFile(id: RecordingId): File =
        File(StardustStorage.mediaRoot, "ptt_v2/${id.value}-mirror.wav")

    /** Called by the bridge after the recording has finalized (its mirror WAV, if any, is written). */
    fun onFinalized(id: RecordingId, chatId: String, receiverId: String, codecId: CodecId, epochTimeMs: Long) {
        if (!DataManager.getSavePTTFilesRequired()) return
        val file = mirrorFile(id)
        // The row is written even when the WAV is missing. The mirror is a self-decode of the
        // transmitted frames, so an unavailable decoder (AI models not loaded) or a recording that
        // produced no audio leaves no file — but the PTT was still sent, and dropping the row here
        // would silently erase it from the sender's own history. A row pointing at a missing path is
        // the lesser evil: the send is recorded, and playback fails visibly instead of the message
        // never existing.
        if (!file.exists()) {
            Timber.tag(TAG).w("mirror WAV missing for recording ${id.value} — saving row anyway: ${file.absolutePath}")
        }
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        val encoderType = if (codecId == CodecId.CODEC2) EncoderType.CODEC2 else EncoderType.AI
        scope.launch {
            runCatching {
                DataManager.getAppRepo().saveMessage(
                    MessageEntity(
                        chatId = chatId,
                        senderID = appId,
                        receiverID = receiverId,
                        state = MessageState.SENT,
                        epochTimeMs = epochTimeMs,
                        extraData = MessageExtraData.PTT(path = file.absolutePath, encoderType = encoderType),
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "PttV2SendStore"
    }
}
