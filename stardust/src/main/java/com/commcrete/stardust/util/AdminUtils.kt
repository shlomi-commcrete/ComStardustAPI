package com.commcrete.stardust.util

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import kotlinx.coroutines.launch

object AdminUtils {

    val adminModeLiveData : MutableLiveData<StardustConfigurationParser.SnifferMode> = MutableLiveData()
    fun setAdminMode (context: Context, snifferMode: StardustConfigurationParser.SnifferMode) {
        SharedPreferencesUtil.setAdminMode(context, snifferMode)
        Scopes.getMainCoroutine().launch {
            adminModeLiveData.value = snifferMode
        }
    }

    private fun getAdminMode (context: Context) : StardustConfigurationParser.SnifferMode {
        return SharedPreferencesUtil.getAdminMode(context)
    }

    fun getAdminLocalMode (context: Context) : AdminLocal {
        return SharedPreferencesUtil.getAdminLocalMode(context)
    }

    fun isHandleSuperUser (adminLocal: AdminLocal) : Boolean {
        return adminLocal == AdminLocal.SuperUser
    }

    fun updateBittelAdminMode () {
        val adminMode = getAdminMode(DataManager.context)
        val clientConnection = DataManager.getClientConnection(DataManager.context)
        SharedPreferencesUtil.getAppUser(DataManager.context)?.let {
            val src = it.appId
            val dst = it.bittelId
            val intData = arrayListOf<Int>()
            intData.add(adminMode.type)
            if(src != null && dst != null) {
                val deletePackage = StardustPackageUtils.getStardustPackage(
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE,
                    data = intData.toIntArray().toTypedArray().reversedArray())
                clientConnection.addMessageToQueue(deletePackage)
            }
        }
    }

    enum class AdminLocal (val type : Int){
        Regular(0),
        Admin(1),
        SuperUser(2),
    }

}