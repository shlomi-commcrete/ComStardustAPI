package com.commcrete.stardust.stardust.model

class StardustAppEventParser : StardustParser(){
    companion object{
        const val eventTypeLength = 1
        const val eventDataLength = 1
        const val eventRssiTotalLength = 12
        const val eventRssiLength = 3
        const val eventRssiSingleLength = 1
        const val eventSnrSingleLength = 1
        const val eventRssiSignalLength = 1
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
                    null -> {}
                }
                val rssiDataBytes = cutByteArray(byteArray,
                    eventRssiTotalLength, offset)
                val rssiList : MutableList<StardustAppEventPackage.RSSIPackage> = mutableListOf()
                for (i in 0 until 4) {
                    val rssiXcvrDataBytes = cutByteArray(rssiDataBytes,
                        eventRssiLength, offset)
                    rssiList.add(parseXcvrRssi(rssiXcvrDataBytes))
                    offset+= eventRssiLength
                }
                bittelAppEventPackage.listRSSIPackage = rssiList
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

    private fun parseXcvrRssi (byteArray: ByteArray) : StardustAppEventPackage.RSSIPackage {
        val rssiPackage = StardustAppEventPackage.RSSIPackage()
        var offset = 0
        try {
            val eventRssiSingleBytes = cutByteArray(byteArray,
                eventRssiSingleLength, offset)
            rssiPackage.rssi = byteArrayToInt(eventRssiSingleBytes.reversedArray())
            offset += eventRssiSingleLength
            val eventSnrSingleBytes = cutByteArray(byteArray,
                eventSnrSingleLength, offset)
            rssiPackage.snr = byteArrayToInt(eventSnrSingleBytes.reversedArray())
            offset += eventSnrSingleLength
            val eventRssiSignalSingleBytes = cutByteArray(byteArray,
                eventRssiSignalLength, offset)
            rssiPackage.signalRssi = byteArrayToInt(eventRssiSignalSingleBytes.reversedArray())
            offset += eventRssiSignalLength
        }catch (e : Exception) {
            e.printStackTrace()
        }
        return rssiPackage
    }
}