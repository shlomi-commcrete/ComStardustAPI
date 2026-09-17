package com.commcrete.stardust.security

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.crypto.SecureKeyUtils
import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.room.new_db.AppDatabase
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
            SecureKeyUtils.setSecuredKeyDefault()
            Timber.i("EraseUtils: Secure key reset to default")
            SharedPreferencesUtil.setIsErased(true)
            Timber.i("EraseUtils: isErased flag set to true")
            DataManager.logout()
            Timber.i("EraseUtils: User logged out")
            // logout() empties the tables; this removes the files themselves.
            // Everything the SDK persists lives under one root, so the wipe is
            // a single recursive delete rather than a list of database names
            // that has to be kept in step. Close the database first or the open
            // handle rewrites its journal as the process winds down.
            AppDatabase.closeAndClear()
            val storageWiped = StardustStorage.deleteAll()
            Timber.i("EraseUtils: Stardust storage wiped = $storageWiped")
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