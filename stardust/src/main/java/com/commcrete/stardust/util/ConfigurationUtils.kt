package com.commcrete.stardust.util

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LimitationType
import com.commcrete.stardust.stardust.model.StardustAppEventPackage
import com.commcrete.stardust.stardust.model.StardustConfigurationPackage
import com.commcrete.stardust.stardust.model.config.CurrentPreset
import com.commcrete.stardust.stardust.model.config.FirmwareVersion
import com.commcrete.stardust.stardust.model.config.Preset
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch

object ConfigurationUtils {


    val bittelVersion = MutableLiveData<String>()
    val bittelConfiguration = MutableLiveData<StardustConfigurationPackage?>()

    // Synchronous mirror of the firmware version string. Set the instant a version packet is
    // handled so the config parser can read it without racing the async LiveData post.
    @Volatile
    var firmwareVersion: String? = null
        private set

    var currentConfig : StardustConfigurationPackage? = null
    private var _currentPreset = MutableLiveData<CurrentPreset?> (null)
    var currentPreset: LiveData<CurrentPreset?> = _currentPreset
    var selectedPreset : Preset? = null
    var presetsList : List<Preset> = listOf()

    var licensedFunctionalities: Map<FunctionalityType, LimitationType> = mapOf()

    fun setConfigFile (config : StardustConfigurationPackage) {
        currentConfig = config
        currentConfig?.let {
            presetsList = it.presets
            setCurrentPresetLocal(it.currentPreset)
        }
    }

    fun setCurrentPresetLocal(preset : CurrentPreset) {
        _currentPreset.postValue(preset)
        val config = currentConfig ?: return

        if (presetsList.isEmpty()) return

        selectedPreset = config.presets.getOrNull(preset.value)
    }

    fun setStardustCarrierFromEvent (stardustAppEventPackage: StardustAppEventPackage) {
        stardustAppEventPackage.carrier = selectedPreset?.xcvrList?.getOrNull(stardustAppEventPackage.xcvr)?.carrier
    }

    private fun getLastPresets () : List<Preset>?{
        return SharedPreferencesUtil.getPresets()
    }

    private fun setLastPresets (presets : List<Preset>){
        return SharedPreferencesUtil.setPresets(presets)
    }

    fun isPresetsChanged (presets : List<Preset>): Boolean {
        val lastPresets = getLastPresets()
        setLastPresets(presets)
        return lastPresets != presets
    }
    fun setDefaults() {
        currentConfig?.let {config ->
            if(isPresetsChanged(config.presets)) {
                CarriersUtils.setPresetsAfterChange(config)
            } else {
                CarriersUtils.setPresetsWithoutChange()
            }
            _currentPreset.value.let {
                CarriersUtils.updateCurrentPresetList(it)
            }
        }
    }

    /**
     * The connected device's firmware as a comparable triple, or null if unknown/unparseable.
     * Uses the same tolerant parser the config parser uses ("Ver_24.0.7", "Ver 24.0.7", "24.0.7", …).
     */
    val currentFirmwareVersion: FirmwareVersion?
        get() = FirmwareVersion.parse(firmwareVersion)

    /**
     * Minimum-version check: true iff the device firmware is >= the given version. Returns false when
     * the version is unknown (conservative — treated as legacy). Prefer this over comparing version
     * strings so all version logic stays in one place.
     *
     * e.g. `isFirmwareAtLeast(24, 0, 7)` is true for 24.0.7, 24.0.8, 24.1.0, 25.x.x, …
     */
    fun isFirmwareAtLeast(major: Int, minor: Int, patch: Int): Boolean =
        isFirmwareAtLeast(FirmwareVersion(major, minor, patch))

    fun isFirmwareAtLeast(threshold: FirmwareVersion): Boolean {
        val current = currentFirmwareVersion ?: return false
        return current >= threshold
    }

    fun handleVersion(mPackage: StardustPackage) {
        val version = mPackage.getDataAsString()
        firmwareVersion = version
        Scopes.getMainCoroutine().launch {
            bittelVersion.value = version
        }
    }

    fun reset() {
        currentConfig = null
        presetsList = listOf()
        licensedFunctionalities = mapOf()
        selectedPreset = null
        firmwareVersion = null
        Scopes.getMainCoroutine().launch {
            _currentPreset.value = null
            bittelVersion.value = ""
            bittelConfiguration.value = null
        }
    }

}