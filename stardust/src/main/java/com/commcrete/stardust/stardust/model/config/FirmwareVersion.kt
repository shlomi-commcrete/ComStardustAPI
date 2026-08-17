package com.commcrete.stardust.stardust.model.config

/**
 * Device firmware version as a comparable (major, minor, patch) triple.
 *
 * Used as a **minimum-version threshold**: any version `>=` a threshold selects the newer wire
 * format (e.g. `24.0.8`, `24.1.0`, `25.x.x` all compare greater than `24.0.7`). Mirrors the C
 * core's `is_v24_0_7_or_later` tuple comparison.
 */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        // Matches the first "X.Y.Z" anywhere in the string, so it tolerates any leading token
        // ("Ver_24.0.7", "Ver 24.0.7", "v24.0.7", "24.0.7").
        private val TRIPLE = Regex("""(\d+)\.(\d+)\.(\d+)""")

        fun parse(raw: String?): FirmwareVersion? {
            if (raw.isNullOrBlank()) return null
            val m = TRIPLE.find(raw) ?: return null
            return FirmwareVersion(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt()
            )
        }
    }
}
