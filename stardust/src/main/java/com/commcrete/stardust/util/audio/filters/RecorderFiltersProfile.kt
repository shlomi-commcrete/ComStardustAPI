package com.commcrete.stardust.util.audio.filters

import com.commcrete.stardust.util.audio.filters.configs.AGCConfig
import com.commcrete.stardust.util.audio.filters.configs.DynamicsConfig
import com.commcrete.stardust.util.audio.filters.configs.HighPassConfig
import com.commcrete.stardust.util.audio.filters.configs.LowPassConfig
import com.commcrete.stardust.util.audio.filters.configs.NotchConfig
import com.commcrete.stardust.util.audio.filters.configs.RnNoiseConfig

data class RecorderFiltersProfile(
    val lowPass: LowPassConfig? = null,
    val notch: NotchConfig? = null,
    val rnNoise: RnNoiseConfig? = null,
    val agc: AGCConfig? = null,
    val dynamics: DynamicsConfig? = null,
    val highPass: HighPassConfig? = null,
)