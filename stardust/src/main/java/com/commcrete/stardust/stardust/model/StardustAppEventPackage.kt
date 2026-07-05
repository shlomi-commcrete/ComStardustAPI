package com.commcrete.stardust.stardust.model

data class StardustAppEventPackage(
    var eventType: StardustAppEventType? = null,
    var xcvr: Int = 0,
    var carrier: StardustConfigurationParser.StardustCarrier? = null,
    var preset: Int? = null,
    var armDelete: Int = 0,
    var senderID: String = "",
    var deviceConnectionRssi: Int = 0,
    var signalRssi: Int = 0,
    var snr: Int = 0
) {
    enum class StardustAppEventType (val type : Int) {
        RXSuccess (0),
        RXFail (1),
        TXStart (2),
        TXFinish (3),
        TXBufferFull (4),
        PresetChange (5),
        ArmDelete (6),
        Delete (7),
        PartialEraseFinished (8),
        RxFinish (9);

        companion object {
            fun fromByte(value: Byte): StardustAppEventType? {
                return StardustAppEventType.entries.find { it.type == value.toInt() }
            }
        }
    }

    fun getCurrentPreset() : StardustConfigurationParser.CurrentPreset? {
        return preset?.let { StardustConfigurationParser.CurrentPreset.fromValue(it) }
    }

}
