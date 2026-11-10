package com.commcrete.stardust.util


import com.commcrete.stardust.contacts.ContactDraft
import com.commcrete.stardust.room.new_db.contact.ContactType
import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.new_db.internal.normalizeIdOrNull
import com.commcrete.stardust.util.DataManager.cleanAllDatabases
import com.commcrete.stardust.util.DataManager.unpairDeviceBLE
import com.commcrete.stardust.util.connectivity.PortUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object RegisteredUserUtils {

    private const val TAG = "RegisteredUser"

    private val _currentUserFlow = MutableStateFlow<RegisterUser?>(null)
    val currentUserFlow: StateFlow<RegisterUser?>
        get() {
            ensureUserLoadedFromPrefs()
            return _currentUserFlow.asStateFlow()
        }

    private fun ensureUserLoadedFromPrefs() {
        if (_currentUserFlow.value != null) return
        val persisted = runCatching { SharedPreferencesUtil.getAppUser() }.getOrNull()
        updateRegisteredUser(persisted)
    }

    internal fun updateRegisteredUser(user: RegisterUser?) {
        _currentUserFlow.value = user
    }

    fun isRegisteredUser(id: String): Boolean = isRegisteredUser(listOf(id))

    fun isRegisteredUser(ids: Collection<String?>): Boolean {
        val user = currentUserFlow.value ?: return false
        return ids.mapNotNull { normalizeIdOrNull(it) }.any { id -> id == user.appId || id == user.deviceId }
    }

    fun isUserLoggedIn(): Boolean = currentUserFlow.value?.appId?.isNotBlank() == true

    /**
     * The operator as a contact draft, read live — what every contact path checks a contact
     * against (ContactConflictEngine.detect's `self`). Null while logged out.
     */
    fun currentUserDraft(): ContactDraft? = currentUserFlow.value
        ?.takeIf { it.appId.isNotBlank() }
        ?.let { ContactDraft(name = it.displayName, appId = it.appId, deviceId = it.deviceId.orEmpty()) }

    /** The operator's ids over time, for history and display — see [SelfIdentity]. */
    fun selfIdentity(): SelfIdentity = SelfIdentity.of(currentUserFlow.value)

    /** [selfIdentity] as it changes: on login, logout, an App ID or radio change. */
    val selfIdentityFlow: Flow<SelfIdentity>
        get() = currentUserFlow.map(SelfIdentity::of).distinctUntilChanged()

    /** Whether [id] was one of the operator's ids at [atMs]. One-off checks only; per row, take a [selfIdentity]. */
    fun wasRegisteredUser(id: String, atMs: Long): Boolean = selfIdentity().wasMine(id, atMs)

    /**
     * A stored contact that holds the connected radio's Device ID, as found at link-up. [newRadio]:
     * this radio's Device ID differs from the one the user had saved before this link-up — the first
     * link to it, over BLE or USB. The host warns only then, not on every reconnect or restart.
     */
    data class RadioHeldByContact(val contactName: String, val deviceId: String, val newRadio: Boolean)

    private val _radioHeldByContact = MutableStateFlow<RadioHeldByContact?>(null)

    /**
     * The contact holding the operator's radio, or null. That contact's traffic reads as the
     * operator's and its chat sends to the operator's own radio; the host warns. Nothing is
     * changed here — the operator fixes it by editing the contact.
     */
    val radioHeldByContact: StateFlow<RadioHeldByContact?> = _radioHeldByContact.asStateFlow()

    private val radioCheckScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The radio first linked in this process with a Device ID unlike the saved one; memory only. */
    private var newRadioId: String? = null

    /**
     * Looks for a contact holding [deviceId] (the radio just registered). Called by the link-up
     * writers, with [previousDeviceId] read from the saved user before they overwrote it.
     */
    internal fun checkRadioHolder(deviceId: String, previousDeviceId: String?) {
        val radio = normalizeIdOrNull(deviceId) ?: return
        val newRadio = synchronized(this) {
            if (!radio.equals(previousDeviceId?.let(::normalizeIdOrNull), ignoreCase = true)) newRadioId = radio
            // Sticky: both link-up writers can run for one link, and the second already finds the id saved.
            radio.equals(newRadioId, ignoreCase = true)
        }
        radioCheckScope.launch {
            val roster = runCatching { DataManager.getAppRepo().getAllContactDrafts() }.getOrElse {
                // Class only: a SQLite message can quote a callsign or id. No answer is not "no holder":
                // the previous value stands until the next link-up asks again.
                StardustLog.w(LogType.STORAGE, TAG, "Radio holder check failed: ${it.javaClass.simpleName}")
                return@launch
            }
            val holder = roster.firstOrNull { it.type != ContactType.GROUP && it.deviceId.equals(radio, ignoreCase = true) }
            if (holder != null) StardustLog.w(LogType.STORAGE, TAG, "Radio Device ID held by a contact")
            _radioHeldByContact.value = holder?.let { RadioHeldByContact(it.name, radio, newRadio) }
        }
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        // Logcat keeps this one; the log files are deleted below.
        StardustLog.i(LogType.STORAGE, TAG, "Logout started")
        coroutineScope {
            // Stop the port-mode assertion and the keepalive ping BEFORE tearing the link down.
            // Both are `while(isActive)` loops that otherwise run for the life of the process, and
            // a ping timeout firing mid-teardown would request a reconnect for the session we are
            // in the middle of ending. Also covers the security-erase path, which reaches here via
            // DataManager.logout().
            PortUtils.stopUpdatingPort()

            // TODO: if logout stops wiping the database (cleanAllDatabases below), clear the radio
            //  too while a USB link is up — BLE needs nothing, unpairDeviceBLE drops it. A USB
            //  link outlives logout, and the radio keeps the logged-out user's App ID (from the
            //  handshake's UPDATE_ADDRESS) and their groups, so it goes on routing their traffic
            //  to this phone. Send UPDATE_ADDRESS with a zero App ID, then REQUEST_DELETE_ALL_GROUPS
            //  (GroupsUtils.sendDeleteAllGroups). Both BEFORE removeAppUser() below:
            //  requireLocalSrcDst() needs the user, and GroupsUtils.sendOp no-ops silently without it.
            //  The next login re-runs the full handshake (BittelUsbManager2.onLoginWithUsbUp).

            // BLE unpair first (side-effect, usually must complete)
            unpairDeviceBLE()
            val databases = async {
                cleanAllDatabases()
            }
            val phone = async { SharedPreferencesUtil.removePhone() }
            val password = async { SharedPreferencesUtil.removePassword() }
            val appUser = async { SharedPreferencesUtil.removeAppUser() }
            val user = async { SharedPreferencesUtil.removeUser() }
            val logs = async { StardustLog.deleteFiles() }

            databases.await() &&
                    phone.await() &&
                    password.await() &&
                    appUser.await() &&
                    user.await() &&
                    logs.await()
        }.also { cleared ->
            // The first line of the next log file: the logout finished, and whether everything went.
            StardustLog.i(LogType.STORAGE, TAG, "Logout finished: all data cleared=$cleared")
        }
    }

}