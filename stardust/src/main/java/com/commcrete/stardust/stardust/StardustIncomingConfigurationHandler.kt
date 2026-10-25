package com.commcrete.stardust.stardust


import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.transport.ConnectionManager
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.ConfigurationUtils
import com.commcrete.stardust.util.LicenseLimitationsUtil
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.StardustLog
import kotlinx.coroutines.launch

internal object StardustIncomingConfigurationHandler {

    data class ApplyResult(
        val applied: Boolean,
        val hasPresetsWithoutConfig: Boolean
    )

    fun parseAndApplyConfiguration(packet: StardustPackage): ApplyResult {
        val cfg = StardustConfigurationParser().parseConfiguration(packet, ConfigurationUtils.firmwareVersion)
        if (cfg == null) {
            StardustLog.w(
                "ConfigDebug",
                "parseConfiguration returned NULL opCode=${packet.stardustOpCode} " +
                    "dataLen=${packet.getDataSizeLength()} dataSize=${packet.data?.size}"
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

        // After setDefaults: the preset check reads the RD selection it just saved.
        ConnectionManager.refreshConfigWarnings()

        return ApplyResult(
            applied = true,
            hasPresetsWithoutConfig = cfg.presetsWithoutConfig().isNotEmpty()
        )
    }
}


