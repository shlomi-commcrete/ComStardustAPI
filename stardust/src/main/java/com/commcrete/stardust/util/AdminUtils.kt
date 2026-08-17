package com.commcrete.stardust.util

import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.config.SnifferMode
import kotlinx.coroutines.launch

object AdminUtils {

    val adminModeLiveData : MutableLiveData<SnifferMode> = MutableLiveData()

    fun setAdminMode(snifferMode: SnifferMode) {
        SharedPreferencesUtil.setAdminMode(snifferMode)
        Scopes.getMainCoroutine().launch {
            adminModeLiveData.value = snifferMode
        }
    }

    private fun getAdminMode() : SnifferMode {
        return SharedPreferencesUtil.getAdminMode()
    }

    fun getAdminLocalMode(): AdminLocal {
        return SharedPreferencesUtil.getAdminLocalMode()
    }

    fun isHandleSuperUser (adminLocal: AdminLocal) : Boolean {
        return adminLocal == AdminLocal.SuperUser
    }

    fun updateBittelAdminMode() {
        val (src, dst) = requireLocalSrcDst() ?: return
        val adminMode = getAdminMode()
        val clientConnection = DataManager.getClientConnection()

        val intData = arrayListOf<Int>()
        intData.add(adminMode.type)

        val deletePackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.SET_ADMIN_MODE,
            data = intData.toIntArray().toTypedArray().reversedArray())
        clientConnection.addMessageToQueue(deletePackage)
    }

    enum class AdminLocal (val type : Int){
        Regular(0),
        Admin(1),
        SuperUser(2),
    }

}