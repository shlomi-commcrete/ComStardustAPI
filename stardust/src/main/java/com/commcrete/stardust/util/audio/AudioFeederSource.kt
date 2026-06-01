package com.commcrete.stardust.util.audio

import java.io.File

/**
 * Describes a single audio file to inject into the [AudioTestFeeder] pipeline.
 *
 * @param file        WAV (RIFF/WAVE PCM 16-bit), raw PCM, or any container
 *                    Android's MediaCodec can decode (m4a / mp3 / aac / ogg /
 *                    flac / 3gp …).
 * @param label       Free-text label used in logs (e.g. "Pixel 6", "Samsung S22").
 * @param rawPcm      If true, [file] is treated as raw signed-16-bit-LE PCM
 *                    (no header).
 * @param rawSampleRate / [rawChannels]: only used when [rawPcm] is true.
 */
data class Source(
    val file: File,
    val label: String = file.nameWithoutExtension,
    val rawPcm: Boolean = false,
    val rawSampleRate: Int = AudioTestFeeder.TARGET_SAMPLE_RATE,
    val rawChannels: Int = AudioTestFeeder.TARGET_CHANNELS,
)


