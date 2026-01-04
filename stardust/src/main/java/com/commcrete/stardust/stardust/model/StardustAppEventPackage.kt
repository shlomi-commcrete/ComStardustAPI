package com.commcrete.stardust.stardust.model

data class StardustAppEventPackage(
    var eventType : StardustAppEventType? = null,
    var xcvr : Int = 0,
    var carrier : StardustConfigurationParser.StardustCarrier? = null,
    var preset : Int? = null,
    var listRSSIPackage: List<RSSIPackage> = mutableListOf(),
    var armDelete : Int = 0,
    var senderID : String = "",
    var rssi : Int = 0
) {
    enum class StardustAppEventType (val type : Int) {
        RXSuccess (0),
        RXFail (1),
        TXStart (2),
        TXFinish (3),
        TXBufferFull (4),
        PresetChange (5),
        ArmDelete (6),
        Delete (7);
        companion object {
            fun fromByte(value: Byte): StardustAppEventType? {
                return values().find { it.type == value.toInt() }
            }
        }
    }

    fun getCurrentPreset () : StardustConfigurationParser.CurrentPreset? {
        when (preset) {
            0 -> {return StardustConfigurationParser.CurrentPreset.PRESET1}
            1 -> {return StardustConfigurationParser.CurrentPreset.PRESET2}
            2 -> {return StardustConfigurationParser.CurrentPreset.PRESET3}
        }
        return null
    }

    data class RSSIPackage (
        var rssi : Int = 0,
        var snr : Int = 0,
        var signalRssi : Int = 0
    )
}
