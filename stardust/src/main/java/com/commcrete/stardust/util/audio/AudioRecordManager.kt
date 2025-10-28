package com.commcrete.stardust.util.audio
import android.media.AudioRecord
import java.util.concurrent.CopyOnWriteArrayList

object AudioRecordManager {

    private val activeRecords = CopyOnWriteArrayList<AudioRecord>()

    fun register(record: AudioRecord) {
        activeRecords.add(record)
    }

    fun unregister(record: AudioRecord) {
        activeRecords.remove(record)
    }

    fun stopAll() {
        for (record in activeRecords) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        activeRecords.clear()
    }
}