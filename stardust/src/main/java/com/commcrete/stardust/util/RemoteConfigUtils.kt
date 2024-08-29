package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.R

class RemoteConfigUtils {


    private fun initLocalDefaults (context: Context) {
        val offlineConfig = FileUtils.readRawResourceAsByteArray(context, R.raw.offline_demo    )
        val newDemoConfig = FileUtils.readRawResourceAsByteArray(context, R.raw.new_demo    )
        val fileOfflineDemo = FileUtils.createFile(context, folderName = "config", fileName = "offlineDemo", fileType = ".json")
        FileUtils.saveToFile(fileOfflineDemo.absolutePath, offlineConfig, false)
        val fileNewDemo = FileUtils.createFile(context, folderName = "config", fileName = "newDemo", fileType = ".json")
        FileUtils.saveToFile(fileNewDemo.absolutePath, newDemoConfig, false)
        SharedPreferencesUtil.setConfigSaved(context)
    }
}
