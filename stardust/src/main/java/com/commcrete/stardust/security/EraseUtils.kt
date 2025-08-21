package com.commcrete.stardust.security

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.crypto.SecureKeyUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.SharedPreferencesUtil

object EraseUtils {

    private var isArmed = false
    private val armHandler : Handler = Handler(Looper.getMainLooper())
    private val armRunnable : Runnable = kotlinx.coroutines.Runnable {
        isArmed = false
    }
    fun handleArm () {
        isArmed = true
        resetArmTimer()
    }

    fun handleDelete () {
        if(isArmed) {
            removeArmTimer()
            SecureKeyUtils.setSecuredKeyDefault(DataManager.context)
            SharedPreferencesUtil.setIsErased(DataManager.context, true)
            DataManager.logout(DataManager.context)
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