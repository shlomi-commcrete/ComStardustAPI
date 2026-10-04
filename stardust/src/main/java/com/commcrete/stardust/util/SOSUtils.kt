package com.commcrete.stardust.util


import android.location.Location
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.ui.graphics.vector.ImageVector
import com.commcrete.stardust.R
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.room.new_db.message.SosType
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.StardustControlByte.StardustDeliveryType
import kotlinx.coroutines.CancellationException
import timber.log.Timber

object SOSUtils {

    private const val LOG_TAG = "SOSUtils"

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

        sosMessage.stardustControlByte.stardustDeliveryType = radio.deliveryType
        sosMessage.stardustControlByte.stardustAcknowledgeType = StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
        DataManager.getClientConnection().addMessageToQueue(sosMessage)
        saveSOSMessage(sosType, stardustAPIPackage, location)
    }

    fun ackSOS(stardustAPIPackage: StardustAPIPackage) {
        val sosMessage = StardustPackageUtils.getStardustPackage(
            source = stardustAPIPackage.senderId,
            destination = stardustAPIPackage.receiverId,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SOS_ACK)
            .apply {
                // No carrier: fall back to the radio's configured SOS transceiver.
                val deliveryType = stardustAPIPackage.carrier?.deliveryType
                    ?: ConfigurationUtils.bittelConfiguration.value?.sosXCVR
                        ?.let { StardustDeliveryType.entries.getOrNull(it) }
                deliveryType?.let { stardustControlByte.stardustDeliveryType = it }
            }
        DataManager.getClientConnection().addMessageToQueue(sosMessage)
    }

    fun updateSosDestinations(destinationId: String) {
        val (src, dst) = requireLocalSrcDst() ?: return

        val sosXCVR = ConfigurationUtils.bittelConfiguration.value?.sosXCVR ?: return

        val sosMessage = StardustPackageUtils.getStardustPackage(
            data = buildUpdateSosDestinationPayload(sosXCVR, destinationId),
            source = src,
            destination = dst,
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
        val (src, dst) = requireLocalSrcDst() ?: return

        var data : Array<Int> = arrayOf()

        data = data.plus(LocationUtils.getLocationForSOSMyLocation(location))


        val sosMessage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SOS,
            data = data)
        try {
            buildSosMessagePackage(src)?.let { saveSOSMessage(null, it, location) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SOS could not be saved; sending it anyway", e)
        }

        DataManager.getClientConnection().addMessageToQueue(sosMessage)
    }

    /**
     * The package an outgoing SOS is FILED under, which is not how it is addressed on air.
     * On air an SOS goes to the local radio ([requireLocalSrcDst]'s destination), which
     * forwards it to the SOS destination configured on the device; in the database it has
     * to appear in the conversation with whoever will receive it, or it lands in a chat
     * with this user's own device and nobody ever sees it.
     *
     * Returns null when no SOS destination is configured, in which case the SOS is sent but
     * not recorded — there is no conversation to record it in.
     */
    private suspend fun buildSosMessagePackage(senderId: String): StardustAPIPackage? {
        val destination = resolvePrimarySosDestination()
        if (destination == null) {
            Log.w(LOG_TAG, "SOS sent but not saved: no SOS destination in the configuration")
            return null
        }

        val groupId = destination.takeIf { GroupsUtils.isLocalGroupId(it) }

        return StardustAPIPackage(
            senderId = senderId,
            receiverId = destination,
            groupId = groupId,
            requireAck = true,
            isLast = true
        )
    }

    /**
     * The radio's primary (first) SOS destination, or null when none is configured.
     *
     * The configuration always carries exactly two fixed-width destination slots, and an
     * unused slot reads back as a run of 0s or Fs rather than as absent — so an empty slot
     * has to be recognised by its value, not by nullability. Falls back to the last
     * destinations seen, which covers an SOS raised before the first configuration read of
     * this session has come back.
     */
    private fun resolvePrimarySosDestination(): String? {
        val configured = ConfigurationUtils.bittelConfiguration.value?.sosDestinations
        return configured?.firstOrNull { isRealDestination(it) }
            ?: SharedPreferencesUtil.getLastSosDestinations().firstOrNull { isRealDestination(it) }
    }

    /** False for a blank id and for the all-0 / all-F placeholders of an unused slot. */
    internal fun isRealDestination(id: String?): Boolean {
        val normalized = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return normalized.any { it != '0' } && normalized.any { it != 'f' }
    }

    suspend fun saveSOSMessage (
        type: SOS_REPORT_TYPES?,
        stardustAPIPackage: StardustAPIPackage,
        location: Location,
        state: MessageState = MessageState.SENT
    ): Long? {

        return DataManager.getAppRepo().saveMessage(
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