package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.StardustOpCode
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object GroupsUtils {

    private val groupsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Endpoints(val src: String, val dst: String)

    private fun endpoints(): Endpoints? {
        val (src, dst) = requireLocalSrcDst() ?: return null
        return Endpoints(src, dst)
    }

    /** Single-byte payload that carries only the SMARTPHONE address marker. */
    private fun smartphoneMarker(): Array<Int> =
        arrayOf(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)

    /**
     * Encodes a list of group ids into the wire format expected by the device:
     * each id reversed, followed by the SMARTPHONE marker, finally the whole
     * payload reversed (big/little endian fix-up).
     *
     * @param padIfEmpty when true, an empty list is padded with `[0,0,0,0]`
     *                   (used by the local `REQUEST_ADD_GROUPS` flow).
     */
    private fun encodeGroupsPayload(groupIds: List<String>, padIfEmpty: Boolean): Array<Int> {
        val intData = arrayListOf<Int>()
        if (groupIds.isNotEmpty()) {
            for (group in groupIds) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
        } else if (padIfEmpty) {
            intData.addAll(listOf(0, 0, 0, 0))
        }
        intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
        return intData.toIntArray().toTypedArray().reversedArray()
    }

    /** Builds and enqueues a Stardust package. No-op when no endpoints are available. */
    private fun sendOp(
        opCode: StardustOpCode,
        data: Array<Int>? = null
    ) {
        val (src, dst) = endpoints() ?: return
        val pkg = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = opCode,
            data = data
        )
        DataManager.getClientConnection().addMessageToQueue(pkg)
    }

    // ---------------------------------------------------------------------
    //  Public BLE API
    // ---------------------------------------------------------------------

    fun sendDeleteAllGroups() = deleteAllDeviceGroups()

    fun deleteAllDeviceGroups() {
        sendOp(StardustOpCode.REQUEST_DELETE_ALL_GROUPS, smartphoneMarker())
    }

    fun getAllDeviceGroups() {
        sendOp(StardustOpCode.REQUEST_GET_ALL_GROUPS)
    }

    fun addDeviceGroups(groupId: List<String>) {
        sendOp(
            StardustOpCode.REQUEST_ADD_GROUPS,
            encodeGroupsPayload(groupId, padIfEmpty = false)
        )
    }

    fun deleteDeviceGroups(groupId: List<String>) {
        sendOp(
            StardustOpCode.REQUEST_REMOVE_GROUPS,
            encodeGroupsPayload(groupId, padIfEmpty = false)
        )
    }

    /**
     * Loads all locally-stored group ids and sends them to the device using
     * `REQUEST_ADD_GROUPS`. The two public flows below differ only in:
     *   * `padIfEmpty` — local-init flow pads `[0,0,0,0]`; manual push does not.
     *   * `sendIfEmpty` — manual push always sends; local-init can opt out.
     */
    private fun loadAndSendAddGroups(
        padIfEmpty: Boolean,
        sendIfEmpty: Boolean
    ) {
        groupsScope.launch {
            val ids = DataManager.getAppRepo().getAllGroupIds()
            if (!sendIfEmpty && ids.isEmpty()) return@launch
            sendOp(
                StardustOpCode.REQUEST_ADD_GROUPS,
                encodeGroupsPayload(ids, padIfEmpty)
            )
            Timber.tag("InitHandler").d("Sent ADD_GROUPS (${ids.size} ids)")
        }
    }

    fun sendAddAllGroups(sendIfEmpty: Boolean = true) =
        loadAndSendAddGroups(padIfEmpty = true, sendIfEmpty = sendIfEmpty)

    fun addGroupsToLocal() =
        loadAndSendAddGroups(padIfEmpty = false, sendIfEmpty = true)

    // ---------------------------------------------------------------------
    //  Group/contact resolution (sender vs group)
    // ---------------------------------------------------------------------

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
        val sourceIsGroup = isLocalGroupId(sourceId)
        val destinationIsGroup = isLocalGroupId(destinationId)

        return when {
            sourceIsGroup -> GroupContactResolution(
                groupId = sourceId,
                senderId = resolveSenderForSourceGroup(sourceId, destinationId)
            )
            destinationIsGroup -> GroupContactResolution(groupId = destinationId, senderId = sourceId)
            else -> GroupContactResolution(groupId = null, senderId = sourceId)
        }
    }

    private fun resolveSenderForSourceGroup(sourceId: String, destinationId: String): String {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId
        return if (appId != null && destinationId.equals(appId, ignoreCase = true)) {
            sourceId
        } else {
            destinationId
        }
    }

    // ---------------------------------------------------------------------
    //  Group-id queries
    // ---------------------------------------------------------------------

    suspend fun isLocalGroupId(id: String): Boolean =
        DataManager.getAppRepo().isGroupId(id.lowercase())

    suspend fun isLocalGroupId(ids: Collection<String?>): Boolean =
        DataManager.getAppRepo().hasAnyGroupId(ids)

    fun hasLocalGroups(): Boolean = runBlocking(Dispatchers.IO) {
        DataManager.getAppRepo().getAllGroupIds().isNotEmpty()
    }

}