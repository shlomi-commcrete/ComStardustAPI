package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import kotlinx.coroutines.launch
import timber.log.Timber

object GroupsUtils {

    fun deleteAllGroups (context: Context) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            val intData = arrayListOf<Int>()
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_DELETE_ALL_GROUPS,
                    data = intData.toIntArray().toTypedArray())
                clientConnection?.addMessageToQueue(deletePackage)
            }
        }
    }

    fun getAllGroups (context: Context) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_GET_ALL_GROUPS)
                clientConnection?.addMessageToQueue(deletePackage)
            }
        }
    }

    fun addGroups (context: Context, groupId : List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            val intData = arrayListOf<Int>()
            for (group in groupId) {
                Timber.tag("added group").d(group)
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)


            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADD_GROUPS,
                    data = intData.toIntArray().toTypedArray().reversedArray())
                clientConnection?.addMessageToQueue(deletePackage)
            }
        }
    }

    fun deleteGroups (context: Context, groupId : List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId

            val intData = arrayListOf<Int>()
            for (group in groupId) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)

            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_REMOVE_GROUPS
                            ,data = intData.toIntArray().toTypedArray().reversedArray())
                clientConnection?.addMessageToQueue(deletePackage)
            }
        }
    }

    fun addAllGroups (context: Context) {
        Scopes.getDefaultCoroutine().launch {
            val groupsList = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao()).getAllGroupIds()
            addGroups(context, groupsList)
        }
    }

    fun isGroup (context: Context, id : String, onGroupCallback : (Boolean) -> Unit) {
        Scopes.getDefaultCoroutine().launch {
            val user = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao()).getChatByBittelID(id)
            val isGroup = user?.isGroup ?: false
            onGroupCallback(isGroup)
        }
    }

    suspend fun isGroup (context: Context, id : String) : Boolean {
        val user = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao()).getChatByBittelID(id)
        return user?.isGroup ?: false
    }
}