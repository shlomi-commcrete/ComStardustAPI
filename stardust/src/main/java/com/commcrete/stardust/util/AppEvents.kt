package com.commcrete.stardust.util

import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import kotlinx.coroutines.launch

object AppEvents {

    fun updateAppEvents (bittelAppEventPackage: StardustAppEventPackage) {
        DataManager.getCallbacks()?.onAppEvent(bittelAppEventPackage)
    }

    fun updateBattery (percent: Int) {
        DataManager.getCallbacks()?.onBatteryChanged(percent)
    }
}