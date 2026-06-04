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
) {
    /** One explicit notch band: a target frequency and its Q. */
    data class Harmonic(val frequencyHz: Float, val q: Float)

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
}

