package com.commcrete.stardust.util

import com.commcrete.stardust.data.SupportedFunctionality
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.enums.LicenseType
import com.commcrete.stardust.enums.LimitationType

class LicenseLimitationsUtil {

    fun createSupportedFunctionalitiesByLicenseType(licenseType: LicenseType): List<SupportedFunctionality> { // TODO: change fun name
        return FunctionalityType.entries.map { functionalityType -> SupportedFunctionality(functionalityType, getFunctionalityLimitationByLicenseType(functionalityType, licenseType)) } }
    }

    private fun getFunctionalityLimitationByLicenseType(
        functionalityType: FunctionalityType,
        licenseType: LicenseType
    ): LimitationType {
        return when(licenseType) {
            LicenseType.UNDEFINED -> LimitationType.DISABLED
            LicenseType.C4I -> LimitationType.ENABLED
            LicenseType.STA -> when(functionalityType) {
                FunctionalityType.REPORTS -> LimitationType.DISABLED
                FunctionalityType.PTT -> LimitationType.INCOMING_ONLY
                FunctionalityType.LOCATION -> LimitationType.INCOMING_ONLY
                FunctionalityType.BFT -> LimitationType.INCOMING_ONLY
                FunctionalityType.FILE -> LimitationType.INCOMING_ONLY
                FunctionalityType.IMAGE -> LimitationType.INCOMING_ONLY
                FunctionalityType.TEXT -> LimitationType.INCOMING_ONLY
                FunctionalityType.ACK -> LimitationType.INCOMING_ONLY
            }
        }
    }
