package com.commcrete.stardust.util

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch

object ConfigurationUtils {


    val bittelVersion = MutableLiveData<String>()
    val bittelConfiguration = MutableLiveData<StardustConfigurationPackage?>()

    var currentConfig : StardustConfigurationPackage? = null
    var currentPreset : StardustConfigurationParser.CurrentPreset? = null
    var selectedPreset : StardustConfigurationParser.Preset? = null
    var presetsList : List<StardustConfigurationParser.Preset> = listOf()

    var licensedFunctionalities: Map<FunctionalityType, LimitationType> = mapOf()

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

    private fun setCurrentPreset() {
        val config = currentConfig ?: return
        val preset = currentPreset ?: return

        if (presetsList.isEmpty()) return

        val index = when (preset) {
            StardustConfigurationParser.CurrentPreset.PRESET1 -> 0
            StardustConfigurationParser.CurrentPreset.PRESET2 -> 1
            StardustConfigurationParser.CurrentPreset.PRESET3 -> 2
        }
        selectedPreset = config.presets.getOrNull(index)
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
                CarriersUtils.setPresetsAfterChange(config)
            } else {
                CarriersUtils.setPresetsWithoutChange()
            }
            currentPreset?.let {
                CarriersUtils.updateCurrentPresetList(it)
            }
        }
    }

    fun handleVersion(mPackage: StardustPackage) {
        Scopes.getMainCoroutine().launch {
            bittelVersion.value = mPackage.getDataAsString()
        }
    }

    fun reset() {
        currentConfig = null
        currentPreset = null
        presetsList = listOf()
        licensedFunctionalities = mapOf()
        selectedPreset = null
        Scopes.getMainCoroutine().launch {
            bittelVersion.value = ""
            bittelConfiguration.value = null
        }
    }

}