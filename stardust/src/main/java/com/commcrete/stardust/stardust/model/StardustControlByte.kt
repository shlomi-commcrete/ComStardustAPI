package com.commcrete.stardust.stardust.model

data class StardustControlByte (val stardustPackageType: StardustPackageType,
                                var stardustDeliveryType: StardustDeliveryType,
                                var stardustAcknowledgeType: StardustAcknowledgeType,
                                var stardustPartType: StardustPartType,
                                var stardustServer: StardustServer,
                                val stardustMessageType: StardustMessageType
) {

    constructor() : this(
        StardustPackageType.DATA, StardustDeliveryType.RD1, StardustAcknowledgeType.DEMAND_ACK,
        StardustPartType.MESSAGE, StardustServer.NOT_SERVER, StardustMessageType.REGULAR
    )


    fun getControlByteValue (): Int {
        return this.stardustPackageType.value + this.stardustDeliveryType.value +
                this.stardustAcknowledgeType.value + this.stardustPartType.value + this.stardustServer.value +
                this.stardustMessageType.value

    }

    enum class StardustPackageType (val value : Int) {
        DATA(0),
        SPEECH(1)
    }

    enum class StardustDeliveryType (val value : Int, val radioName : String) {
        RD1(0, "RD1"),
        RD2(2, "RD2"),
        RD3(4, "RD3"),
        RD4(6, "RD4"),
    }

    enum class StardustAcknowledgeType (val value : Int) {
        NO_DEMAND_ACK(0),
        DEMAND_ACK(8)
    }

    enum class StardustPartType (val value : Int) {
        MESSAGE(0),
        LAST(16)
    }

    enum class StardustServer (val value : Int) {
        NOT_SERVER(0),
        SERVER(32)
    }

    enum class StardustMessageType (val value : Int) {
        REGULAR(0),
        SNIFFED(64)
    }

    fun getStardustControlByteFromByte(byte: Int): StardustControlByte {
        return StardustControlByte(
            if (byte and 1 == 1) StardustPackageType.SPEECH else StardustPackageType.DATA,
            when((byte and 2) + (byte and 4)) {
                0 -> {StardustDeliveryType.RD1}
                2 -> {StardustDeliveryType.RD2}
                4 -> {StardustDeliveryType.RD3}
                6 -> {StardustDeliveryType.RD4}
                else -> {StardustDeliveryType.RD1}
            },
            if (byte and 8 == 8) StardustAcknowledgeType.DEMAND_ACK else StardustAcknowledgeType.NO_DEMAND_ACK,
            if (byte and 16 == 16) StardustPartType.LAST else StardustPartType.MESSAGE,
            if (byte and 32 == 32) StardustServer.SERVER else StardustServer.NOT_SERVER,
            if (byte and 64 == 64) StardustMessageType.REGULAR else StardustMessageType.REGULAR,
        )
    }



}