package com.commcrete.stardust.util

import com.commcrete.stardust.R

object RemoteConfigUtils {

    fun initLocalDefaults() {
        val offlineConfig = FileUtils.readRawResourceAsByteArray(DataManager.appContext, R.raw.offline_demo)
        val newDemoConfig = FileUtils.readRawResourceAsByteArray(DataManager.appContext, R.raw.new_demo)
        val fileOfflineDemo = FileUtils.createFile(folderName = "config", fileName = "offlineDemo", fileType = ".json")
        FileUtils.saveToFile(fileOfflineDemo.absolutePath, offlineConfig, false)
        val fileNewDemo = FileUtils.createFile(folderName = "config", fileName = "newDemo", fileType = ".json")
        FileUtils.saveToFile(fileNewDemo.absolutePath, newDemoConfig, false)
        SharedPreferencesUtil.setConfigSaved()
    }
}
