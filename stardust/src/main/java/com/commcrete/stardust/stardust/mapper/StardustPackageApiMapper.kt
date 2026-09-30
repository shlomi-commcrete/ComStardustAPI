package com.commcrete.stardust.stardust.mapper

import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustAcknowledgeType
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustPartType
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.RegisteredUserUtils

object StardustPackageApiMapper {
    /**
     * An OUTGOING package, addressed the way it was put on the wire: source is this device,
     * destination is whoever it was sent to.
     *
     * [toStardustAPIPackage] cannot be used for this — it is built for INCOMING packages and
     * hard-codes `receiverId` to the local app id, which for an outgoing package points the
     * wrong way round. Nothing here is nullable: a failure has to be reportable even when no
     * user is registered, which is itself one of the reasons a send fails.
     */
    fun toOutgoingStardustAPIPackage(pkg: StardustPackage): StardustAPIPackage =
        StardustAPIPackage(
            senderId = pkg.getSourceAsString(),
            receiverId = pkg.getDestAsString(),
            groupId = pkg.groupId,
            chatId = pkg.chatId,
            requireAck = pkg.stardustControlByte.stardustAcknowledgeType == StardustAcknowledgeType.DEMAND_ACK,
            carrier = CarriersUtils.getCarrierByControl(pkg.stardustControlByte.stardustDeliveryType),
            isLast = pkg.stardustControlByte.stardustPartType == StardustPartType.LAST
        )

    fun toStardustAPIPackage(pkg: StardustPackage): StardustAPIPackage? {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return null
        return StardustAPIPackage(
            senderId = pkg.senderId,
            groupId = pkg.groupId,
            chatId = pkg.chatId,
            receiverId = appId,
            requireAck = pkg.stardustControlByte.stardustAcknowledgeType == StardustAcknowledgeType.DEMAND_ACK,
            carrier = CarriersUtils.getCarrierByControl(pkg.stardustControlByte.stardustDeliveryType),
            isLast = pkg.stardustControlByte.stardustPartType == StardustPartType.LAST
        )
    }
}
