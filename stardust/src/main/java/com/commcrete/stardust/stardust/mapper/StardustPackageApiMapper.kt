package com.commcrete.stardust.stardust.mapper

import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustAcknowledgeType
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustPartType
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.RegisteredUserUtils

object StardustPackageApiMapper {
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
