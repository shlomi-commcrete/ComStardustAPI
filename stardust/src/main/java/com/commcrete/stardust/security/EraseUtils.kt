package com.commcrete.stardust.security

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.crypto.SecureKeyUtils
import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.room.new_db.AppDatabase
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.LogType
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.StardustLog

object EraseUtils {

    private const val TAG = "EraseUtils"
    private var isArmed = false
    private val armHandler : Handler = Handler(Looper.getMainLooper())
    private val armRunnable : Runnable = kotlinx.coroutines.Runnable {
        isArmed = false
    }
    fun handleArm () {
        isArmed = true
        resetArmTimer()
        StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: Device is ARMED for data wipe in 1 minute")
    }

    fun handleDelete () {
        StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: handleDelete called, isArmed = $isArmed")
        if(isArmed) {
            removeArmTimer()
            StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: Device is being WIPED NOW")
            SecureKeyUtils.setSecuredKeyDefault()
            StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: Secure key reset to default")
            SharedPreferencesUtil.setIsErased(true)
            StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: isErased flag set to true")
            DataManager.logout()
            StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: User logged out")
            // logout() empties the tables; this removes the files themselves.
            // Everything the SDK persists lives under one root, so the wipe is
            // a single recursive delete rather than a list of database names
            // that has to be kept in step. Close the database first or the open
            // handle rewrites its journal as the process winds down.
            AppDatabase.closeAndClear()
            val storageWiped = StardustStorage.deleteAll()
            // A wipe that left files behind is a failure, not a step.
            if (storageWiped) {
                StardustLog.i(LogType.GENERAL, TAG, "EraseUtils: Stardust storage wiped = $storageWiped")
            } else {
                StardustLog.e(TAG, "EraseUtils: Stardust storage wiped = $storageWiped")
            }
            val device = DataManager.getPairedDevices()
            DataManager.getClientConnection().mDevice = device
            // Security wipe: destroy the local pairing record unconditionally, even if the OS
            // unbond fails. Any leftover OS bond is recoverable later via PairingRepository adoption.
            DataManager.getClientConnection().removeBittelBond(forceClearLocal = true)
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