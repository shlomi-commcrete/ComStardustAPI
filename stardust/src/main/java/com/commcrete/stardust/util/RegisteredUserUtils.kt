package com.commcrete.stardust.util


import com.commcrete.stardust.request_objects.RegisterUser
import com.commcrete.stardust.util.DataManager.cleanAllDatabases
import com.commcrete.stardust.util.DataManager.unpairDeviceBLE
import kotlinx.coroutines.*

object RegisteredUserUtils {

    var onUserUpdatedListener: OnUserUpdated? = null

    var mRegisterUser : RegisterUser? = null
        internal set(value) {
            field = value
            onUserUpdatedListener?.onUpdated(value)
        }


    fun isRegisteredUser(id: String): Boolean {
        return isRegisteredUser(listOf(id))
    }

    fun isRegisteredUser(ids: Collection<String?>): Boolean {
        val user = mRegisterUser ?: return false
        return ids.any { id ->
            id != null && (id == user.appId || id == user.bittelId)
        }
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        coroutineScope {
            // BLE unpair first (side-effect, usually must complete)
            unpairDeviceBLE(DataManager.context)
            val databases = async {
                cleanAllDatabases(DataManager.context)
            }
            val phone = async { SharedPreferencesUtil.removePhone(DataManager.context) }
            val password = async { SharedPreferencesUtil.removePassword(DataManager.context) }
            val appUser = async { SharedPreferencesUtil.removeAppUser(DataManager.context) }
            val user = async { SharedPreferencesUtil.removeUser(DataManager.context) }

            databases.await() &&
                    phone.await() &&
                    password.await() &&
                    appUser.await() &&
                    user.await()
        }
    }

}