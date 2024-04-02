package com.commcrete.stardust.stardust.model

data class StardustControlByte (val stardustPackageType: StardustPackageType,
                                var stardustDeliveryType: StardustDeliveryType,
                                var stardustAcknowledgeType: StardustAcknowledgeType,
                                var stardustPartType: StardustPartType,
                                var stardustServer: StardustServer,
                                val stardustMessageType: StardustMessageType
) {

    constructor() : this(
        StardustPackageType.DATA, StardustDeliveryType.HR, StardustAcknowledgeType.DEMAND_ACK,
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

    enum class StardustDeliveryType (val value : Int) {
        HR(0),
        LR(2)
    }

    enum class StardustAcknowledgeType (val value : Int) {
        NO_DEMAND_ACK(0),
        DEMAND_ACK(4)
    }

    enum class StardustPartType (val value : Int) {
        MESSAGE(0),
        LAST(8)
    }

    enum class StardustServer (val value : Int) {
        NOT_SERVER(0),
        SERVER(16)
    }

    enum class StardustMessageType (val value : Int) {
        REGULAR(0),
        SNIFFED(32)
    }

    fun getStardustControlByteFromByte(byte: Int): StardustControlByte {
        return StardustControlByte(
            if (byte and 1 == 1) StardustPackageType.SPEECH else StardustPackageType.DATA,
            if (byte and 2 == 2) StardustDeliveryType.LR else StardustDeliveryType.HR,
            if (byte and 4 == 4) StardustAcknowledgeType.DEMAND_ACK else StardustAcknowledgeType.NO_DEMAND_ACK,
            if (byte and 8 == 8) StardustPartType.LAST else StardustPartType.MESSAGE,
            if (byte and 16 == 16) StardustServer.SERVER else StardustServer.NOT_SERVER,
            if (byte and 32 == 32) StardustMessageType.REGULAR else StardustMessageType.REGULAR,
        )
    }



}