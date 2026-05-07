package com.commcrete.stardust.util

import android.content.Context
import android.util.Log
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import kotlinx.coroutines.launch
import timber.log.Timber

object GroupsUtils {

    private val groupsLock = Any()
    private val groupsIds: MutableList<String> = mutableListOf()


    fun sendDeleteAllGroups (context: Context) {
        synchronized(groupsLock) { groupsIds.clear() }
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
                    destenation = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_DELETE_ALL_GROUPS,
                    data = intData.toIntArray().toTypedArray())
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    private fun addGroups (context: Context, groupsList : List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        val user = SharedPreferencesUtil.getAppUser(context) ?: return
        val src = user.appId ?: return
        val dst = user.bittelId ?: return
        val intData = arrayListOf<Int>()

        if(groupsList.isNotEmpty()) {
            for (group in groupsList) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
        } else { intData.addAll(listOf(0, 0, 0, 0)) }

        intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)


        val pkg = StardustPackageUtils.getStardustPackage(
            context = context,
            source = src,
            destenation = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADD_GROUPS,
            data = intData.toIntArray().toTypedArray().reversedArray())

        clientConnection.addMessageToQueue(pkg)
        Timber.tag("InitHandler").d("Sent ADD_GROUPS")
    }


    fun sendAddAllGroups(context: Context, sendIfEmpty: Boolean = true) {
        Scopes.getDefaultCoroutine().launch {
            // Snapshot the cache atomically; if empty, refresh from DB and snapshot that result.
            val snapshot = synchronized(groupsLock) { groupsIds.toList() }
                .ifEmpty { resetLocalCacheGroupIds(context) }
            if(!sendIfEmpty && snapshot.isEmpty()) return@launch
            addGroups(context, snapshot)
        }
    }


    fun isGroup (id : String?) : Boolean {
        val cleaned = id?.trim()?.lowercase() ?: return false

        return synchronized(groupsLock) {
            groupsIds.any { it.equals(cleaned, ignoreCase = true) }
        }
    }

    fun clearCacheData() {
        synchronized(groupsLock) { groupsIds.clear() }
    }

    fun addGroupIds(data: List<String>) {
        synchronized(groupsLock) {
            groupsIds.addAll(data)
            Log.i("Groups", "Added group IDs to local cache: ${data.joinToString(", ")}")
        }
    }

    fun resetLocalCacheGroupIds(data: List<String>) {
        synchronized(groupsLock) {
            groupsIds.clear()
            groupsIds.addAll(data)
            Log.i("Groups", "Reset local cache group IDs")
        }
    }

    suspend fun resetLocalCacheGroupIds(context: Context): List<String> {
        val groups = DataManager.getChatsRepo(context).getAllGroupIds()
        resetLocalCacheGroupIds(groups)
        return groups
    }
}