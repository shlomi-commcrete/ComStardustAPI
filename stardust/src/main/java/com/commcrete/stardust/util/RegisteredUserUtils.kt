package com.commcrete.stardust.util


import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.room.new_db.internal.normalizeIdOrNull
import com.commcrete.stardust.util.DataManager.cleanAllDatabases
import com.commcrete.stardust.util.DataManager.unpairDeviceBLE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RegisteredUserUtils {

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

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        coroutineScope {
            // BLE unpair first (side-effect, usually must complete)
            unpairDeviceBLE()
            val databases = async {
                cleanAllDatabases()
            }
            val phone = async { SharedPreferencesUtil.removePhone() }
            val password = async { SharedPreferencesUtil.removePassword() }
            val appUser = async { SharedPreferencesUtil.removeAppUser() }
            val user = async { SharedPreferencesUtil.removeUser() }

            databases.await() &&
                    phone.await() &&
                    password.await() &&
                    appUser.await() &&
                    user.await()
        }
    }

}