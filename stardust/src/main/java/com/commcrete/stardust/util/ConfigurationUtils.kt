package com.commcrete.stardust.util

import android.content.Context
import androidx.lifecycle.LiveData
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
    private var _currentPreset = MutableLiveData<StardustConfigurationParser.CurrentPreset?> (null)
    var currentPreset: LiveData<StardustConfigurationParser.CurrentPreset?> = _currentPreset
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

    fun setCurrentPresetLocal(preset : StardustConfigurationParser.CurrentPreset) {
        _currentPreset.postValue(preset)
        val config = currentConfig ?: return

        if (presetsList.isEmpty()) return

        selectedPreset = config.presets.getOrNull(preset.value)
    }

    fun setStardustCarrierFromEvent (stardustAppEventPackage: StardustAppEventPackage) {
        stardustAppEventPackage.carrier = selectedPreset?.xcvrList?.getOrNull(stardustAppEventPackage.xcvr)?.carrier
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
            _currentPreset.value.let {
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
        presetsList = listOf()
        licensedFunctionalities = mapOf()
        selectedPreset = null
        Scopes.getMainCoroutine().launch {
            _currentPreset.value = null
            bittelVersion.value = ""
            bittelConfiguration.value = null
        }
    }

}