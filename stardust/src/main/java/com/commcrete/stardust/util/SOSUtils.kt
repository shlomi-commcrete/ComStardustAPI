package com.commcrete.stardust.util

import android.content.Context
import android.location.Location
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.ui.graphics.vector.ImageVector
import com.commcrete.stardust.R
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.location.LocationUtils
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.StardustPackageUtils
import kotlinx.coroutines.launch
import java.util.Date

object SOSUtils {

    //added location from user
    fun sendSos (type : Int, text : String ? = null, context: Context, location: Location, stardustAPIPackage: StardustAPIPackage) {
        DataManager.getClientConnection(context).let {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let { appId ->
                val sosString = "SOS"
                val sosBytes = sosString.toByteArray()
                var data : Array<Int> = arrayOf()
                data = data.plus(if(text.isNullOrEmpty()) 12 else (12+text.length))
                data = data.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes))
                data = data.plus(type)
                data = data.plus(LocationUtils.getLocationForSOSMyLocation(location))
                text?.let {
                    data = data.plus(StardustPackageUtils.byteArrayToIntArray(it.toByteArray()))
                }
                val radio = CarriersUtils.getRadioToSend(functionalityType =  FunctionalityType.SOS)
                val sosMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = stardustAPIPackage.destination, stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_MESSAGE,
                    data = data)
                sosMessage.stardustControlByte.stardustDeliveryType = radio.second
                it.addMessageToQueue(sosMessage)
                saveSOSSent(context, type,stardustAPIPackage, location)
            }
        }
    }

    fun ackSOS (context: Context, stardustAPIPackage: StardustAPIPackage) {
        DataManager.getClientConnection(context).let {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let { appId ->
                val radio = CarriersUtils.getRadioToSend(functionalityType =  FunctionalityType.SOS)
                val sosMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = stardustAPIPackage.destination, stardustOpCode = StardustPackageUtils.StardustOpCode.SOS_ACK)
                sosMessage.stardustControlByte.stardustDeliveryType = radio.second
                it.addMessageToQueue(sosMessage)
            }
        }
    }

    fun sendSos (context: Context, location: Location, stardustAPIPackage: StardustAPIPackage) {
        DataManager.getClientConnection(context).let {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let { appId ->
                var data : Array<Int> = arrayOf()
                data = data.plus(LocationUtils.getLocationForSOSMyLocation(location))
                val radio = CarriersUtils.getRadioToSend(functionalityType =  FunctionalityType.SOS)
                val sosMessage = StardustPackageUtils.getStardustPackage(
                    source = appId , destenation = stardustAPIPackage.destination, stardustOpCode = StardustPackageUtils.StardustOpCode.SOS,
                    data = data)
                sosMessage.stardustControlByte.stardustDeliveryType = radio.second
                it.addMessageToQueue(sosMessage)
                saveSOSSent(context, 0 ,stardustAPIPackage, location)

            }
        }
    }

    enum class SOS_TYPES (val type : Int, val sosName : String,val image : Int, val imageVector: ImageVector) {
        VEHICLE (0, "Vehicle Issues", R.drawable.sos_vehicle_truck, Icons.Filled.LocalFireDepartment),
        FIRE (1, "Fire", R.drawable.sos_fire, Icons.Filled.LocalFireDepartment ),
        LOST (2, "Lost Or Trapped", R.drawable.sos_lost, Icons.Filled.LocalFireDepartment),
        HEALTH (3, "Health Or Injury", R.drawable.sos_injury, Icons.Filled.LocalFireDepartment),
        CRIME (4, "Crime", R.drawable.sos_crime, Icons.Filled.LocalFireDepartment),
        CUSTOM (5, "Custom", R.drawable.sos_crime, Icons.Filled.LocalFireDepartment)
    }

    enum class SOS_TYPES_ARMY (val type : Int, val sosName : String,val image : Int, val imageVector: ImageVector) {
        HOSTILE (10, "Hostile", R.drawable.hostile, Icons.Filled.LocalFireDepartment), //SKULL? 147/148 Arma
        MAN_DOWN (11, "Man Down", R.drawable.medical, Icons.Filled.LocalFireDepartment ), //MEDIC 175 Arma
        LOST (12, "M.I.A", R.drawable.mia, Icons.Filled.LocalFireDepartment), // Question mark 145/146 Arma
        REINFORCEMENT (13, "Need Reinforcement", R.drawable.sos_lost, Icons.Filled.LocalFireDepartment) // Hand 207
    }

    fun saveSOSSent (
        context: Context,
        type: Int,
        stardustAPIPackage: StardustAPIPackage,
        location: Location
    ) {
        val textName = when (type) {
            SOS_TYPES_ARMY.HOSTILE.type -> {
                SOS_TYPES_ARMY.HOSTILE.sosName }
            SOS_TYPES_ARMY.MAN_DOWN.type -> {
                SOS_TYPES_ARMY.MAN_DOWN.sosName}
            SOS_TYPES_ARMY.LOST.type -> {
                SOS_TYPES_ARMY.LOST.sosName}
            else -> {
                "S.O.S"}
        }
//        val text = "$textName Sent"
        val text = "latitude : ${location.latitude}\n" +
                "longitude : ${location.longitude}\naltitude : ${location.altitude}"
        val messageItem = MessageItem(chatId = stardustAPIPackage.destination, text = text, epochTimeMs = Date().time, senderID = stardustAPIPackage.source,
            isSOS = true, sosType = type)
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = DataManager.getChatsRepo(context)
            val messagesRepository = DataManager.getMessagesRepo(context)
            Scopes.getDefaultCoroutine().launch{
                val chatItem = chatsRepo.getChatByBittelID(stardustAPIPackage.destination)
                var textChat = "Reporting $textName"
                if(type != 0) {
                    textChat = "$textChat Event"
                }
                chatItem?.message = Message(
                    senderID = stardustAPIPackage.source,
                    text = textChat,
                    seen = false
                )
                chatItem?.let { chatsRepo.addChat(it) }
                messagesRepository.addContact(messageItem)
                val numOfUnread = chatItem?.numOfUnseenMessages ?: 0
                chatsRepo.updateNumOfUnseenMessages(stardustAPIPackage.source, numOfUnread+1)

            }
        }
    }

    private fun getSOSDest (context: Context) : List<String> {
        val destList = mutableListOf<String>()
        val selectedUserMain = SharedPreferencesUtil.getSelectedSOSMain(context)
        val selectedUserSub = SharedPreferencesUtil.getSelectedSOSSub(context)
        if(selectedUserMain.isNotEmpty()) {
            destList.add(selectedUserMain)
        }
        if(selectedUserSub.isNotEmpty()) {
            destList.add(selectedUserSub)
        }
        if(destList.isEmpty()){
            destList.add("00000002")
        }
        return destList
    }
}