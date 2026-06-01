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
    fun resampleLinear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return input
        if (input.isEmpty()) return input
        val filtered = if (srcRate > dstRate) antiAliasLowPass(input, srcRate, dstRate) else input
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = (filtered.size * ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(filtered.size - 1)
            val frac = srcPos - i0
            val s = filtered[i0] * (1 - frac) + filtered[i1] * frac
            out[i] = s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * Anti-aliasing low-pass filter applied prior to downsampling. Hann-windowed
     * sinc, fixed 63 taps, cutoff at 95 % of `dstRate / 2`.
     */
    fun antiAliasLowPass(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (input.size < 64) return input
        val taps = 63
        val half = taps / 2
        val cutoffHz = (dstRate.toDouble() / 2.0) * 0.95
        val normCutoff = cutoffHz / srcRate.toDouble()
        val kernel = DoubleArray(taps)
        var ksum = 0.0
        for (n in 0 until taps) {
            val k = n - half
            val sinc = if (k == 0) {
                2.0 * normCutoff
            } else {
                val x = 2.0 * Math.PI * normCutoff * k
                Math.sin(x) / (Math.PI * k.toDouble())
            }
            val hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / (taps - 1))
            val v = sinc * hann
            kernel[n] = v
            ksum += v
        }
        if (ksum > 0.0) for (n in 0 until taps) kernel[n] = kernel[n] / ksum

        val out = ShortArray(input.size)
        val n = input.size
        for (i in 0 until n) {
            var acc = 0.0
            for (k in 0 until taps) {
                val j = (i + k - half).coerceIn(0, n - 1)
                acc += kernel[k] * input[j].toDouble()
            }
            out[i] = acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
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



