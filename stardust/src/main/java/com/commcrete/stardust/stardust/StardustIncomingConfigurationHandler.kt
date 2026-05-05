package com.commcrete.stardust.stardust

import android.content.Context
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

    fun parseAndApplyConfiguration(context: Context, packet: StardustPackage): ApplyResult {
        val cfg = StardustConfigurationParser().parseConfiguration(packet)
            ?: return ApplyResult(applied = false, hasPresetsWithoutConfig = false)
        SharedPreferencesUtil.saveLastSosDestinations(context, cfg.sosDestinations)
        Scopes.getMainCoroutine().launch {
            ConfigurationUtils.bittelConfiguration.value = cfg
        }
        ConfigurationUtils.licensedFunctionalities =
            LicenseLimitationsUtil().createSupportedFunctionalitiesByLicenseType(cfg.licenseType)
        ConfigurationUtils.setConfigFile(cfg)
        ConfigurationUtils.setDefaults(context)

        return ApplyResult(
            applied = true,
            hasPresetsWithoutConfig = cfg.presetsWithoutConfig(context).isNotEmpty()
        )
    }
}


