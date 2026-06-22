package com.commcrete.stardust.util.audio

/**
 * Optional impulsive-noise removal stage applied **FIRST** in the live DSP
 * chain (before notch / RNNoise / DP / AGC / AI-gain / LPF). De-clicks the
 * audio so downstream stateful filters never see the spikes — which is
 * critical because a single-sample tick causes:
 *
 *  - Notch biquads to ring for ~50 ms ("boing" artifact)
 *  - RNNoise to misclassify a 10 ms frame → output artifacts for ~200 ms
 *  - DP / AGC envelope followers to slam → audible "duck" lasting one
 *    release-time (~80–250 ms)
 *
 * Killing the tick before the chain sees it makes all of those problems
 * disappear.
 *
 * ## Detection (two paths combined)
 *
 *  1. **Amplitude deviation** — `dev = |x − median|` over a small window
 *     (size [medianWindow]) centred on the current sample. Catches samples
 *     that are far from the local "normal" amplitude.
 *  2. **Derivative jump** *(when [useDerivativeDetection])* — absolute
 *     first difference between adjacent samples. Catches **wave kinks** —
 *     sudden slope discontinuities that may not produce a large absolute
 *     peak but break the smoothness of the wave.
 *
 * Both are compared against their own rolling median absolute deviation
 * (MAD), smoothed across chunks via EMA. A sample is a *candidate tick*
 * when **either** signal exceeds `thresholdMad × MAD` AND its absolute
 * amplitude is above `minPeakLin`.
 *
 * ## Region expansion
 *
 * After locating a candidate peak, the run is extended left and right
 * while neighbouring samples are still ≥ `thresholdMad × expansionFraction × MAD`
 * (default 50 % of the main threshold). This catches the **overshoot**
 * and **ringing tail** of the anomaly so the repair endpoints land on a
 * truly clean sample — without expansion, a hard click would leave a
 * lopsided "half-repaired" wave that still has wonky derivatives at the
 * boundary.
 *
 * Expansion is capped by [maxTickSamples] — runs that grow past the cap
 * are deemed "real audio loss" and left untouched.
 *
 * ## Repair (continuous-wave guarantee)
 *
 * Detected ticks are replaced with smooth interpolation:
 *
 *  - **≤ [splineMaxLength] samples** → **Hermite cubic spline** with
 *    endpoint tangents computed from the **clean side only** (does not
 *    cross the tick). The repaired curve passes exactly through the
 *    clean endpoints AND matches their slopes → C¹-continuous wave with
 *    no derivative discontinuity at either boundary.
 *  - **≤ [maxTickSamples] samples** → linear interpolation **plus** a
 *    short Hann edge crossfade (a few samples) that blends from the
 *    clean-extrapolated curve into the linear interior. Restores
 *    tangent continuity at the boundaries even though the interior is
 *    straight.
 *  - **> [maxTickSamples]** → not a "tick", treated as legitimate audio
 *    loss (e.g. packet drop). Left untouched; fabricating > 1 ms of
 *    audio creates worse artifacts than the original loss.
 *
 * ## Voice safety
 *
 * Real voice transients (plosives /p/, /t/, /k/) look superficially like
 * ticks. Two guardrails:
 *
 *  - **MAD-relative threshold** — during loud speech, MAD grows so
 *    plosives don't cross the threshold. During silence, MAD shrinks so
 *    the tiniest bit-error spike does.
 *  - **[minPeakDbFs]** — absolute level gate. Plosives at conversational
 *    level sit around −20 to −10 dBFS; pathological USB ticks come in
 *    at 0 dBFS / near full scale. Default −20 dBFS catches the latter
 *    without flagging the former.
 */
data class DeclickConfig(
    val enabled: Boolean = false,
    /**
     * Detection threshold as a multiple of the rolling MAD.
     *  - `4` — aggressive (catches subtle ticks, may flag some loud
     *    transients)
     *  - `6` — default, voice-safe
     *  - `8` — conservative (only obvious ticks)
     */
    val thresholdMad: Float = 7f,
    /**
     * Absolute minimum peak (dBFS) for a candidate tick. Anything quieter
     * is ignored. Prevents noise-floor wiggles and tiny dither flickers
     * from triggering interpolation. Default `−20 dBFS` keeps voice
     * plosives safe.
     */
    val minPeakDbFs: Float = -18f,
    /**
     * Maximum consecutive samples (after region expansion) treated as a
     * single tick. Longer runs are deemed "real audio loss" and left alone.
     */
    val maxTickSamples: Int = 80,
    /** Use Hermite cubic spline for ticks up to this length; linear interpolation beyond. */
    val splineMaxLength: Int = 8,
    /**
     * Median-filter window size (odd, 5–9). Reference signal for
     * computing per-sample deviation. Larger = more robust against runs
     * of contiguous ticks, but adds latency / boundary loss.
     */
    val medianWindow: Int = 9,
    /**
     * EMA smoothing for the per-chunk MAD estimate. Larger window = MAD
     * tracks signal statistics more slowly (good when ticks come in
     * bursts; bad when signal levels change a lot).
     */
    val madSmoothingSamples: Int = 48_000,
    /** Emit a Timber debug line per chunk with tick count and positions. */
    val logDetections: Boolean = false,
    /**
     * Detect anomalies via per-sample first-difference jumps in addition
     * to amplitude deviation. Catches **wave kinks** — slope discontinuities
     * that don't necessarily produce a large absolute peak. Recommended `true`
     * for voice / smooth signals.
     */
    val useDerivativeDetection: Boolean = false,
    /**
     * Region-expansion threshold as a fraction of the main threshold.
     * After detecting a candidate above `thresholdMad × MAD`, walks left
     * and right while neighbouring samples still exceed
     * `thresholdMad × expansionFraction × MAD`. `0.5` (default) means
     * "expand while still half-abnormal". Set to `0.0` to disable
     * expansion (only the strict-threshold samples are repaired).
     */
    val expansionFraction: Float = 0.2f,
) {
    /** Short human-readable summary for logs. */
    internal fun describe(): String =
        "thr=%.1f×MAD/min=%.0fdBFS/max=%dsamp/spline≤%d/medWin=%d/dDet=%s/exp=%.2f".format(
            thresholdMad, minPeakDbFs, maxTickSamples, splineMaxLength, medianWindow,
            if (useDerivativeDetection) "on" else "off", expansionFraction,
        ).replace(',', '.')

    companion object {
        fun getDefault(deviceType: RecordingDeviceType): DeclickConfig = when (deviceType) {
            RecordingDeviceType.JBOX_INTERNAL -> DeclickConfig(enabled = true)
            // Phone mic: buffer-underrun clicks (~4600/file observed).
            RecordingDeviceType.PHONE_MIC -> DeclickConfig(enabled = true)
            // JBOX_EXTERNAL: no USB click artifacts from external mic path.
            else -> DeclickConfig(enabled = false)
        }
    }
}


