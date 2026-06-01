package com.commcrete.stardust.util.audio

import java.io.File

/**
 * Result of running an audio source through `WavTokenizerEncoder.encode()`
 * followed by `WavTokenizerDecoder.decode()` and comparing the reconstructed
 * PCM to the input.
 *
 * **Primary verdict:** [logSpectralDistanceDb] (phase-invariant — appropriate
 * for iSTFT-based neural codecs like WavTokenizer). [psnrDb] / [siSdrDb] are
 * **informational only** for this codec class — high PSNR + very negative
 * SI-SDR is the typical fingerprint of a spectrally-correct but
 * phase-shifted reconstruction.
 *
 * Other useful metrics:
 *  - [tokenDiversity]               – unique tokens / 4096. Healthy speech
 *                                     uses ~5–20 %. <1 % ⇒ codebook collapse.
 *  - [tokensPerSecond]              – should be ≈ 40 for WavTokenizer-large.
 *  - [realTimeFactor]               – `(encode+decode) / audio_duration`.
 *                                     <1 ⇒ faster than realtime.
 *  - [alignmentLagSamples]          – lag in samples found by cross-correlation
 *                                     between input and reconstruction; quantifies
 *                                     the codec's algorithmic delay.
 *  - [perBandSpectralDistortionDb]  – dB difference per [AudioTestFeeder.BAND_LABELS]
 *                                     band between input and reconstructed
 *                                     spectra. Positive ⇒ codec added energy
 *                                     ("hallucinated"); negative ⇒ codec lost energy.
 */
data class RoundTripResult(
    val label: String,
    val inputSamples: Int,
    val reconstructedSamples: Int,
    val chunks: Int,
    val tokens: Int,
    val uniqueTokens: Int,
    val tokenDiversity: Double,
    val tokensPerSecond: Double,
    val avgEncodeMs: Double,
    val avgDecodeMs: Double,
    val realTimeFactor: Double,
    val logSpectralDistanceDb: Double,
    val psnrDb: Double,
    val siSdrDb: Double,
    val alignmentLagSamples: Int,
    val perBandSpectralDistortionDb: DoubleArray,
    val error: String?,
    /** Saved artifacts (null if writing failed or the feature was disabled). */
    val tokensTxtFile: File?,
    val tokensBinFile: File?,
    val decodedWavFile: File?,
    val originalNormalizedWavFile: File?,
)

