package com.commcrete.stardust.util


import android.location.Location
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.ui.graphics.vector.ImageVector
import com.commcrete.stardust.R
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.SosType
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte

object SOSUtils {

    suspend fun sendAlert(
        sosType: SOS_REPORT_TYPES?,
        text: String ? = null,
        location: Location,
        stardustAPIPackage: StardustAPIPackage) {

        val sosString = "SOS"
        val sosBytes = sosString.toByteArray()
        var data : Array<Int> = arrayOf()
        data = data.plus(if(text.isNullOrEmpty()) 12 else (12 + text.length))
        data = data.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes))
        data = data.plus(sosType?.type ?: 0)
        data = data.plus(LocationUtils.getLocationForSOSMyLocation(location))
        text?.let {
            data = data.plus(StardustPackageUtils.byteArrayToIntArray(it.toByteArray()))
        }
        val radio = CarriersUtils.getRadioToSend(functionalityType = FunctionalityType.REPORTS) ?: return

        val sosMessage = StardustPackageUtils.getStardustPackage(
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_MESSAGE,
            data = data)

        sosMessage.stardustControlByte.stardustDeliveryType = radio.second
        sosMessage.stardustControlByte.stardustAcknowledgeType = StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
        DataManager.getClientConnection().addMessageToQueue(sosMessage)
        saveSOSMessage(sosType, stardustAPIPackage, location)
    }

    fun ackSOS(stardustAPIPackage: StardustAPIPackage) {
        val sosMessage = StardustPackageUtils.getStardustPackage(
            source = stardustAPIPackage.receiverId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SOS_ACK)
        DataManager.getClientConnection().addMessageToQueue(sosMessage)
    }

    fun updateSosDestinations(destinationId: String) {
        val appId = RegisteredUserUtils.mRegisterUser.value?.appId ?: return
        val deviceId = RegisteredUserUtils.mRegisterUser.value?.deviceId ?: return
        val sosXCVR = ConfigurationUtils.bittelConfiguration.value?.sosXCVR ?: return

        val sosMessage = StardustPackageUtils.getStardustPackage(
            data = buildUpdateSosDestinationPayload(sosXCVR, destinationId),
            source = appId,
            destination = deviceId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.UPDATE_SOS_DESTINATION)
        DataManager.getClientConnection().addMessageToQueue(sosMessage)
        DataManager.getClientConnection().saveConfiguration()
    }

    private fun buildUpdateSosDestinationPayload(sosXCVR: Int, id: String): Array<Int> {
        val parsedDestination = StardustPackageUtils.hexStringToByteArray(id)
        return arrayListOf<Int>().apply {
            add(sosXCVR)
            repeat(2) { addAll(parsedDestination) }
        }.toIntArray().toTypedArray()
    }

    suspend fun sendSos(location: Location) {
        var data : Array<Int> = arrayOf()

        data = data.plus(LocationUtils.getLocationForSOSMyLocation(location))

        val user = RegisteredUserUtils.mRegisterUser.value ?: return
        val appId = user.appId ?: return
        val deviceId = user.deviceId ?: return

        val sosMessage = StardustPackageUtils.getStardustPackage(
            source = appId,
            destination = deviceId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SOS,
            data = data)

        DataManager.getClientConnection().addMessageToQueue(sosMessage)

        val realSOSDest = ConfigurationUtils.bittelConfiguration.value?.sosDestinations?.firstNotNullOfOrNull { it }
        val sosPackage = StardustAPIPackage(
            senderId = appId,
            receiverId = realSOSDest ?: deviceId,
            requireAck = true,
            isLast = true
        )

        saveSOSMessage(null , sosPackage, location)
    }

    suspend fun saveSOSMessage (
        type: SOS_REPORT_TYPES?,
        stardustAPIPackage: StardustAPIPackage,
        location: Location,
        state: MessageState = MessageState.SENT
    ) {

        DataManager.getAppRepo().saveMessage(
            pkg = stardustAPIPackage,
            state = state,
            extraData = MessageExtraData.Sos(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                subtype = type?.toSosType()
            )
        )
    }

    enum class SOS_TYPES (val type : Int, val sosName : String,val image : Int, val imageVector: ImageVector) {
        VEHICLE (0, "Vehicle Issues", R.drawable.sos_vehicle_truck, Icons.Filled.LocalFireDepartment),
        FIRE (1, "Fire", R.drawable.sos_fire, Icons.Filled.LocalFireDepartment ),
        LOST (2, "Lost Or Trapped", R.drawable.sos_lost, Icons.Filled.LocalFireDepartment),
        HEALTH (3, "Health Or Injury", R.drawable.sos_injury, Icons.Filled.LocalFireDepartment),
        CRIME (4, "Crime", R.drawable.sos_crime, Icons.Filled.LocalFireDepartment),
        CUSTOM (5, "Custom", R.drawable.sos_crime, Icons.Filled.LocalFireDepartment)
    }

    enum class SOS_REPORT_TYPES(
        val type: Int,
        val sosName: String,
        val image: Int,
        val imageVector: ImageVector,
    ) {
        HOSTILE (10, "Hostile", R.drawable.hostile, Icons.Filled.LocalFireDepartment),
        MAN_DOWN (11,  "Man Down", R.drawable.medical, Icons.Filled.LocalFireDepartment),
        LOST (12, "M.I.A", R.drawable.mia, Icons.Filled.LocalFireDepartment),
        REINFORCEMENT (13, "Need Reinforcement", R.drawable.sos_lost, Icons.Filled.LocalFireDepartment);


        fun toSosType(): SosType = when(this) {
            HOSTILE -> SosType.HOSTILE
            MAN_DOWN -> SosType.MAN_DOWN
            LOST -> SosType.MIA
            REINFORCEMENT -> SosType.REINFORCEMENT
        }

        companion object {
            fun fromCode(code: Int): SOS_REPORT_TYPES? =
                entries.firstOrNull { it.type == code }

            fun toReportType(type: SosType): SOS_REPORT_TYPES = when(type) {
                SosType.HOSTILE -> HOSTILE
                SosType.MAN_DOWN -> MAN_DOWN
                SosType.MIA -> LOST
                SosType.REINFORCEMENT -> REINFORCEMENT
            }

        }
    }

}