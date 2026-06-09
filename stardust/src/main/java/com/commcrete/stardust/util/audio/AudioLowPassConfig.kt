package com.commcrete.stardust.util.audio

data class LowPassConfig(
    val enabled: Boolean = true,
    val cutoffHz: Float = 3_500f,
    val rollOffDbPerOctave: Float = 40f,
) {
    /** Short human-readable summary for logs. */
    @Suppress("unused")
    internal fun describe(): String =
        if (!enabled) "off"
        else "%.0fHz @ %.0fdB/oct".format(cutoffHz, rollOffDbPerOctave)
            .replace(',', '.')

    companion object {
        @Suppress("unused")
        fun voiceBand() = LowPassConfig()

        fun getDefault(deviceType: RecordingDeviceType): LowPassConfig? = when (deviceType) {
            RecordingDeviceType.JBOX_INTERNAL -> LowPassConfig(enabled = true, cutoffHz = 3500f, rollOffDbPerOctave = 40f)
            RecordingDeviceType.JBOX_EXTERNAL -> LowPassConfig(enabled = true, cutoffHz = 3500f, rollOffDbPerOctave = 40f)
            else -> null
        }
    }
}

