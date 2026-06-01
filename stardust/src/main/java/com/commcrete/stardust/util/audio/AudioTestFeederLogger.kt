package com.commcrete.stardust.util.audio

import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formats and emits all human-readable diagnostics from the test feeder:
 *  - per-file audio info / PCM stats / spectral fingerprint,
 *  - WavTokenizer suitability verdict (using [WtThresholds]),
 *  - per-file round-trip metrics,
 *  - cross-source comparison tables.
 *
 * Pure formatting / Timber logging. No analysis, no I/O on data files.
 */
internal object AudioTestFeederLogger {

    private const val TAG = "AudioTestFeeder"

    /**
     * Heuristic "happy zone" thresholds derived from WavTokenizer training data
     * (LibriTTS / CommonVoice / LJSpeech / AudioSet @ 24 kHz mono 16-bit).
     */
    private object WtThresholds {
        const val RMS_MIN_DBFS = -32.0
        const val RMS_MAX_DBFS = -6.0
        const val RMS_IDEAL_LOW = -24.0
        const val RMS_IDEAL_HIGH = -12.0
        const val PEAK_MAX_DBFS = -0.5
        const val DC_MAX = 80.0
        const val MIN_HIGHBAND_PCT = 5.0
        const val MIN_BITS_USED = 12
        const val MAX_REPEAT_RUN_MS = 60
        const val MAX_ZERO_RUN_MS = 120
        const val MAX_SILENCE_RATIO = 0.80
        const val MAX_CLIP_RATIO = 0.001
    }

    fun logAudioInfo(info: AudioInfo) {
        Timber.tag(TAG).i(
            """
            ╭─ Audio file info ──────────────────────────────
            │ label            : %s
            │ path             : %s
            │ size on disk     : %d bytes
            │ container        : %s
            │ wave format code : %d (1 = PCM)
            │ sample rate      : %d Hz
            │ channels         : %d
            │ bits per sample  : %d
            │ byte rate        : %d B/s
            │ samples (per ch) : %d
            │ duration         : %d ms (%.2f s)
            │ → will be resampled to %d Hz mono 16-bit
            ╰────────────────────────────────────────────────
            """.trimIndent(),
            info.source.label,
            info.source.file.absolutePath,
            info.fileSizeBytes,
            if (info.source.rawPcm) "RAW PCM" else info.containerLabel,
            info.audioFormat,
            info.sampleRate,
            info.channels,
            info.bitsPerSample,
            info.byteRate,
            info.totalSamples,
            info.durationMs,
            info.durationMs / 1000.0,
            AudioTestFeeder.TARGET_SAMPLE_RATE,
        )
    }

    fun logAudioStats(label: String, s: AudioStats) {
        Timber.tag(TAG).i(
            """
            ╭─ PCM statistics [%s] (post-normalize) ────────
            │ samples            : %d
            │ peak amplitude     : %d  (%.2f dBFS)
            │ RMS                : %.2f  (%.2f dBFS)
            │ DC offset          : %.2f%s
            │ zero-crossing      : %.4f  (per sample)
            │ silence ratio      : %.2f %% (|x| < 200)
            │ clipped ratio      : %.2f %% (|x| ≥ 32700)
            │ effective bits used: %d / 16%s
            │ longest zero-run   : %d ms%s
            │ longest repeat-run : %d ms%s
            │ high-band energy   : %.4f  → %s
            │ spectral flatness  : %.3f  (0=tone, 1=noise; speech ≈ 0.05–0.30)%s
            │ dominant peak      : %.0f Hz  (%.1f%% of energy, peak/median = +%.1f dB)
            %s%s╰────────────────────────────────────────────────
            """.trimIndent(),
            label,
            s.sampleCount,
            s.peak, s.peakDbFs,
            s.rms, s.rmsDbFs,
            s.dcOffset, if (abs(s.dcOffset) > 50) "  ⚠ noticeable DC bias" else "",
            s.zeroCrossingRate,
            s.silenceRatio * 100.0,
            s.clippedSampleRatio * 100.0,
            s.effectiveBitsUsed,
            if (s.effectiveBitsUsed < 12 && s.sampleCount > AudioTestFeeder.TARGET_SAMPLE_RATE) "  ⚠ low — looks like ${s.effectiveBitsUsed}-bit source padded to 16" else "",
            s.longestZeroRunMs, if (s.longestZeroRunMs > 200) "  ⚠ possible transport dropout" else "",
            s.longestRepeatRunMs, if (s.longestRepeatRunMs > 100) "  ⚠ possible PLC / stall / muted ADC" else "",
            s.highBandEnergyRatio, s.bandwidthHint,
            s.spectralFlatness, if (s.spectralFlatness < 0.05) "  ⚠ very tonal" else "",
            s.dominantFreqHz, s.dominantBinEnergyPct, s.peakToMedianDb,
            if (s.toneAlert != null) "│ tone alert        : ⚠ ${s.toneAlert}\n" else "",
            if (s.possibleRawByteIssue != null) "│ raw-PCM warning   : ⚠ ${s.possibleRawByteIssue}\n" else "",
        )
        logSpectralHistogram(label, s)
    }

    private fun logSpectralHistogram(label: String, s: AudioStats) {
        if (s.subBandEnergyPct.isEmpty() || s.subBandEnergyPct.sum() <= 0.0) return
        val sb = StringBuilder()
        sb.append("╭─ Spectral fingerprint [").append(label).append("] ──────────\n")
        for (i in AudioTestFeeder.BAND_LABELS.indices) {
            val pct = s.subBandEnergyPct[i]
            sb.append(String.format(
                java.util.Locale.US,
                "│ %-10s %5.1f %% │%s│\n",
                AudioTestFeeder.BAND_LABELS[i], pct, formatBandBar(pct)
            ))
        }
        val above3k4 = s.subBandEnergyPct.drop(3).sum()
        val above8k = s.subBandEnergyPct.last()
        val hint = when {
            above3k4 < 1.0 -> "→ NARROWBAND only (BLE SCO / CVSD / telephony)"
            above8k < 1.0 -> "→ wideband-ish (no true fullband content, possibly mSBC / 16 kHz mic)"
            else          -> "→ fullband content present"
        }
        sb.append("│ ").append(hint).append('\n')
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())

        logWavTokenizerSuitability(label, s)
    }

    private fun logWavTokenizerSuitability(label: String, s: AudioStats) {
        val issues = mutableListOf<String>()
        val warns = mutableListOf<String>()

        when {
            s.rmsDbFs.isInfinite() -> issues += "RMS = −∞ (silence) → all frames map to silence token"
            s.rmsDbFs < WtThresholds.RMS_MIN_DBFS ->
                issues += "RMS %.1f dBFS too quiet (need > %.0f) → info loss in silence tokens"
                    .format(java.util.Locale.US, s.rmsDbFs, WtThresholds.RMS_MIN_DBFS)
            s.rmsDbFs > WtThresholds.RMS_MAX_DBFS ->
                issues += "RMS %.1f dBFS too hot → encoder saturation".format(java.util.Locale.US, s.rmsDbFs)
            s.rmsDbFs < WtThresholds.RMS_IDEAL_LOW || s.rmsDbFs > WtThresholds.RMS_IDEAL_HIGH ->
                warns += "RMS %.1f dBFS outside ideal %.0f…%.0f dBFS — quality degraded but usable"
                    .format(java.util.Locale.US, s.rmsDbFs, WtThresholds.RMS_IDEAL_LOW, WtThresholds.RMS_IDEAL_HIGH)
        }

        if (s.peakDbFs > WtThresholds.PEAK_MAX_DBFS)
            warns += "peak %.2f dBFS — near clip line".format(java.util.Locale.US, s.peakDbFs)
        if (s.clippedSampleRatio > WtThresholds.MAX_CLIP_RATIO)
            issues += "clipped %.2f%% of samples → token noise".format(java.util.Locale.US, s.clippedSampleRatio * 100)

        if (abs(s.dcOffset) > WtThresholds.DC_MAX)
            warns += "DC offset %.0f → shifts encoder activations → consistent token drift".format(java.util.Locale.US, s.dcOffset)

        val band3k4_8k = s.subBandEnergyPct.getOrNull(3) ?: Double.NaN
        val band8k_12k = s.subBandEnergyPct.getOrNull(4) ?: Double.NaN
        if (band3k4_8k.isFinite() && band8k_12k.isFinite()) {
            when {
                band3k4_8k < 1.0 && band8k_12k < 0.5 ->
                    issues += "narrowband (≤~3.4 kHz, 3.4–8k=%.1f%% 8–12k=%.2f%%) → true SCO/CVSD input → decoder will hallucinate highs"
                        .format(java.util.Locale.US, band3k4_8k, band8k_12k)
                band8k_12k < 0.5 ->
                    warns += "natively ≤8 kHz source (3.4–8k=%.1f%% 8–12k=%.2f%%) — looks like a 16 kHz mic (mSBC / VOICE_RECOGNITION). Fine for WavTokenizer; no true fullband content available."
                        .format(java.util.Locale.US, band3k4_8k, band8k_12k)
            }
        }

        if (s.effectiveBitsUsed in 1 until WtThresholds.MIN_BITS_USED)
            warns += "only %d effective bits → looks padded/truncated; unnatural quantization noise floor"
                .format(java.util.Locale.US, s.effectiveBitsUsed)

        if (s.longestRepeatRunMs > WtThresholds.MAX_REPEAT_RUN_MS)
            issues += "%d ms repeat-run → BLE/USB PLC or stall → codec will output coherent but WRONG audio"
                .format(java.util.Locale.US, s.longestRepeatRunMs)
        if (s.longestZeroRunMs > WtThresholds.MAX_ZERO_RUN_MS)
            warns += "%d ms zero-run → transport dropout".format(java.util.Locale.US, s.longestZeroRunMs)

        if (s.silenceRatio > WtThresholds.MAX_SILENCE_RATIO)
            warns += "%.0f%% silent → most tokens will be silence".format(java.util.Locale.US, s.silenceRatio * 100)

        if (s.toneAlert != null)
            issues += "tonal contamination: ${s.toneAlert} → codebook collapse + speech masking"

        val verdict = when {
            issues.isNotEmpty() -> "✗ POOR — expect audible artifacts / wrong content"
            warns.isNotEmpty()  -> "△ OK — quality degraded but recognizable"
            else                -> "✓ GOOD — input matches WavTokenizer training distribution"
        }

        val sb = StringBuilder()
        sb.append("╭─ WavTokenizer suitability [").append(label).append("] ─────\n")
        sb.append("│ verdict: ").append(verdict).append('\n')
        if (issues.isNotEmpty()) {
            sb.append("│ blockers:\n")
            issues.forEach { sb.append("│   ✗ ").append(it).append('\n') }
        }
        if (warns.isNotEmpty()) {
            sb.append("│ warnings:\n")
            warns.forEach { sb.append("│   △ ").append(it).append('\n') }
        }
        if (issues.isEmpty() && warns.isEmpty()) {
            sb.append("│   (within training-distribution loudness, bandwidth, bit-depth and PLC bounds)\n")
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    fun logRoundTrip(r: RoundTripResult) {
        val sb = StringBuilder()
        sb.append("╭─ WavTokenizer round-trip [").append(r.label).append("] ───\n")
        if (r.error != null) sb.append("│ ⚠ partial result: ").append(r.error).append('\n')
        sb.append(String.format(java.util.Locale.US,
            "│ chunks=%d  tokens=%d  uniqueTokens=%d (%.2f%% of 4096)\n",
            r.chunks, r.tokens, r.uniqueTokens, r.tokenDiversity * 100))
        sb.append(String.format(java.util.Locale.US,
            "│ token rate=%.1f Hz (expected ≈ 40)\n", r.tokensPerSecond))
        sb.append(String.format(java.util.Locale.US,
            "│ encode avg=%.1f ms/chunk  decode avg=%.1f ms/chunk  RTF=%.3f%s\n",
            r.avgEncodeMs, r.avgDecodeMs, r.realTimeFactor,
            if (r.realTimeFactor > 1.0) "  ⚠ slower than realtime" else ""))
        sb.append(String.format(java.util.Locale.US,
            "│ alignment lag=%+d samples (%+.1f ms — codec algorithmic delay)\n",
            r.alignmentLagSamples, r.alignmentLagSamples * 1000.0 / AudioTestFeeder.TARGET_SAMPLE_RATE))

        sb.append(String.format(java.util.Locale.US,
            "│ Log-Spectral Distance = %s dB %s   ← primary metric (phase-invariant)\n",
            if (r.logSpectralDistanceDb.isNaN()) "n/a"
            else "%.2f".format(java.util.Locale.US, r.logSpectralDistanceDb),
            qualityLabelLsd(r.logSpectralDistanceDb)))

        sb.append("│ Sample-domain metrics (phase-sensitive — INFORMATIONAL ONLY for iSTFT codecs):\n")
        sb.append(String.format(java.util.Locale.US,
            "│   PSNR   = %.2f dB %s\n", r.psnrDb, qualityLabelPsnr(r.psnrDb)))
        sb.append(String.format(java.util.Locale.US,
            "│   SI-SDR = %.2f dB %s\n", r.siSdrDb, qualityLabelSiSdr(r.siSdrDb)))
        if (r.psnrDb > 10.0 && r.siSdrDb < 0.0) {
            sb.append("│   (high PSNR + very negative SI-SDR is the typical fingerprint of a\n")
            sb.append("│    spectrally-correct but phase-shifted reconstruction — expected here.)\n")
        }

        sb.append("│ per-band spectral distortion (rec − orig, dB):\n")
        for (i in AudioTestFeeder.BAND_LABELS.indices) {
            val d = r.perBandSpectralDistortionDb.getOrNull(i) ?: 0.0
            val flag = when {
                d > 6.0  -> "  ⚠ hallucinated energy"
                d < -6.0 -> "  ⚠ lost energy"
                else     -> ""
            }
            sb.append(String.format(java.util.Locale.US,
                "│   %-10s %+6.2f dB%s\n", AudioTestFeeder.BAND_LABELS[i], d, flag))
        }
        val verdict = when {
            r.error != null                 -> "✗ FAILED — ${r.error}"
            r.logSpectralDistanceDb.isNaN() -> "?? — insufficient audio for spectral verdict"
            r.tokenDiversity < 0.005        -> "✗ POOR — codebook collapse (uniqueTokens < 0.5%)"
            r.logSpectralDistanceDb > 10.0  -> "✗ POOR — reconstruction spectrally diverges from input"
            r.logSpectralDistanceDb > 6.0   -> "△ OK — recognizable but lossy"
            else                            -> "✓ GOOD — spectrum well-preserved (typical for neural codec)"
        }
        sb.append("│ verdict: ").append(verdict).append('\n')
        r.originalNormalizedWavFile?.let { sb.append("│ original (24k mono) : ").append(it.absolutePath).append('\n') }
        r.tokensTxtFile?.let           { sb.append("│ tokens (txt)        : ").append(it.absolutePath).append('\n') }
        r.tokensBinFile?.let           { sb.append("│ tokens (bin)        : ").append(it.absolutePath).append('\n') }
        r.decodedWavFile?.let          { sb.append("│ decoded             : ").append(it.absolutePath).append('\n') }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    fun logCrossSourceRoundTrip(roundTrips: Map<String, RoundTripResult>) {
        if (roundTrips.size < 2) return
        val labels = roundTrips.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 18)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source WavTokenizer round-trip ────────\n")
        sb.append(String.format(java.util.Locale.US,
            "│ %-18s %-8s %-8s %-9s %-8s %-7s\n",
            "label", "LSD dB", "PSNR", "SI-SDR", "tok/sec", "uniq%"))
        sb.append("│ (LSD is the phase-invariant metric — primary; PSNR/SI-SDR informational)\n")
        sb.append("├─────────────────────────────────────────────────\n")
        for ((idx, entry) in roundTrips.entries.withIndex()) {
            val r = entry.value
            val lsdStr = if (r.logSpectralDistanceDb.isNaN()) "n/a"
                         else "%.2f".format(java.util.Locale.US, r.logSpectralDistanceDb)
            sb.append(String.format(java.util.Locale.US,
                "│ %-18s %-8s %-8.1f %-9.1f %-8.1f %-7.2f\n",
                display[idx], lsdStr, r.psnrDb, r.siSdrDb, r.tokensPerSecond, r.tokenDiversity * 100))
        }
        val lsds = roundTrips.values
            .map { it.logSpectralDistanceDb }
            .filter { !it.isNaN() && it.isFinite() }
        if (lsds.size >= 2) {
            val spread = lsds.max() - lsds.min()
            if (spread > 3.0) sb.append(String.format(java.util.Locale.US,
                "│ ⚠ LSD spread %.1f dB — codec output quality varies by source\n", spread))
        }
        val divs = roundTrips.values.map { it.tokenDiversity }
        if (divs.size >= 2 && (divs.max() - divs.min()) > 0.05) {
            sb.append("│ ⚠ Token-diversity spread > 5 pp — some source is collapsing the codebook (likely OOD/narrowband)\n")
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    fun logCrossSourceSummary(stats: Map<String, AudioStats>) {
        if (stats.size < 2) return
        val labels = stats.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 18)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source comparison ─────────────────────\n")
        sb.append(String.format("│ %-18s %-10s %-10s %-6s %-6s %-22s\n",
            "label", "peak dBFS", "rms dBFS", "bits", "DC", "bandwidth"))
        sb.append("├─────────────────────────────────────────────────\n")
        for ((idx, entry) in stats.entries.withIndex()) {
            val s = entry.value
            sb.append(String.format("│ %-18s %-10.1f %-10.1f %-6d %-6.0f %-22s\n",
                display[idx], s.peakDbFs, s.rmsDbFs, s.effectiveBitsUsed, s.dcOffset, s.bandwidthHint.take(22)))
        }
        val rmsValues = stats.values.map { it.rmsDbFs }.filter { it.isFinite() }
        if (rmsValues.size >= 2) {
            val spread = (rmsValues.max() - rmsValues.min())
            if (spread > 6.0) sb.append(String.format("│ ⚠ RMS spread across sources: %.1f dB — gain mismatch\n", spread))
        }
        val bws = stats.values.map { it.bandwidthHint }.toSet()
        if (bws.size > 1) sb.append("│ ⚠ Mixed bandwidth across sources — AI will see inconsistent spectrum\n")
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())

        logCrossSourceSpectrum(stats)
    }

    private fun logCrossSourceSpectrum(stats: Map<String, AudioStats>) {
        if (stats.size < 2) return
        val labels = stats.keys.toList()
        val display = disambiguateLabels(labels, maxLen = 10)
        val sb = StringBuilder()
        sb.append("╭─ Cross-source spectral energy (% per band) ───\n")
        sb.append(String.format(java.util.Locale.US, "│ %-10s", "band"))
        for (d in display) sb.append(String.format(java.util.Locale.US, " %10s", d))
        sb.append('\n')
        sb.append("├─────────────────────────────────────────────────\n")
        for (i in AudioTestFeeder.BAND_LABELS.indices) {
            sb.append(String.format(java.util.Locale.US, "│ %-10s", AudioTestFeeder.BAND_LABELS[i]))
            for (l in labels) {
                val v = stats[l]?.subBandEnergyPct?.getOrNull(i) ?: 0.0
                sb.append(String.format(java.util.Locale.US, " %9.1f%%", v))
            }
            sb.append('\n')
        }
        val warnings = mutableListOf<String>()
        for (i in AudioTestFeeder.BAND_LABELS.indices) {
            val values = labels.mapNotNull { stats[it]?.subBandEnergyPct?.getOrNull(i) }
            if (values.size >= 2) {
                val spread = values.max() - values.min()
                if (spread > 15.0) warnings += "${AudioTestFeeder.BAND_LABELS[i]} differs by ${"%.1f".format(spread)} pp"
            }
        }
        if (warnings.isNotEmpty()) {
            sb.append("│ ⚠ Spectral divergence: ").append(warnings.joinToString("; ")).append('\n')
        }
        sb.append("╰─────────────────────────────────────────────────")
        Timber.tag(TAG).i(sb.toString())
    }

    // ---------------- Formatting helpers ----------------

    private fun formatBandBar(pct: Double, width: Int = 20): String {
        val filled = ((pct / 100.0) * width).roundToInt().coerceIn(0, width)
        return "█".repeat(filled) + "·".repeat(width - filled)
    }

    private fun qualityLabelPsnr(p: Double): String = when {
        p.isInfinite() && p > 0 -> "(identical)"
        p >= 25.0 -> "(transparent)"
        p >= 18.0 -> "(good)"
        p >= 12.0 -> "(noticeable artifacts)"
        p >= 6.0  -> "(degraded)"
        else      -> "(broken)"
    }

    private fun qualityLabelSiSdr(s: Double): String = when {
        s.isInfinite() && s > 0 -> "(identical)"
        s >= 15.0 -> "(transparent)"
        s >= 10.0 -> "(good)"
        s >= 5.0  -> "(recognizable)"
        s >= 0.0  -> "(degraded)"
        else      -> "(broken)"
    }

    /** Lower LSD is better. Thresholds chosen from typical neural-codec evaluation literature. */
    private fun qualityLabelLsd(d: Double): String = when {
        d.isNaN() -> "(n/a)"
        d < 3.0   -> "(transparent)"
        d < 6.0   -> "(good — typical for neural codecs)"
        d < 10.0  -> "(noticeable artifacts)"
        else      -> "(spectral mismatch)"
    }

    /**
     * Produces visually-distinct display labels for cross-source tables.
     * Strips longest common prefix/suffix and middle-ellipsizes anything still
     * longer than [maxLen].
     */
    private fun disambiguateLabels(labels: List<String>, maxLen: Int): List<String> {
        if (labels.isEmpty()) return labels
        if (labels.size == 1) return listOf(labels[0].take(maxLen))

        var commonPrefix = labels[0]
        for (s in labels.drop(1)) {
            val n = minOf(commonPrefix.length, s.length)
            var k = 0
            while (k < n && commonPrefix[k] == s[k]) k++
            commonPrefix = commonPrefix.substring(0, k)
            if (commonPrefix.isEmpty()) break
        }
        val minRemainder = 3
        val stripPrefix = commonPrefix.isNotEmpty() &&
            labels.all { it.length - commonPrefix.length >= minRemainder }

        val afterPrefix = if (stripPrefix) labels.map { it.removePrefix(commonPrefix) } else labels

        var commonSuffix = afterPrefix[0]
        for (s in afterPrefix.drop(1)) {
            val a = commonSuffix; val b = s
            val n = minOf(a.length, b.length)
            var k = 0
            while (k < n && a[a.length - 1 - k] == b[b.length - 1 - k]) k++
            commonSuffix = a.substring(a.length - k)
            if (commonSuffix.isEmpty()) break
        }
        val stripSuffix = commonSuffix.isNotEmpty() &&
            afterPrefix.all { it.length - commonSuffix.length >= minRemainder }

        val core = if (stripSuffix) afterPrefix.map { it.removeSuffix(commonSuffix) } else afterPrefix

        return core.map { ellipsizeMiddle(it, maxLen) }
    }

    private fun ellipsizeMiddle(s: String, maxLen: Int): String {
        if (s.length <= maxLen) return s
        if (maxLen <= 1) return s.take(maxLen)
        val keep = maxLen - 1
        val head = (keep + 1) / 2
        val tail = keep - head
        return s.substring(0, head) + "…" + s.substring(s.length - tail)
    }
}


