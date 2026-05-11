package com.commcrete.stardust.util


import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.util.DataManager.cleanAllDatabases
import com.commcrete.stardust.util.DataManager.unpairDeviceBLE
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RegisteredUserUtils {

    private val _mRegisterUser = MutableStateFlow<RegisterUser?>(null)
    val mRegisterUser: StateFlow<RegisterUser?> = _mRegisterUser.asStateFlow()


    internal fun updateRegisteredUser(user: RegisterUser?) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            _mRegisterUser.value = user
        }
    }

    fun isRegisteredUser(id: String): Boolean {
        return isRegisteredUser(listOf(id))
    }

    fun isRegisteredUser(ids: Collection<String?>): Boolean {
        val user = _mRegisterUser.value ?: return false
        return ids.any { id ->
            id != null && (id == user.appId || id == user.deviceId)
        }
    }
    
    fun isUserLoggedIn(): Boolean {
        val inMemoryUser = mRegisterUser.value
        if (inMemoryUser != null) {
            return !inMemoryUser.appId.isNullOrBlank() && !inMemoryUser.deviceId.isNullOrBlank()
        }

        // Cold start fallback: recover persisted user state when storage is available.
        val persistedUser = runCatching { SharedPreferencesUtil.getAppUser() }.getOrNull()
        if (persistedUser != null &&
            !persistedUser.appId.isNullOrBlank() &&
            !persistedUser.deviceId.isNullOrBlank()) {
            updateRegisteredUser(persistedUser)
            return true
        }

        return false
    }

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