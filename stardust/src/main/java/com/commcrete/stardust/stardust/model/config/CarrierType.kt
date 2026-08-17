package com.commcrete.stardust.stardust.model.config

import com.commcrete.stardust.enums.FunctionalityType

enum class CarrierType(val type: Int, val typeName: String) {
    HR(0, "High Rate"),
    LR(1, "Low Rate"),
    ST(2, "Fast Rate");

    fun getAllowedFunctionalityOptions(): Set<FunctionalityType> {
        return when (this) {
            HR -> HR_ALLOWED
            LR -> LR_ALLOWED
            ST -> ST_ALLOWED
        }
    }

    companion object {

        private val HR_ALLOWED = setOf(
            FunctionalityType.TEXT,
            FunctionalityType.LOCATION,
            FunctionalityType.PTT,
            FunctionalityType.BFT,
            FunctionalityType.FILE,
            FunctionalityType.IMAGE,
            FunctionalityType.REPORTS,
            FunctionalityType.ACK,
            FunctionalityType.SOS,
        )

        private val LR_ALLOWED = setOf(
            FunctionalityType.TEXT,
            FunctionalityType.LOCATION,
            FunctionalityType.REPORTS,
            FunctionalityType.ACK,
            FunctionalityType.SOS
        )

        private val ST_ALLOWED = setOf(
            FunctionalityType.IMAGE,
            FunctionalityType.FILE
        )

        fun fromType(type: Int): CarrierType =
            entries.firstOrNull { it.type == type } ?: HR
    }
}
