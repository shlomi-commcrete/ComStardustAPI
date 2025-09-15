package com.commcrete.stardust.security

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.crypto.SecureKeyUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil
import timber.log.Timber

object EraseUtils {

    private var isArmed = false
    private val armHandler : Handler = Handler(Looper.getMainLooper())
    private val armRunnable : Runnable = kotlinx.coroutines.Runnable {
        isArmed = false
    }
    fun handleArm () {
        isArmed = true
        resetArmTimer()
        Timber.i("EraseUtils: Device is ARMED for data wipe in 1 minute")
    }

    fun handleDelete () {
        Timber.i("EraseUtils: handleDelete called, isArmed = $isArmed")
        if(isArmed) {
            removeArmTimer()
            Timber.i("EraseUtils: Device is being WIPED NOW")
            SecureKeyUtils.setSecuredKeyDefault(DataManager.context)
            Timber.i("EraseUtils: Secure key reset to default")
            SharedPreferencesUtil.setIsErased(DataManager.context, true)
            Timber.i("EraseUtils: isErased flag set to true")
            DataManager.logout(DataManager.context)
            Timber.i("EraseUtils: User logged out")
            val device = DataManager.getPairedDevices(DataManager.context)
            DataManager.getClientConnection(DataManager.context).mDevice = device
            DataManager.getClientConnection(DataManager.context).removeBittelBond()
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 1000)
        }
    }

    private fun resetArmTimer() {
        armHandler.removeCallbacks(armRunnable)
        armHandler.removeCallbacksAndMessages(null)
        armHandler.postDelayed(armRunnable, 1000 * 60)
    }

    private fun removeArmTimer() {
        try {
            armHandler.removeCallbacks(armRunnable)
            armHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }
}