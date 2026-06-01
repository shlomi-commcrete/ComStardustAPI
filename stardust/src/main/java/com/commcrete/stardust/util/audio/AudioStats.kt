package com.commcrete.stardust.util.audio

/**
 * Per-file PCM statistics computed by [AudioStatsAnalyzer] after the input
 * has been normalized to [AudioTestFeeder.TARGET_SAMPLE_RATE] mono 16-bit.
 *
 *  - [peak] / [peakDbFs]              – maximum absolute sample / its dBFS.
 *  - [rms] / [rmsDbFs]                – signal RMS / its dBFS.
 *  - [dcOffset]                       – mean sample value (≠ 0 ⇒ DC bias).
 *  - [zeroCrossingRate]               – per sample.
 *  - [silenceRatio]                   – share of samples whose `|x| < 200`.
 *  - [clippedSampleRatio]             – share of samples at `±32700`-ish.
 *  - [effectiveBitsUsed]              – 1..16, detects truncated/padded depth.
 *  - [longestZeroRunMs]               – long zero-runs ⇒ dropouts / stalls.
 *  - [longestRepeatRunMs]             – long identical-sample runs ⇒ PLC /
 *                                       USB stall / muted ADC.
 *  - [highBandEnergyRatio]            – proxy for bandwidth (energy of 1st-
 *                                       order diff vs total).
 *  - [bandwidthHint]                  – textual classification of the above.
 *  - [possibleRawByteIssue]           – non-null if a raw-PCM file looks
 *                                       like wrong endian / unsigned.
 *  - [subBandEnergyPct]               – energy distribution across
 *                                       [AudioTestFeeder.BAND_LABELS] (% summing
 *                                       to 100), via FFT on Hann-windowed
 *                                       2048-sample frames (50% overlap).
 *  - [spectralFlatness]               – Wiener entropy: 0 ≈ pure tone,
 *                                       1 ≈ white noise. Speech ≈ 0.05–0.30.
 *  - [dominantFreqHz]                 – Hz of the dominant FFT bin.
 *  - [dominantBinEnergyPct]           – energy of the dominant bin as % of
 *                                       total spectral energy.
 *  - [peakToMedianDb]                 – peak-to-median magnitude ratio in dB.
 *                                       >25 dB ⇒ very narrow / tonal.
 *  - [toneAlert]                      – non-null when a sustained
 *                                       narrow-band tone ("piiii") is found.
 */
data class AudioStats(
    val sampleCount: Int,
    val peak: Int,
    val peakDbFs: Double,
    val rms: Double,
    val rmsDbFs: Double,
    val dcOffset: Double,
    val zeroCrossingRate: Double,
    val silenceRatio: Double,
    val clippedSampleRatio: Double,
    val effectiveBitsUsed: Int,
    val longestZeroRunMs: Int,
    val longestRepeatRunMs: Int,
    val highBandEnergyRatio: Double,
    val bandwidthHint: String,
    val possibleRawByteIssue: String?,
    val subBandEnergyPct: DoubleArray,
    val spectralFlatness: Double,
    val dominantFreqHz: Double,
    val dominantBinEnergyPct: Double,
    val peakToMedianDb: Double,
    val toneAlert: String?,
)

