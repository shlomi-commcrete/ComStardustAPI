package com.commcrete.stardust.util

import android.content.Context
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser

object ConfigurationUtils {

    var currentConfig : StardustConfigurationPackage? = null
    var currentPreset : StardustConfigurationParser.CurrentPreset? = null
    var selectedPreset : StardustConfigurationParser.Preset? = null
    var presetsList : List<StardustConfigurationParser.Preset> = listOf()

    fun setConfigFile (config : StardustConfigurationPackage) {
        currentConfig = config
        currentConfig?.let {
            presetsList = it.presets
            setCurrentPresetLocal(it.currentPreset)
        }
    }

    fun setCurrentPresetLocal (preset : StardustConfigurationParser.CurrentPreset) {
        currentPreset = preset
        setCurrentPreset()
    }

    private fun setCurrentPreset () {
        currentConfig?.let { config ->
            currentPreset?.let {presetNum ->
                if(presetsList.isNotEmpty()) {
                    if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET1) {
                        selectedPreset = config.presets.get(0)
                    } else if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET2) {
                        selectedPreset = config.presets.get(1)
                    } else if (currentPreset == StardustConfigurationParser.CurrentPreset.PRESET3) {
                        selectedPreset = config.presets.get(2)
                    } else {
                        selectedPreset = config.presets.get(0)
                    }
                }
            }
        }

    }

    fun setStardustCarrierFromEvent (stardustAppEventPackage: StardustAppEventPackage) {
        selectedPreset?.xcvrList?.let { xcvrs ->
            when (stardustAppEventPackage.xcvr) {
                0 -> {stardustAppEventPackage.carrier = xcvrs.get(0).carrier}
                1 -> {stardustAppEventPackage.carrier = xcvrs.get(1).carrier}
                2 -> {stardustAppEventPackage.carrier = xcvrs.get(2).carrier}
            }
        }
    }

    private fun getLastPresets (context: Context) : List<StardustConfigurationParser.Preset>?{
        return SharedPreferencesUtil.getPresets(context)
    }

    private fun setLastPresets (presets : List<StardustConfigurationParser.Preset>, context: Context){
        return SharedPreferencesUtil.setPresets(context, presets)
    }

    fun isPresetsChanged (presets : List<StardustConfigurationParser.Preset>, context: Context): Boolean {
        val lastPresets = getLastPresets(context)
        setLastPresets(presets, context)
        return lastPresets != presets
    }

    fun setDefaults (context: Context) {
        currentConfig?.let {config ->
            if(isPresetsChanged(config.presets, context)) {
                selectedPreset?.let { preset ->
                    CarriersUtils.getDefaultsFromPresets(config)
                }
            }
//            getDefaults()
        }
    }

    fun getDefaults () {

    }

}