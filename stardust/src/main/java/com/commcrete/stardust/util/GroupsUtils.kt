package com.commcrete.stardust.util

import android.content.Context
import android.util.Log
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.StardustPackageUtils.hexStringToByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

object GroupsUtils {

    private const val TAG = "Groups"

    private val groupsLock = Any()
    private val groupsIds: MutableList<String> = mutableListOf()

    // ---- Live-sync state ----
    @Volatile private var observerJob: Job? = null
    @Volatile private var ready: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Start observing the chats table. Idempotent.
     * The cache is populated from the first DB emission and stays in sync with
     * subsequent inserts/updates/deletes automatically (Room Flow query).
     * Call once from app/SDK init (e.g. DataManager.init).
     */
    fun start(context: Context) {
        if (observerJob != null) return
        // Use application context to avoid leaking Activities/Services.
        val appContext = context.applicationContext
        synchronized(groupsLock) {
            if (observerJob != null) return
            if (ready.isCompleted) ready = CompletableDeferred()
            observerJob = Scopes.getDefaultCoroutine().launch {
                try {
                    DataManager.getChatsRepo(appContext).observeAllGroupIds()
                        // Restart on transient failures (e.g. DB closed / migration in-flight)
                        .retryWhen { cause, attempt ->
                            Log.w(TAG, "groups observer failed (attempt=$attempt); retrying", cause)
                            delay(1000L * (attempt + 1).coerceAtMost(10))
                            true
                        }
                        .catch { cause ->
                            Log.e(TAG, "groups observer giving up", cause)
                        }
                        .collect { groups ->
                            synchronized(groupsLock) {
                                groupsIds.clear()
                                groupsIds.addAll(groups)
                            }
                            if (!ready.isCompleted) ready.complete(Unit)
                            Log.i(TAG, "Cache refreshed from DB (${groups.size} groups)")
                        }
                } finally {
                    // Allow a future start() to re-create the observer if this one ended.
                    synchronized(groupsLock) { observerJob = null }
                }
            }
        }
    }

    fun stop() {
        synchronized(groupsLock) {
            observerJob?.cancel()
            observerJob = null
            groupsIds.clear()
            // Cancel any callers currently awaiting first-load so they don't hang forever.
            if (!ready.isCompleted) {
                ready.cancel(CancellationException("GroupsUtils stopped"))
            }
            ready = CompletableDeferred()
        }
    }

    /** Suspend until the cache has been populated at least once from the DB. */
    suspend fun awaitReady() = ready.await()

    /** Suspend variant of [isGroup] guaranteed to wait for the first DB load. For checkups outside Dust communication. */
    suspend fun isGroupAwait(id: String?): Boolean {
        awaitReady()
        return isGroup(id)
    }

    fun isGroup(id: String?): Boolean {
        val cleaned = id?.trim()?.lowercase() ?: return false
        // groupsIds already contains lower-cased values (DAO uses LOWER(chat_id)).
        return synchronized(groupsLock) { groupsIds.any { it == cleaned } }
    }

    fun sendDeleteAllGroups(context: Context) {
        // NOTE: do NOT clear the local cache here — only a network request is being
        // queued. The DB hasn't changed yet, so clearing now would create an
        // inconsistent window until the Flow re-emits. The cache will be refreshed
        // automatically once the response handler actually deletes rows from the DB.
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            val intData = arrayListOf<Int>()
            intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)
            if (src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destenation = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_DELETE_ALL_GROUPS,
                    data = intData.toIntArray().toTypedArray()
                )
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    private fun sendGroups(context: Context, groupsList: List<String>) {
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        val user = SharedPreferencesUtil.getAppUser(context) ?: return
        val src = user.appId ?: return
        val dst = user.bittelId ?: return
        val intData = arrayListOf<Int>()

        if (groupsList.isNotEmpty()) {
            for (group in groupsList) {
                intData.addAll(hexStringToByteArray(group).reversedArray())
            }
        } else {
            intData.addAll(listOf(0, 0, 0, 0))
        }

        intData.add(StardustPackageUtils.BittelAddressUpdate.SMARTPHONE.id)

        val pkg = StardustPackageUtils.getStardustPackage(
            context = context,
            source = src,
            destenation = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_ADD_GROUPS,
            data = intData.toIntArray().toTypedArray().reversedArray()
        )

        clientConnection.addMessageToQueue(pkg)
        Log.d("InitHandler", "Sent ADD_GROUPS")
    }


    fun sendAddAllGroups(context: Context, sendIfEmpty: Boolean = true) {
        Scopes.getDefaultCoroutine().launch {
            start(context)        // ensure observer is running
            awaitReady()          // ensure first DB load has completed
            val snapshot = synchronized(groupsLock) { groupsIds.toList() }
            if (!sendIfEmpty && snapshot.isEmpty()) return@launch
            sendGroups(context, snapshot)
        }
    }

}