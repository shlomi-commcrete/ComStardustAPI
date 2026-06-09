package com.commcrete.stardust.util.audio

/**
 * Software multiband compressor + limiter config for
 * [DynamicsProcessingFilter]. Used by the live per-chunk filter chain in
 * [com.commcrete.stardust.util.audio.AudioFeederEngine] (test feeder) at
 * this position:
 *
 * ```
 * Declick → Notch → RNNoise → DynamicsProcessing → (AGC) → AI-gain → LPF
 * ```
 *
 * **Important**: DP runs **after** RNNoise. With a vanilla compressor +
 * make-up-gain config every band would lift the residual noise floor that
 * RNNoise just suppressed, undoing RNNoise's cleanup before the AI encoder
 * sees the signal. The defaults below are tuned for the **opposite**
 * intent — *voice-focus, DP-only*: keep voice intelligible, drive
 * everything else (HVAC rumble, USB hiss, room tone, RNNoise residue)
 * toward silence with downward expanders in every band, and have DP also
 * do the level work AGC normally would (so AGC can be disabled in the
 * chain — see [voiceFocusDpOnlyPreset]).
 *
 * Voice-focus DP-only defaults (post-RNNoise, AGC bypassed):
 *
 *  - Input gain: small static boost; the speech band's heavy post-gain
 *    handles the bulk of make-up.
 *  - Band 0 (sub-bass): −18 dB pre-gain + 8:1 expander below −50 dBFS —
 *    kills HVAC, table thump, mic handling.
 *  - Band 1 (speech): 2.5:1 compression above −22 dBFS (knee 6 dB) with
 *    a heavy post-gain so DP alone handles level control. 4:1 downward
 *    expander below −48 dBFS gates inter-word silence and any RNNoise
 *    residue. Soft consonants (~−30 to −42 dBFS) sit safely above the
 *    gate.
 *  - Band 2 (highs): −12 dB pre-gain + gentle 3:1 expander below
 *    −58 dBFS — chases residual hiss without smearing fricative tails
 *    of /s/ /sh/ /f/.
 *  - Limiter: −3 dBFS ceiling, 1 ms attack, 100 ms release, 20:1 — final
 *    safety net.
 *
 * Notes on cutoff conventions / sample-rate handling:
 *  - [Band.highEdgeHz] is the **upper edge** of each band (lower edge is
 *    the previous band's `highEdgeHz`, or 0 for band 0). Same convention
 *    as the HAL `MbcBand.cutoffFrequency`.
 *  - This filter runs at `LiveFilterChain.sampleRate` (source's native
 *    rate — 48 kHz for jbox WAVs, 24 kHz for already-decimated sources).
 *    Band edges that exceed Nyquist are silently clamped.
 */
data class DynamicsConfig(
    val enabled: Boolean = true,
    val inputGainDb: Float = 2f,
    val band0: Band = Band.subBassVoiceFocus(),
    val band1: Band = Band.speechVoiceFocusDpOnly(),
    val band2: Band = Band.highsVoiceFocus(),
    val limiter: Limiter = Limiter.defaultPreset(),
) {
    /**
     * One band of the multiband compressor. Lower edge is implicit (0 Hz for
     * the first band, the previous band's [highEdgeHz] for the others).
     */
    data class Band(
        /** Upper edge in Hz. Above Nyquist → clamped to Nyquist. */
        val highEdgeHz: Float,
        val attackMs: Float,
        val releaseMs: Float,
        /** Compression ratio above [thresholdDb]. 1 = no compression. */
        val ratio: Float,
        /** Compression threshold, dBFS (negative). */
        val thresholdDb: Float,
        /** Soft-knee width in dB centred on [thresholdDb]. 0 = hard knee. */
        val kneeWidthDb: Float,
        /** Downward-expander threshold, dBFS. Below this the [expanderRatio] kicks in. */
        val noiseGateDb: Float,
        /** Downward-expansion ratio below [noiseGateDb]. 1 = no expansion. */
        val expanderRatio: Float,
        /** Static gain applied BEFORE the dynamics stage, in dB. */
        val preGainDb: Float,
        /** Static gain applied AFTER the dynamics stage, in dB. */
        val postGainDb: Float,
    ) {
        companion object {
            /**
             * Voice-focus sub-bass band:
             *  - Heavy −18 dB static pre-gain on the band's input slice.
             *  - 8:1 downward expander below −50 dBFS — anything quiet
             *    in this range is rumble (HVAC, mic handling, footsteps,
             *    table thumps) so the gate kills it hard.
             *  - Narrow upper edge keeps male F0 (85–155 Hz) safely
             *    inside the speech band where it gets the proper
             *    compression / make-up treatment.
             */
            fun subBassVoiceFocus() = Band(
                highEdgeHz = 70f,
                attackMs = 5f,
                releaseMs = 80f,
                ratio = 1f,
                thresholdDb = 0f,
                kneeWidthDb = 0f,
                noiseGateDb = -50f,
                expanderRatio = 8f,
                preGainDb = -18f,
                postGainDb = 0f,
            )

            /**
             * Voice-focus speech band, DP-only mode. DP carries all the
             * level-control work itself (no AGC downstream).
             *
             *  - 2.5:1 compression above −22 dBFS with a 6 dB soft knee.
             *  - Heavy post-gain provides AGC-equivalent make-up so quiet
             *    and loud speakers both end up near the same level.
             *  - 4:1 downward expander below −48 dBFS gates inter-word
             *    silence and any RNNoise residue. Soft consonants
             *    (~−30 to −42 dBFS) sit safely above the gate.
             *  - Fast 5 / 35 ms attack/release: short enough that
             *    word-initial stop bursts (/p/ /t/ /k/, 5–20 ms) are
             *    not muted as they enter from a closed gate, but slow
             *    enough that the compressor doesn't pump on every
             *    syllable.
             *
             * Pair with [voiceFocusDpOnlyPreset] (or just use the no-arg
             * [DynamicsConfig] constructor — it builds exactly that).
             */
            fun speechVoiceFocusDpOnly() = Band(
                highEdgeHz = 4_500f,
                attackMs = 5f,
                releaseMs = 35f,
                ratio = 2.5f,
                thresholdDb = -22f,
                kneeWidthDb = 6f,
                noiseGateDb = -48f,
                expanderRatio = 4f,
                preGainDb = 0f,
                postGainDb = 13f,
            )

            /**
             * Voice-focus highs band (speech-band upper edge → Nyquist):
             *  - −12 dB static pre-gain — strong de-essing + lowered hiss.
             *  - Gentle 3:1 downward expander below −58 dBFS. The gate
             *    is intentionally low (well under typical fricative tail
             *    energy of −40 to −55 dBFS) so /s/, /sh/, /f/ tails decay
             *    naturally instead of being chopped.
             *  - 80 ms release smooths gate transitions across word
             *    boundaries so sibilance doesn't pump.
             */
            fun highsVoiceFocus() = Band(
                highEdgeHz = 24_000f,
                attackMs = 3f,
                releaseMs = 80f,
                ratio = 1f,
                thresholdDb = 0f,
                kneeWidthDb = 0f,
                noiseGateDb = -58f,
                expanderRatio = 3f,
                preGainDb = -12f,
                postGainDb = 0f,
            )
        }
    }

    /** Brick-wall limiter applied after the bands are summed. */
    data class Limiter(
        val thresholdDb: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val ratio: Float,
        val postGainDb: Float,
    ) {
        companion object {
            /** −1 dBFS ceiling, 1 ms attack, 50 ms release, 20:1. */
            fun defaultPreset() = Limiter(
                thresholdDb = -3f,
                attackMs = 1f,
                releaseMs = 100f,
                ratio = 20f,
                postGainDb = 0f,
            )
        }
    }

    /** Short human-readable summary for logs. */
    internal fun describe(): String = (
        "in=%+.1fdB | b0(<%.0fHz pre%+.0fdB exp%.1f@%.0fdB) | " +
            "b1(<%.0fHz %.1f:1@%.0fdB+%.0fdB exp%.1f@%.0fdB) | " +
            "b2(>%.0fHz pre%+.0fdB exp%.1f@%.0fdB) | " +
            "lim(%.0fdBFS %.1f:1)"
        ).format(
            inputGainDb,
            band0.highEdgeHz, band0.preGainDb, band0.expanderRatio, band0.noiseGateDb,
            band1.highEdgeHz, band1.ratio, band1.thresholdDb, band1.postGainDb,
            band1.expanderRatio, band1.noiseGateDb,
            band2.highEdgeHz.takeIf { it.isFinite() } ?: -1f,
            band2.preGainDb, band2.expanderRatio, band2.noiseGateDb,
            limiter.thresholdDb, limiter.ratio,
        ).replace(',', '.')

    companion object {
        /**
         * Voice-focus preset for **DP-only mode** (AGC disabled). This is
         * the only preset and is equivalent to the no-arg [DynamicsConfig]
         * constructor — it exists so callers can name the intent
         * explicitly. Aggressive multiband downward expansion + gentle
         * speech-band compression with heavy make-up gain so DP alone
         * handles level control. Designed to run **after** RNNoise so it
         * doesn't undo RNNoise's cleanup.
         *
         * Use when:
         *  - You're disabling AGC for CPU / latency reasons.
         *  - You want a more "broadcast-style" flat voice dynamic.
         *  - The chain feeds something with its own AGC downstream
         *    (e.g. a codec that normalizes).
         *
         * Do NOT pair with AGC enabled — the two compress in series and
         * over-flatten voice.
         */
        @Suppress("unused")
        fun voiceFocusDpOnlyPreset() = DynamicsConfig()

        fun getDefault(deviceType: RecordingDeviceType): DynamicsConfig? = when (deviceType) {
            RecordingDeviceType.JBOX_EXTERNAL -> DynamicsConfig(
                enabled = true,
                inputGainDb = 2f,
                band0 = Band.subBassVoiceFocus(),
                band1 = Band(
                    highEdgeHz = 4_500f,
                    attackMs = 5f,
                    releaseMs = 35f,
                    ratio = 2.5f,
                    thresholdDb = -22f,
                    kneeWidthDb = 6f,
                    noiseGateDb = -48f,
                    expanderRatio = 4f,
                    preGainDb = 0f,
                    postGainDb = 4f,
                ),
                band2 = Band.highsVoiceFocus(),
                limiter = Limiter.defaultPreset(),
            )
            RecordingDeviceType.JBOX_INTERNAL -> DynamicsConfig(
                enabled = true,
                inputGainDb = 2f,
                band0 = Band.subBassVoiceFocus(),
                band1 = Band.speechVoiceFocusDpOnly(),
                band2 = Band.highsVoiceFocus(),
                limiter = Limiter.defaultPreset(),
            )
            else -> null
        }
    }
}

