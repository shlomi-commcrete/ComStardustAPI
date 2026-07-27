package com.commcrete.stardust.audio.v2.framework

import com.commcrete.stardust.audio.v2.application.port.MessageStore
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.audio.v2.domain.StreamKey
import com.commcrete.stardust.audio.v2.domain.TerminalReason

/**
 * Framework ring — the default no-persistence [MessageStore]. Swap for a RoomMessageStore (wrapping
 * `DataManager.getAppRepo().saveMessage(MessageExtraData.PTT(...))`) when wiring persistence.
 */
object NoOpMessageStore : MessageStore {
    override suspend fun onRecordingStarted(id: RecordingId, codecId: CodecId, peer: StreamKey) = Unit
    override suspend fun onRecordingFinalized(id: RecordingId, frames: Int, reason: TerminalReason) = Unit
    override suspend fun onStreamReceived(key: StreamKey, codecId: CodecId, frames: Int) = Unit
}
