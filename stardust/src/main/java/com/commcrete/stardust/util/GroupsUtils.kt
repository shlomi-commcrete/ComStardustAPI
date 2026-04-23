package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import com.commcrete.stardust.util.RegisteredUserUtils.mRegisterUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object GroupsUtils {

    private val groupsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getAllDeviceGroups(context: Context) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.deviceId
            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_GET_ALL_GROUPS)
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    fun addDeviceGroups(context: Context, groupId : List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.deviceId
            val intData = arrayListOf<Int>()
            for (group in groupId) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)


            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADD_GROUPS,
                    data = intData.toIntArray().toTypedArray().reversedArray())
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    fun deleteAllDeviceGroups (context: Context) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.deviceId
            val intData = arrayListOf<Int>()
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_DELETE_ALL_GROUPS,
                    data = intData.toIntArray().toTypedArray())
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    fun deleteDeviceGroups(context: Context, groupId : List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.deviceId

            val intData = arrayListOf<Int>()
            for (group in groupId) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)

            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destination = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_REMOVE_GROUPS,
                    data = intData.toIntArray().toTypedArray().reversedArray())
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    fun addGroupsToLocal(context: Context) {
        groupsScope.launch {
            val groups = DataManager.getAppRepo(context).getAllGroupIds()
            addDeviceGroups(context, groups)
        }
    }

    data class GroupContactResolution(
        val groupId: String?,
        val senderId: String
    )

    /**
     * Synchronous resolver for constructor/init contexts where suspend calls are not possible.
     * Falls back to sourceId when repository/context is not ready.
     */
    fun resolveGroupAndContactSync(sourceId: String, destinationId: String): GroupContactResolution {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                resolveGroupAndContact(sourceId = sourceId, destinationId = destinationId)
            }
        }.getOrElse {
            GroupContactResolution(groupId = null, senderId = sourceId)
        }
    }

    /**
     * Resolves groupId + real senderId from packet source/destination.
     * Rule: when source is a local group and destination is the current app user,
     * the real sender is the group itself.
     */
    suspend fun resolveGroupAndContact(sourceId: String, destinationId: String): GroupContactResolution {
        val sourceIsGroup = DataManager.getAppRepo(DataManager.context).isGroupId(sourceId)
        val destinationIsGroup = DataManager.getAppRepo(DataManager.context).isGroupId(destinationId)

        return when {
            sourceIsGroup -> resolveWhenSourceIsGroup(sourceId, destinationId)
            destinationIsGroup -> GroupContactResolution(groupId = destinationId, senderId = sourceId)
            else -> GroupContactResolution(groupId = null, senderId = sourceId)
        }
    }

    private fun resolveWhenSourceIsGroup(sourceId: String, destinationId: String): GroupContactResolution {
        val senderId = resolveSenderForSourceGroup(sourceId, destinationId)
        return GroupContactResolution(groupId = sourceId, senderId = senderId)
    }

    private fun resolveSenderForSourceGroup(sourceId: String, destinationId: String): String {
        val appId = mRegisterUser.value?.appId
        return if (appId != null && destinationId.equals(appId, ignoreCase = true)) {
            sourceId
        } else {
            destinationId
        }
    }


    suspend fun isLocalGroupId(id: String): Boolean {
        return DataManager.getAppRepo(DataManager.context).isGroupId(id)
    }

    suspend fun isLocalGroupId(ids: Collection<String?>): Boolean {
        return DataManager.getAppRepo(DataManager.context).hasAnyGroupId(ids)
    }

    fun hasLocalGroups(context: Context): Boolean = runBlocking(Dispatchers.IO) {
        DataManager.getAppRepo(context).getAllGroupIds().isNotEmpty()
    }

    fun resetLocalGroupIds(context: Context) {
        groupsScope.launch {
            // Warm the repository cache from the DB-backed source of truth.
            DataManager.getAppRepo(context).getAllGroupIds()
        }
    }
}