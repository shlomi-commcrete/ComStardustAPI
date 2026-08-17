package com.commcrete.stardust.stardust


import android.util.Log
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.LicenseLimitationsUtil
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.launch

internal object StardustIncomingConfigurationHandler {

    data class ApplyResult(
        val applied: Boolean,
        val hasPresetsWithoutConfig: Boolean
    )

    fun parseAndApplyConfiguration(packet: StardustPackage): ApplyResult {
        val cfg = StardustConfigurationParser().parseConfiguration(packet, ConfigurationUtils.firmwareVersion)
        if (cfg == null) {
            Log.w(
                "ConfigDebug",
                "parseConfiguration returned NULL opCode=${packet.stardustOpCode} " +
                    "dataLen=${packet.getDataSizeLength()} dataStr=${packet.getDataAsString()} bytes=[${packet.toHex()}]"
            )
            return ApplyResult(applied = false, hasPresetsWithoutConfig = false)
        }
        SharedPreferencesUtil.saveLastSosDestinations(cfg.sosDestinations)
        Scopes.getMainCoroutine().launch {
            ConfigurationUtils.bittelConfiguration.value = cfg
        }
        ConfigurationUtils.licensedFunctionalities =
            LicenseLimitationsUtil().createSupportedFunctionalitiesByLicenseType(cfg.licenseType)
        ConfigurationUtils.setConfigFile(cfg)
        ConfigurationUtils.setDefaults()

        return ApplyResult(
            applied = true,
            hasPresetsWithoutConfig = cfg.presetsWithoutConfig().isNotEmpty()
        )
    }
}


