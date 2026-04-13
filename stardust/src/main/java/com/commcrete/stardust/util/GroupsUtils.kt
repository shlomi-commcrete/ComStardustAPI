package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
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
            val dst = it.bittelId
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
            val dst = it.bittelId
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
            val dst = it.bittelId
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
            val dst = it.bittelId

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
     * Resolves groupId + real senderId from packet source/destination.
     * Rule: when source is a local group and destination is the current app user,
     * the real sender is the group itself.
     */
    fun resolveGroupAndContact(sourceId: String, destinationId: String): GroupContactResolution {
        val groupCheckResults = isLocalGroup(listOf(sourceId, destinationId))

        return when {
            groupCheckResults[sourceId] == true -> resolveWhenSourceIsGroup(sourceId, destinationId)
            groupCheckResults[destinationId] == true -> GroupContactResolution(groupId = destinationId, senderId = sourceId)
            else -> GroupContactResolution(groupId = null, senderId = sourceId)
        }
    }

    private fun resolveWhenSourceIsGroup(sourceId: String, destinationId: String): GroupContactResolution {
        val senderId = resolveSenderForSourceGroup(sourceId, destinationId)
        return GroupContactResolution(groupId = sourceId, senderId = senderId)
    }

    private fun resolveSenderForSourceGroup(sourceId: String, destinationId: String): String {
        val appId = mRegisterUser?.appId
        return if (appId != null && destinationId.equals(appId, ignoreCase = true)) {
            sourceId
        } else {
            destinationId
        }
    }


    fun isLocalGroup(id: String?): Boolean {
        val normalizedId = id?.trim()?.lowercase() ?: return false
        return runBlocking(Dispatchers.IO) {
            DataManager.getAppRepo(DataManager.context)
                .getAllGroupIds()
                .any { it.equals(normalizedId, ignoreCase = true) }
        }
    }

    /**
     * Checks multiple IDs in a single DB call.
     * @return map of each id to whether it is a local group.
     */
    private fun isLocalGroup(ids: Collection<String?>): Map<String?, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val localGroups = runBlocking(Dispatchers.IO) {
            DataManager.getAppRepo(DataManager.context)
                .getAllGroupIds()
                .mapTo(HashSet()) { it.trim().lowercase() }
        }
        return ids.associateWith { id ->
            id?.trim()?.lowercase()?.let { it in localGroups } ?: false
        }
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