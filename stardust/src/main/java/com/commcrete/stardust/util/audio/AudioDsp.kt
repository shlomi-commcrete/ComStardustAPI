package com.commcrete.stardust.util.audio

import kotlin.math.roundToInt

/**
 * DSP primitives shared by the [AudioTestFeeder] pipeline:
 *  - mono down-mix,
 *  - linear / band-limited resampling,
 *  - AI-gain application (mirroring `AudioRecorderAI.processSamples`),
 *  - radix-2 in-place FFT and the Hann window used by spectral analysis.
 *
 * Pure functions, no I/O, no Android dependencies.
 */
internal object AudioDsp {

    /** Window size used by every FFT-based analysis path. Must be a power of 2. */
    const val FFT_SIZE = 2048

    /** Hard cap on samples processed by FFT analysis (~30 s at 24 kHz). Keeps cost bounded. */
    val FFT_MAX_SAMPLES: Int = 30 * AudioTestFeeder.TARGET_SAMPLE_RATE

    /** Pre-computed Hann window of size [FFT_SIZE]. */
    val hannWindow: DoubleArray by lazy {
        DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)) }
    }

    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c].toInt()
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /**
     * Apply the same AI gain that `AudioRecorderAI.processSamples()` applies —
     * multiply each sample by [gain] and clamp to int16 range. Returns a fresh
     * ShortArray of [length] samples so callers can safely reuse the source buffer.
     */
    fun applyAiGain(source: ShortArray, length: Int, gain: Float): ShortArray {
        val out = ShortArray(length)
        if (gain == 1f) {
            System.arraycopy(source, 0, out, 0, length)
            return out
        }
        for (i in 0 until length) {
            val v = (source[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = v.toShort()
        }
        return out
    }

    /**
     * In-place variant of [applyAiGain] for callers that already own the
     * destination buffer (e.g. the live filter chain in [AudioFeederEngine]).
     * Same scaling + int16 clamp semantics; no-op when [gain] is exactly 1f.
     */
    fun applyAiGainInPlace(buffer: ShortArray, gain: Float) {
        if (gain == 1f) return
        for (i in buffer.indices) {
            val v = (buffer[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = v.toShort()
        }
    }

    /**
     * Soft-saturating variant of [applyAiGainInPlace] using a tanh curve.
     * Same level boost as the linear path but **never hard-clips** — peaks
     * roll off smoothly toward ±1 instead of getting square-wave clipped.
     *
     * Behaviour vs. linear, sample-by-sample:
     *
     *  - For samples below ~30 % of full scale (|x_norm| < 0.3) the curve
     *    is indistinguishable from linear gain → quiet content unaffected.
     *  - Past ~50 % of full scale tanh starts compressing → loud peaks get
     *    rounded instead of clipped.
     *  - At very high drive (gain ≥ 4–5) the curve approaches a soft
     *    limiter → no output sample exceeds ±32767 even when the linear
     *    path would have clipped massively.
     *
     * Result on voice fed at high `gain` (e.g. 5x = +14 dB): output level
     * is hot (similar to hard-clipped) but the spectrum gains gentle even
     * harmonics ("warmth") instead of harsh odd harmonics ("buzz"). The
     * AI encoder sees a hot signal without the brittle clipping artifacts.
     *
     * No-op when [gain] is exactly 1f.
     */
    fun applyAiGainSoftSatInPlace(buffer: ShortArray, gain: Float) {
        if (gain == 1f) return
        val scale = Short.MAX_VALUE.toFloat()
        for (i in buffer.indices) {
            // Normalize → drive → tanh → denormalize. Tanh inherently
            // saturates within ±1 so the int16 clamp is just a belt-and-
            // braces safety net (won't trigger in normal operation).
            val xNorm = buffer[i] / scale
            val sat = kotlin.math.tanh(gain * xNorm)
            val v = (sat * scale).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = v.toShort()
        }
    }

    /**
     * Resampler used to normalize any input rate to the target rate before
     * tokenization.
     *
     * Two regimes:
     *  - **Upsampling** (`srcRate < dstRate`): plain linear interpolation. No
     *    aliasing risk going up.
     *  - **Downsampling** (`srcRate > dstRate`): a Hann-windowed-sinc FIR
     *    low-pass at ~95 % of the new Nyquist is run BEFORE linear
     *    interpolation. Without this pre-filter, energy between the new and
     *    old Nyquist folds back into the speech band.
     */
    /**
     * Polyphase FIR resampler with Kaiser-windowed sinc kernel and
     * pre-computed coefficient table. Comparable to Windows 11's
     * built-in sample rate converter.
     *
     * ## Quality
     *
     *  - **Kaiser window** (β = 9.0) → >80 dB stopband attenuation
     *    (vs. ~40 dB with Hann). Alias artifacts are inaudible.
     *  - **Pre-computed table** of `NUM_PHASES × tapsPerPhase` coefficients
     *    — the inner loop is pure multiply-accumulate, no trig functions.
     *  - **Normalized coefficients** — unity passband gain guaranteed.
     *
     * ## Anti-aliasing
     *
     * Cutoff is `min(1, dstRate/srcRate) × 0.95`. When downsampling,
     * the sinc kernel suppresses frequencies above the destination
     * Nyquist — no separate pre-filter needed.
     *
     * ## Performance
     *
     * 500 ms chunk 48→8 kHz: 4000 output samples × 193 taps ≈ 772 k
     * multiply-adds (table lookup only, no trig). Trivial on ARM.
     *
     * The table is lazily built and cached per (srcRate, dstRate) pair
     * via [getOrBuildKernel].
     */
    fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty()) return input

        val inLen = input.size
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = (inLen * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)

        val kernel = getOrBuildKernel(srcRate, dstRate)
        val halfLen = kernel.halfLen
        val table = kernel.table
        val numPhases = kernel.numPhases

        for (i in 0 until outLen) {
            val center = i / ratio
            val centerInt = center.toInt()
            val frac = center - centerInt

            // Pick the nearest pre-computed phase.
            val phaseIdx = (frac * numPhases + 0.5).toInt().coerceIn(0, numPhases - 1)
            val phaseOff = phaseIdx * kernel.tapsPerPhase

            var acc = 0.0
            val jMin = maxOf(-halfLen, -centerInt)
            val jMax = minOf(halfLen, inLen - 1 - centerInt)

            for (j in jMin..jMax) {
                val tapIdx = j + halfLen  // 0-based tap index
                acc += input[centerInt + j].toDouble() * table[phaseOff + tapIdx]
            }

            out[i] = acc.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    // ── Polyphase kernel cache ──────────────────────────────────────────

    /** Number of fractional phases in the polyphase table. */
    private const val NUM_PHASES = 256

    /** Kaiser window β. 9.0 → ~80 dB stopband. */
    private const val KAISER_BETA = 9.0

    private data class PolyphaseKernel(
        val halfLen: Int,
        val tapsPerPhase: Int,   // = 2 * halfLen + 1
        val numPhases: Int,
        val table: DoubleArray,  // [numPhases × tapsPerPhase], pre-normalized
    )

    private val kernelCache = HashMap<Long, PolyphaseKernel>()

    /** Clear cached resampler kernels. Call only if memory pressure requires it. */
    fun clearResamplerCache() { kernelCache.clear() }

    private fun getOrBuildKernel(srcRate: Int, dstRate: Int): PolyphaseKernel {
        val key = srcRate.toLong() shl 32 or dstRate.toLong()
        kernelCache[key]?.let { return it }
        val kernel = buildPolyphaseKernel(srcRate, dstRate)
        kernelCache[key] = kernel
        return kernel
    }

    private fun buildPolyphaseKernel(srcRate: Int, dstRate: Int): PolyphaseKernel {
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val cutoff = minOf(1.0, ratio) * 0.95
        val halfLen = (16.0 / cutoff).toInt().coerceIn(16, 128)
        val taps = 2 * halfLen + 1
        val i0BesselBeta = i0(KAISER_BETA)

        val table = DoubleArray(NUM_PHASES * taps)
        for (p in 0 until NUM_PHASES) {
            val frac = p.toDouble() / NUM_PHASES
            var sum = 0.0
            val off = p * taps
            for (t in 0 until taps) {
                val j = t - halfLen
                val x = j.toDouble() - frac

                // Sinc
                val sinc = if (x * x < 1e-12) cutoff
                else {
                    val pxc = Math.PI * x * cutoff
                    kotlin.math.sin(pxc) / (Math.PI * x)
                }

                // Kaiser window
                val wArg = x / (halfLen + 1)
                val win = if (wArg * wArg >= 1.0) 0.0
                else i0(KAISER_BETA * kotlin.math.sqrt(1.0 - wArg * wArg)) / i0BesselBeta

                val w = sinc * win
                table[off + t] = w
                sum += w
            }
            // Normalize so passband gain = 1.0
            if (sum > 1e-12) {
                for (t in 0 until taps) table[off + t] /= sum
            }
        }
        return PolyphaseKernel(halfLen, taps, NUM_PHASES, table)
    }

    /**
     * Zeroth-order modified Bessel function of the first kind.
     * Used by the Kaiser window. Converges in ~15-20 terms for β ≤ 12.
     */
    private fun i0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val halfX = x / 2.0
        for (k in 1..25) {
            term *= (halfX / k)
            val termSq = term * term
            sum += termSq
            if (termSq < sum * 1e-16) break
        }
        return sum
    }

    /**
     * Periodic Hann window of size [FFT_SIZE]. Uses `2πi/N` (not `2πi/(N-1)`)
     * so the COLA property holds exactly at 50 % overlap:
     * `w[n]² + w[n + N/2]² = 1.0` for all `n`.
     *
     * Use this for overlap-add synthesis (e.g. [SpectralSubtractionFilter]);
     * [hannWindow] (symmetric variant) is kept for the existing analysis paths.
     */
    val hannWindowPeriodic: DoubleArray by lazy {
        DoubleArray(FFT_SIZE) { i -> 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / FFT_SIZE) }
    }

    /**
     * In-place inverse FFT. Conjugate → forward FFT → conjugate → scale
     * by 1/N. Same constraints as [fftInPlace]: [re].size must be a power
     * of 2 and equal to [im].size.
     */
    fun ifftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        for (i in 0 until n) im[i] = -im[i]
        fftInPlace(re, im)
        val invN = 1.0 / n
        for (i in 0 until n) {
            re[i] *= invN
            im[i] = -im[i] * invN
        }
    }

    /** Iterative radix-2 Cooley-Tukey FFT, in-place. [re].size must be a power of 2. */
    fun fftInPlace(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = kotlin.math.cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = curRe * re[i + k + half] - curIm * im[i + k + half]
                    val tIm = curRe * im[i + k + half] + curIm * re[i + k + half]
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + half] = uRe - tRe
                    im[i + k + half] = uIm - tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}



