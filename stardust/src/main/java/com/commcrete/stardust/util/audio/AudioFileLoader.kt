package com.commcrete.stardust.util.audio

import timber.log.Timber
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads any audio file the [AudioTestFeeder] accepts (WAV / raw PCM /
 * MediaCodec-decodable container) and converts it to mono 16-bit PCM at the
 * pipeline's [AudioTestFeeder.TARGET_SAMPLE_RATE].
 *
 * Pure data-layer code: no coroutine plumbing, no logging of high-level state,
 * no DSP analysis. The loader returns descriptive metadata in [AudioInfo]
 * plus the normalized PCM buffer so upstream layers can analyze and feed it.
 */
internal object AudioFileLoader {

    private const val TAG = "AudioTestFeeder"

    /** Container holding low-level PCM data extracted from a WAV / compressed file. */
    internal data class ParsedWav(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val byteRate: Int,
        val samples: ShortArray, // interleaved
        /** Non-null for files decoded through MediaCodec; identifies the actual container. */
        val mimeLabel: String? = null,
    )

    /**
     * Loads the file, parses header (or treats as raw PCM), down-mixes to mono,
     * resamples (linear) to [AudioTestFeeder.TARGET_SAMPLE_RATE] and returns 16-bit samples.
     */
    fun loadAndNormalize(source: Source): Pair<AudioInfo, ShortArray> {
        val (info, nativeRate, mono) = loadMono(source)
        val resampled = if (nativeRate == AudioTestFeeder.TARGET_SAMPLE_RATE) mono
            else AudioDsp.resamplePolyphase(mono, nativeRate, AudioTestFeeder.TARGET_SAMPLE_RATE)
        return info to resampled
    }

    /**
     * Loads the file, parses the header (or treats as raw PCM) and down-mixes
     * to mono **without resampling**. Returns the source's native sample rate
     * alongside the mono int16 PCM so callers that want to do their own
     * resampling (e.g. per-chunk, post-filter) can skip the upfront resample
     * pass that [loadAndNormalize] performs.
     *
     * @return Triple of (audio metadata, native sample rate in Hz, mono int16 PCM at that rate)
     */
    fun loadMono(source: Source): Triple<AudioInfo, Int, ShortArray> {
        val fileSize = source.file.length()
        return when {
            source.rawPcm -> {
                val raw = source.file.readBytes()
                val pcm = bytesToShortsLe(raw)
                val mono = AudioDsp.downmix(pcm, source.rawChannels)
                val info = AudioInfo(
                    source = source,
                    sampleRate = source.rawSampleRate,
                    channels = source.rawChannels,
                    bitsPerSample = 16,
                    audioFormat = 1,
                    totalSamples = mono.size,
                    durationMs = mono.size * 1000L / source.rawSampleRate,
                    fileSizeBytes = fileSize,
                    byteRate = source.rawSampleRate * source.rawChannels * 2,
                    containerLabel = "RAW PCM",
                )
                Triple(info, source.rawSampleRate, mono)
            }
            isWavFile(source.file) -> {
                val parsed = parseWav(source.file)
                val mono = AudioDsp.downmix(parsed.samples, parsed.channels)
                val info = AudioInfo(
                    source = source,
                    sampleRate = parsed.sampleRate,
                    channels = parsed.channels,
                    bitsPerSample = parsed.bitsPerSample,
                    audioFormat = parsed.audioFormat,
                    totalSamples = parsed.samples.size / parsed.channels,
                    durationMs = (parsed.samples.size / parsed.channels) * 1000L / parsed.sampleRate,
                    fileSizeBytes = fileSize,
                    byteRate = parsed.byteRate,
                    containerLabel = "WAV/RIFF",
                )
                Triple(info, parsed.sampleRate, mono)
            }
            else -> {
                val parsed = decodeCompressedAudio(source.file)
                val mono = AudioDsp.downmix(parsed.samples, parsed.channels)
                val info = AudioInfo(
                    source = source,
                    sampleRate = parsed.sampleRate,
                    channels = parsed.channels,
                    bitsPerSample = parsed.bitsPerSample,
                    audioFormat = parsed.audioFormat,
                    totalSamples = parsed.samples.size / parsed.channels,
                    durationMs = if (parsed.sampleRate > 0)
                        (parsed.samples.size / parsed.channels) * 1000L / parsed.sampleRate else 0L,
                    fileSizeBytes = fileSize,
                    byteRate = parsed.byteRate,
                    containerLabel = "Compressed (${parsed.mimeLabel ?: "MediaCodec"})",
                )
                Triple(info, parsed.sampleRate, mono)
            }
        }
    }

    /** Quick check whether [file] is a RIFF/WAVE container. */
    private fun isWavFile(file: File): Boolean {
        return try {
            val head = ByteArray(12)
            FileInputStream(file).use { it.read(head) }
            String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE"
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------- WAV parsing ----------------

    private fun parseWav(file: File): ParsedWav {
        DataInputStream(FileInputStream(file)).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4) == "RIFF") { "Not a RIFF file: ${file.name}" }
            require(String(header, 8, 4) == "WAVE") { "Not a WAVE file: ${file.name}" }

            var fmtFound = false
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0       // 1=PCM, 3=IEEE float, 0xFFFE=EXTENSIBLE
            var subFormatCode = -1    // resolved sub-format for EXTENSIBLE
            var byteRate = 0
            var samples: ShortArray = ShortArray(0)

            while (true) {
                val chunkHeader = ByteArray(8)
                val read = input.read(chunkHeader)
                if (read < 8) break
                val id = String(chunkHeader, 0, 4)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size)
                        input.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        byteRate = bb.int
                        /* blockAlign */ bb.short
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        if (audioFormat == 0xFFFE && size >= 40) {
                            bb.position(bb.position() + 8)
                            subFormatCode = bb.short.toInt() and 0xFFFF
                        }
                        fmtFound = true
                    }
                    "data" -> {
                        require(fmtFound) { "WAV 'data' chunk before 'fmt ' in ${file.name}" }
                        val effectiveFmt = if (audioFormat == 0xFFFE) subFormatCode else audioFormat
                        val data = ByteArray(size)
                        input.readFully(data)
                        samples = when {
                            effectiveFmt == 1 && bitsPerSample == 8 -> bytes8uToShorts(data)
                            effectiveFmt == 1 && bitsPerSample == 16 -> bytesToShortsLe(data)
                            effectiveFmt == 1 && bitsPerSample == 24 -> bytes24LeToShorts(data)
                            effectiveFmt == 1 && bitsPerSample == 32 -> bytes32IntLeToShorts(data)
                            effectiveFmt == 3 && bitsPerSample == 32 -> bytes32FloatLeToShorts(data)
                            else -> error("Unsupported WAV format=$effectiveFmt bits=$bitsPerSample in ${file.name}")
                        }
                        return ParsedWav(sampleRate, channels, bitsPerSample, effectiveFmt, byteRate, samples)
                    }
                    else -> {
                        var toSkip = size.toLong()
                        while (toSkip > 0) {
                            val s = input.skip(toSkip)
                            if (s <= 0) break
                            toSkip -= s
                        }
                    }
                }
            }
            error("No 'data' chunk found in ${file.name}")
        }
    }

    // ---------------- Compressed-audio decoding via MediaExtractor + MediaCodec ----------------

    private fun decodeCompressedAudio(file: File): ParsedWav {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalArgumentException(
                "Cannot open '${file.name}' as a media container: ${t.message}", t
            )
        }

        var audioTrack = -1
        var inputFormat: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                inputFormat = f
                break
            }
        }
        if (audioTrack < 0 || inputFormat == null) {
            extractor.release()
            error("No audio track found in '${file.name}'")
        }
        extractor.selectTrack(audioTrack)

        val mime = inputFormat.getString(android.media.MediaFormat.KEY_MIME)!!
        val sampleRate = inputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

        inputFormat.setInteger(
            android.media.MediaFormat.KEY_PCM_ENCODING,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )

        val codec = try {
            android.media.MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalStateException("No decoder for $mime (file ${file.name}): ${t.message}", t)
        }

        val pcmOut = java.io.ByteArrayOutputStream()
        val info = android.media.MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var outputPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val timeoutUs = 10_000L

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                            ?: error("Null input buffer at index $inIdx")
                        inBuf.clear()
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                                ?: error("Null output buffer at index $outIdx")
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            pcmOut.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                            outputPcmEncoding = newFormat
                                .getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val pcmBytes = pcmOut.toByteArray()
        val samples: ShortArray = when (outputPcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_16BIT -> bytesToShortsLe(pcmBytes)
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> bytes32FloatLeToShorts(pcmBytes)
            android.media.AudioFormat.ENCODING_PCM_8BIT  -> bytes8uToShorts(pcmBytes)
            else -> bytesToShortsLe(pcmBytes)
        }

        Timber.tag(TAG).d(
            "Decoded '%s' via MediaCodec (%s): %d Hz, %d ch, %d samples (%d ms)",
            file.name, mime, sampleRate, channels, samples.size / max(channels, 1),
            if (sampleRate > 0) (samples.size / max(channels, 1)) * 1000L / sampleRate else 0L
        )

        return ParsedWav(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = 16,
            audioFormat = 1,
            byteRate = sampleRate * channels * 2,
            samples = samples,
            mimeLabel = mime,
        )
    }

    // ---------------- Byte → Short converters ----------------

    fun bytesToShortsLe(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in out.indices) out[i] = bb.short
        return out
    }

    /** 8-bit WAV is unsigned (0..255, 128 = silence). Convert to signed 16-bit. */
    private fun bytes8uToShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size)
        for (i in bytes.indices) {
            val u = bytes[i].toInt() and 0xFF
            out[i] = (((u - 128) shl 8)).toShort()
        }
        return out
    }

    /** 24-bit signed little-endian PCM → 16-bit (drop low byte). */
    private fun bytes24LeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 3
        val out = ShortArray(n)
        for (i in 0 until n) {
            val b0 = bytes[i * 3].toInt() and 0xFF
            val b1 = bytes[i * 3 + 1].toInt() and 0xFF
            val b2 = bytes[i * 3 + 2].toInt()
            val s24 = (b2 shl 16) or (b1 shl 8) or b0
            out[i] = (s24 shr 8).toShort()
        }
        return out
    }

    /** 32-bit signed little-endian PCM → 16-bit. */
    private fun bytes32IntLeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 4
        val out = ShortArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) out[i] = (bb.int shr 16).toShort()
        return out
    }

    /** 32-bit IEEE float (-1.0..+1.0) → 16-bit. */
    private fun bytes32FloatLeToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 4
        val out = ShortArray(n)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val f = bb.float.coerceIn(-1f, 1f)
            out[i] = (f * 32_767f).roundToInt().toShort()
        }
        return out
    }
}



