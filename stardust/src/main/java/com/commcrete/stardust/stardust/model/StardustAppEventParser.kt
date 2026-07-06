package com.commcrete.stardust.stardust.model

import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil


class StardustAppEventParser : StardustParser(){

    fun parseAppEvent(dataPackage: StardustPackage) : StardustAppEventPackage {
        val deviceEventPackage = StardustAppEventPackage()
        try {
            dataPackage.data?.let { intArray ->
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                val eventParts = parseEventData(byteArray, deviceEventPackage)

                parsePrimaryEventData(eventParts, deviceEventPackage)
                parseAdditionalEventData(eventParts.eventType, byteArray, eventParts.offset, deviceEventPackage)
            }
        } catch ( e : Exception) {
            e.printStackTrace()
        }
        return deviceEventPackage
    }

    private fun parseEventData(
        byteArray: ByteArray,
        deviceEventPackage: StardustAppEventPackage
    ): EventParts {
        var offset = 0

        val eventTypeBytes = cutByteArray(byteArray, EVENT_TYPE_LENGTH, offset)
        deviceEventPackage.eventType = StardustAppEventPackage.StardustAppEventType.fromByte(eventTypeBytes[0])
        offset += EVENT_TYPE_LENGTH

        val eventDataBytes = cutByteArray(byteArray, EVENT_DATA_LENGTH, offset)
        offset += EVENT_DATA_LENGTH

        return EventParts(deviceEventPackage.eventType, eventTypeBytes, eventDataBytes, offset)
    }

    private fun parsePrimaryEventData(
        eventParts: EventParts,
        deviceEventPackage: StardustAppEventPackage
    ) {
        when (eventParts.eventType) {
            StardustAppEventPackage.StardustAppEventType.RXSuccess,
            StardustAppEventPackage.StardustAppEventType.RXFail,
            StardustAppEventPackage.StardustAppEventType.TXStart,
            StardustAppEventPackage.StardustAppEventType.TXFinish,
            StardustAppEventPackage.StardustAppEventType.TXBufferFull,
            StardustAppEventPackage.StardustAppEventType.RxFinish -> parseXcvr(eventParts.eventDataBytes, deviceEventPackage)

            StardustAppEventPackage.StardustAppEventType.PresetChange -> parsePreset(eventParts.eventDataBytes, deviceEventPackage)

            StardustAppEventPackage.StardustAppEventType.ArmDelete,
            StardustAppEventPackage.StardustAppEventType.Delete,
            StardustAppEventPackage.StardustAppEventType.PartialEraseFinished -> parseDelete(eventParts.eventTypeBytes, deviceEventPackage)
            null -> {}
        }
    }

    private fun parseAdditionalEventData(
        eventType: StardustAppEventPackage.StardustAppEventType?,
        byteArray: ByteArray,
        offset: Int,
        deviceEventPackage: StardustAppEventPackage
    ) {
        val appIDBytes = cutByteArray(byteArray, SENDER_ID_LENGTH, offset)
        parseIDSender(appIDBytes, deviceEventPackage)
        val rssiReportSource = SharedPreferencesUtil.getRSSIReportSource(DataManager.context)
        if(deviceEventPackage.senderID.equals(rssiReportSource, true)) {
            var newOffset = offset + SENDER_ID_LENGTH
            val rssi = cutByteArray(byteArray, RSSI_LENGTH, newOffset)
            parseRSSI(rssi, deviceEventPackage)
            newOffset = newOffset + RSSI_LENGTH
            val snr = cutByteArray(byteArray, SNR_LENGTH, newOffset)
            parseSnr(snr, deviceEventPackage)

            newOffset = newOffset + SNR_LENGTH
            val signalRssi = cutByteArray(byteArray, SIGNAL_RSSI_LENGTH, newOffset)
            parseSignalRssi(signalRssi, deviceEventPackage)
        }
    }

    private fun parseSignalRssi(
        byteArray: ByteArray,
        deviceEventPackage: StardustAppEventPackage
    ) {
        deviceEventPackage.signalRssi = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseSnr(
        byteArray: ByteArray,
        deviceEventPackage: StardustAppEventPackage
    ) {
        deviceEventPackage.snr = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseXcvr (byteArray: ByteArray, deviceEventPackage: StardustAppEventPackage){
        deviceEventPackage.xcvr = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parsePreset (byteArray: ByteArray, deviceEventPackage: StardustAppEventPackage){
        deviceEventPackage.preset = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseDelete (byteArray: ByteArray, deviceEventPackage: StardustAppEventPackage) {
        deviceEventPackage.armDelete = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseIDSender (byteArray: ByteArray, deviceEventPackage: StardustAppEventPackage) {
        deviceEventPackage.senderID = byteArray.reversedArray().toHex().substring(0, 8)
    }

    private fun parseRSSI (byteArray: ByteArray, deviceEventPackage: StardustAppEventPackage) {
        deviceEventPackage.deviceConnectionRssi = (byteArrayToInt(byteArray.reversedArray()).times(-1))
    }

    private class EventParts(
        val eventType: StardustAppEventPackage.StardustAppEventType?,
        val eventTypeBytes: ByteArray,
        val eventDataBytes: ByteArray,
        val offset: Int
    )

    companion object {
        private const val EVENT_TYPE_LENGTH = 1
        private const val EVENT_DATA_LENGTH = 1
        private const val SENDER_ID_LENGTH = 4
        private const val RSSI_LENGTH = 1
        private const val SNR_LENGTH = 1
        private const val SIGNAL_RSSI_LENGTH = 1
    }
}