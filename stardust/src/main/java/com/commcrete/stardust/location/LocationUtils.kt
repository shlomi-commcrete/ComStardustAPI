package com.commcrete.stardust.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.LocationPackage
import com.commcrete.stardust.util.CoordinatesUtil
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.room.messages.MessageState
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageType
import com.commcrete.stardust.stardust.model.asString
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date
import kotlin.random.Random

@SuppressLint("StaticFieldLeak")
object LocationUtils  {

    var location : Location? = null
    private val locationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun saveDeviceLocation(
        appContext: Context,
        dataPackage: StardustPackage,
        locationPackage: LocationPackage,
        isSOS : Boolean = false
    ){
        val appId = mRegisterUser?.appId ?: return

        locationScope.launch {
            val senderId = dataPackage.getSenderAsString()
            val groupId = dataPackage.getGroupAsString()

            val message = MessageEntity(
                senderID = senderId,
                text = locationPackage.location.asString(),
                epochTimeMs = Date().time,
                type = if(isSOS) MessageType.SOS else MessageType.LOCATION,
                receiverID = appId,
                state = MessageState.RECEIVED
            )
            try {
                DataManager.getAppRepo(appContext).saveMessage(message, groupId)
            } catch (e: Exception) {
                Timber.tag("LocationUtils").e(e, "Failed to save location message")
            }

            val pollingUtils = DataManager.getPollingUtils(appContext)
            if(pollingUtils.isRunning) {
                pollingUtils.handleResponse(dataPackage)
            }

            PlayerUtils.playNotificationSound(appContext)

            DataManager.getCallbacks()?.receiveLocation(
                StardustAPIPackage(
                    senderId = senderId,
                    receiverId = appId,
                    carrier = CarriersUtils.getCarrierByControl(dataPackage.stardustControlByte.stardustDeliveryType)),
                locationPackage.location)
        }
    }

    internal fun sendMyLocation(
        appContext: Context,
        mPackage: StardustPackage,
        clientConnection: ClientConnection,
        isDemandAck : Boolean = false,
        isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1,
        opCode : StardustPackageUtils.StardustOpCode? = null,
        randomID: String = "") {

        Log.d("LocationRequest $randomID", "getLocation ${System.currentTimeMillis()}")
        val location = location
        if(location == null) {
            Log.d("LocationRequest $randomID", "send Missing ${System.currentTimeMillis()}")
            sendMissingLocation(appContext, mPackage, clientConnection, isDemandAck, isHR, opCode)
        } else {
            Log.d("LocationRequest $randomID", "send Location ${System.currentTimeMillis()}")
            sendLocation(appContext, mPackage, location, clientConnection, isDemandAck, isHR, opCode, randomID)
        }
    }

    fun getLocationForSOSMyLocation(location: Location): Array<Int> {
        return CoordinatesUtil().packLocation(location)
    }

    private fun sendMissingLocation(
        appContext: Context,
        mPackage: StardustPackage,
        clientConnection : ClientConnection,
        isDemandAck : Boolean = false,
        isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1,
        opCode : StardustPackageUtils.StardustOpCode? = null
    ) {
        // TODO: send Cant find location ToPreviousDevice
        // TODO: change xor check
        locationScope.launch {
            val dataPackage = StardustPackageUtils.getStardustPackage(
                context = appContext,
                source = mPackage.getDestAsString(),
                destination = mPackage.getSourceAsString(),
                stardustOpCode = opCode ?: mPackage.stardustOpCode,
                data =  CoordinatesUtil().packEmptyLocation()
            )

            dataPackage.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            dataPackage.stardustControlByte.stardustDeliveryType = isHR
            Log.d("LocationRequest", "send to ble ${System.currentTimeMillis()}")
            try {
                clientConnection.sendMessage(dataPackage)
            } catch (e: Exception) {
                Timber.tag("LocationUtils").e(e, "Failed to send missing location")
            }
        }
    }

    internal fun sendLocation(
        appContext: Context,
        mPackage: StardustPackage,
        location: Location,
        clientConnection : ClientConnection,
        isDemandAck : Boolean = false,
        isHR : StardustControlByte.StardustDeliveryType = StardustControlByte.StardustDeliveryType.RD1,
        opCode : StardustPackageUtils.StardustOpCode? = null,
        randomID: String = "") {

        // TODO: change xor check
        // TODO: WTF is going on???

        locationScope.launch {
            val dataPackage = StardustPackageUtils.getStardustPackage(
                context = appContext,
                source = mPackage.getDestAsString(),
                destination = mPackage.getSourceAsString(),
                stardustOpCode = opCode ?: StardustPackageUtils.StardustOpCode.RECEIVE_LOCATION,
                data = CoordinatesUtil().packLocation(location)
            )
            val id = Random.nextLong(Long.MAX_VALUE)
            dataPackage.stardustControlByte.stardustAcknowledgeType = if(isDemandAck) StardustControlByte.StardustAcknowledgeType.DEMAND_ACK else StardustControlByte.StardustAcknowledgeType.NO_DEMAND_ACK
            dataPackage.isDemandAck = isDemandAck
            dataPackage.idNumber = id
            dataPackage.stardustControlByte.stardustDeliveryType = isHR

            Log.d("LocationRequest $randomID", "send to ble ${System.currentTimeMillis()}")

            try {
                clientConnection.sendMessage(dataPackage, randomID)
            } catch (e: Exception) {
                Timber.tag("LocationUtils").e(e, "Failed to send location message to BLE")
            }

            val senderId = mPackage.getDestAsString()
            val message = MessageEntity(
                senderID = senderId,
                text = location.asString(),
                epochTimeMs = Date().time,
                type = MessageType.LOCATION,
                receiverID = mPackage.getSourceAsString(),
                state = if(UsersUtils.isRegisteredUser(senderId)) MessageState.SENT else MessageState.RECEIVED,
                isAckResponse = isDemandAck
            )
            try {
                DataManager.getAppRepo(appContext).saveMessage(message)
            } catch (e: Exception) {
                Timber.tag("LocationUtils").e(e, "Failed to save sent location message")
            }
        }
    }

}


