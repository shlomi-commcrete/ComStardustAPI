package com.commcrete.stardust.util.audio.tester

/**
 * Detailed metadata extracted from an input file by [AudioFileLoader].
 *
 *  - [audioFormat] is the WAVE format code (1 = PCM, 3 = IEEE float, 0xFFFE
 *    for EXTENSIBLE). For non-WAV containers it is normalised to 1 (PCM).
 *  - [totalSamples] is the per-channel sample count after stripping the
 *    container header.
 *  - [containerLabel] is a human-readable container name for logs:
 *    "WAV/RIFF", "RAW PCM", or e.g. "Compressed (audio/mp4a-latm)".
 */
data class AudioInfo(
    val source: Source,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val audioFormat: Int,
    val totalSamples: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val byteRate: Int,
    val containerLabel: String = "WAV/RIFF",
)