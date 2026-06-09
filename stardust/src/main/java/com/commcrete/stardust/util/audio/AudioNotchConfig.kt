package com.commcrete.stardust.util.audio

/**
 * Optional NotchFilter (band-stop) stage; runs FIRST in the DSP chain,
 * before RnNoise / AGC / LPF / AI. Useful for removing tonal contaminants
 * (jbox "piiii" whine, mains hum, switching-supply harmonics).
 *
 * The caller chooses **exactly one** of two configuration modes:
 *  1. **Uniform harmonics** – set [fundamentalHz], [q] and [numHarmonics];
 *  2. **Per-harmonic control** – set [harmonics] to a non-empty list of
 *     [Harmonic] specs (each entry has its own frequency and Q).
 *
 * Setting both, or neither, throws [IllegalArgumentException] when the
 * feeder is started.
 */
data class NotchConfig(
    val enabled: Boolean = true,
    val fundamentalHz: Float = 1_000f,
    val q: Float = 30f,
    val numHarmonics: Int? = null,
    val harmonics: List<Harmonic> = mutableListOf<Harmonic>().also { list ->
        for (i in 1..4) { list.add(Harmonic(frequencyHz = (i * 50).toFloat(), q = 10f)) }
        for (i in 3..4) list.add(Harmonic(frequencyHz = (i * 1_00).toFloat(), q = 50f))
        list.add(Harmonic(frequencyHz = 800f, q = 100f))
        for (i in 1..8) list.add(Harmonic(frequencyHz = (i * 1_000).toFloat(), q = i * 100f))
    },
    /**
     * Optional per-chunk **adaptive** layer: FFT-based tone detector that
     * ADDS notch bands on top of the static [harmonics] / [numHarmonics]
     * config. `null` (default) = disabled, behaviour identical to the
     * legacy static-only mode. See [AdaptiveNotchDetector] for the actual
     * detection algorithm.
     */
    val adaptive: AdaptiveSettings? = null,
) {
    /** One explicit notch band: a target frequency and its Q. */
    data class Harmonic(val frequencyHz: Float, val q: Float)

    /**
     * Per-chunk adaptive-notch configuration. Three detection paths
     * (harmonic-series scan, narrowband Q-filtered peak scan, silence-based
     * learning) feed a temporal tracker that confirms tones over multiple
     * chunks before locking on. See [AdaptiveNotchDetector] for details.
     */
    data class AdaptiveSettings(
        /**
         * Always scan for integer harmonics of these fundamentals (Hz).
         * If ≥ [harmonicMinCount] harmonics of a fundamental are present in
         * the chunk, all detected harmonics are added — INCLUDING ones in
         * the voice band (formants don't form integer series at 50/60 Hz).
         */
        val harmonicFundamentals: List<Float> = listOf(50f, 60f, 100f, 120f),
        /** Minimum number of in-series peaks required to lock onto a fundamental. */
        val harmonicMinCount: Int = 3,
        /** Cap on harmonic order to search per fundamental. */
        val harmonicMaxOrder: Int = 80,
        /**
         * Narrowband peak scan: minimum Q (from −3 dB bandwidth) for an
         * isolated peak inside the voice band to qualify as a tone. Voice
         * formants have Q ≈ 5–30; tones typically Q > 100. Default 80 is
         * a safe middle ground.
         */
        val qThreshold: Float = 80f,
        /** Peak must be at least this much above local median magnitude (dB). */
        val prominenceDb: Float = 15f,
        /** Ignore peaks quieter than this absolute level (dBFS). */
        val minPeakDbFs: Float = -50f,
        /** Q applied to each adaptive notch — narrow so voice damage is minimal. */
        val notchQ: Float = 100f,
        /** Cap on the number of simultaneously-applied adaptive notches. */
        val maxBands: Int = 8,
        /** A bucket must be detected in this many consecutive chunks to be applied. */
        val stabilityChunks: Int = 2,
        /** Keep an applied bucket alive for this many chunks after it stops being detected. */
        val holdoverChunks: Int = 6,
        /** Treat chunks at or below this RMS (dBFS) as silence for the learning path. */
        val silenceRmsDbFs: Float = -45f,
        /** During silence, also scan the voice band for tones (otherwise voice-band-only Q gate applies). */
        val learnInVoiceBand: Boolean = true,
        /** Voice band lower edge (Hz) — peaks below this require no Q check. */

        val voiceBandLowHz: Float = 300f,
        /** Voice band upper edge (Hz) — peaks above this require no Q check. */
        val voiceBandHighHz: Float = 4_000f,
        /** Frequency bucketing for tracker (Hz). Detections within this distance share a bucket. */
        val bucketHz: Int = 5,
    )

    /** Validate mutually-exclusive configuration and resolve to NotchFilter bands. */
    internal fun resolveBands(): List<NotchFilter.Band> {
        val hasUniform = numHarmonics != null
        val hasExplicit = harmonics != null
        require(hasUniform xor hasExplicit) {
            "NotchConfig: set exactly ONE of `numHarmonics` (uniform mode) " +
                "or `harmonics` (per-harmonic mode); got numHarmonics=$numHarmonics, " +
                "harmonics=${harmonics?.size ?: "null"}."
        }
        return if (hasExplicit) {
            require(harmonics!!.isNotEmpty()) {
                "NotchConfig.harmonics must be non-empty in per-harmonic mode."
            }
            harmonics.map { NotchFilter.Band(it.frequencyHz, it.q) }
        } else {
            NotchFilter.harmonicsToBands(fundamentalHz, q, numHarmonics!!)
        }
    }

    /** Short human-readable summary for logs. */
    internal fun describe(): String = if (harmonics != null) {
        "explicit[${harmonics.joinToString(",") { "${it.frequencyHz.toInt()}Hz@Q${it.q.toInt()}" }}]"
    } else {
        "${fundamentalHz.toInt()}Hz/Q=${q.toInt()}/x${numHarmonics}"
    }




    companion object {
        fun getDefault(deviceType: RecordingDeviceType): NotchConfig? = when (deviceType) {
            RecordingDeviceType.JBOX_EXTERNAL -> NotchConfig(
                enabled = true,
                harmonics = mutableListOf<Harmonic>().also { list ->
                    list.add(Harmonic(frequencyHz = 375f, q = 50f))
                    for (i in 1..8) list.add(Harmonic(frequencyHz = (i * 1_000).toFloat(), q = i * 60f))
                }
            )
            RecordingDeviceType.JBOX_INTERNAL -> NotchConfig(
                enabled = true,
                harmonics = mutableListOf<Harmonic>().also { list ->
                    list.add(Harmonic(frequencyHz = 375f, q = 30f))
                    for (i in 1..8) list.add(Harmonic(frequencyHz = (i * 1_000).toFloat(), q = i * 50f))
                }
            )
            else -> null
        }
    }
}

