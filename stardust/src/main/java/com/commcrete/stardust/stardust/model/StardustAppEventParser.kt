package com.commcrete.stardust.stardust.model

class StardustAppEventParser : StardustParser(){
    companion object{
        const val eventTypeLength = 1
        const val eventDataLength = 1
        const val senderIDLength = 4
        const val rssiLength = 1
    }


    fun parseAppEvent (bittelPackage: StardustPackage) : StardustAppEventPackage {
        val bittelAppEventPackage = StardustAppEventPackage()
        try {
            bittelPackage.data?.let { intArray ->
                val byteArray = intArrayToByteArray(intArray.toMutableList())
                var offset = 0
                val eventTypeBytes = cutByteArray(byteArray,
                    eventTypeLength, offset)
                bittelAppEventPackage.eventType = StardustAppEventPackage.StardustAppEventType.fromByte(eventTypeBytes.get(0))
                offset+= eventTypeLength
                val eventDataBytes = cutByteArray(byteArray,
                    eventDataLength, offset)
                offset+= eventDataLength
                when (bittelAppEventPackage.eventType) {
                    StardustAppEventPackage.StardustAppEventType.RXSuccess -> parseXcvr(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.RXFail -> parseXcvr(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.TXStart -> parseXcvr(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.TXFinish -> parseXcvr(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.TXBufferFull -> parseXcvr(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.PresetChange -> parsePreset(eventDataBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.ArmDelete -> parseDelete(eventTypeBytes, bittelAppEventPackage)
                    StardustAppEventPackage.StardustAppEventType.Delete -> parseDelete(eventTypeBytes, bittelAppEventPackage)
                    null -> {}
                }

                when (bittelAppEventPackage.eventType) {
                    StardustAppEventPackage.StardustAppEventType.RXSuccess -> {
                        val appIDBytes = cutByteArray(byteArray,
                            senderIDLength, offset)
                        parseIDSender(appIDBytes, bittelAppEventPackage)
                        val rssi = cutByteArray(byteArray,
                            rssiLength, offset)
                        parseRSSI(rssi, bittelAppEventPackage)
                    }
                    StardustAppEventPackage.StardustAppEventType.RXFail -> {}
                    StardustAppEventPackage.StardustAppEventType.TXStart -> {}
                    StardustAppEventPackage.StardustAppEventType.TXFinish -> {}
                    StardustAppEventPackage.StardustAppEventType.TXBufferFull -> {}
                    StardustAppEventPackage.StardustAppEventType.PresetChange -> {}
                    StardustAppEventPackage.StardustAppEventType.ArmDelete -> {
                        val appIDBytes = cutByteArray(byteArray,
                            senderIDLength, offset)
                        parseIDSender(appIDBytes, bittelAppEventPackage)
                    }
                    StardustAppEventPackage.StardustAppEventType.Delete -> {
                        val appIDBytes = cutByteArray(byteArray,
                            senderIDLength, offset)
                        parseIDSender(appIDBytes, bittelAppEventPackage)
                    }
                    null -> {}
                }
            }
        }catch ( e : Exception) {
            e.printStackTrace()
        }
        return bittelAppEventPackage
    }

    private fun parseXcvr (byteArray: ByteArray, bittelAppEventPackage: StardustAppEventPackage){
        bittelAppEventPackage.xcvr = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parsePreset (byteArray: ByteArray, bittelAppEventPackage: StardustAppEventPackage){
        bittelAppEventPackage.preset = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseDelete (byteArray: ByteArray, bittelAppEventPackage: StardustAppEventPackage) {
        bittelAppEventPackage.armDelete = byteArrayToInt(byteArray.reversedArray())
    }

    private fun parseIDSender (byteArray: ByteArray, bittelAppEventPackage: StardustAppEventPackage) {
        bittelAppEventPackage.senderID = byteArray.reversedArray().toHex().substring(0,8)
    }

    private fun parseRSSI (byteArray: ByteArray, bittelAppEventPackage: StardustAppEventPackage) {
        bittelAppEventPackage.rssi = (byteArrayToInt(byteArray.reversedArray()).times(-1))

    }
}