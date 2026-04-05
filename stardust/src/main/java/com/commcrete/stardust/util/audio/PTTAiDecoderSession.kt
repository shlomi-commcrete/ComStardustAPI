package com.commcrete.stardust.util.audio


import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.WavHelper
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.Scopes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class AiPlayerUtils : PttReceiveSession() {


    // ── AI receive session state ──────────────────────────────────────────────────────────────────
    var isFileInit = false
    private var aiLastKnownSampleRate = -1

    // Frame buffer for WAV saving (not for AudioTrack playback)
    val aiFrameBuffer = mutableListOf<ShortArray>()
    private var aiIsFirst = true
    private var aiIsRecoded = false
    private var aiStartRecored = 0L

    // Timing metrics
    private var aiMeasureLastFrameTime = 0L
    private var aiMeasureTotalSilenceTime = 0L
    private var aiMeasureCounterSilence = 0L
    private var aiMeasureMinTime = 0L
    private var aiMeasureMaxTime = 0L

    // ── Model utility ─────────────────────────────────────────────────────────────────────────────
    fun getModelFromValue(modelValue: Int): WavTokenizerDecoder.ModelType? =
        WavTokenizerDecoder.ModelType.fromInt(modelValue)

    // ── Timestamp ────────────────────────────────────────────────────────────────────────────────


    // ── Session timeout finalization (called by PttReceiveManager on AI inactivity) ──────────────
    fun finalizeAiReceiveSessionOnTimeout() {
        Scopes.getMainCoroutine().launch {
            Timber.tag("AiPlayerUtils").d("Stopping AI file write due to inactivity.")
            val file = fileToWrite
            file?.let {
                Timber.tag("AiPlayerUtils").d("Saving AI frames to WAV: ${it.absolutePath}")
                saveAiFramesToWav(it, aiLastKnownSampleRate)
            }
            isFileInit = false
            fileToWrite = null
            aiIsRecoded = false
            aiIsFirst = true
            aiStartRecored = 0L
        }
    }

    // ── Hooks called by PttReceiveManager per decoded AI frame ────────────────────────────────────
    fun onAiFrameEnqueued(from: String, source: String) {
        Scopes.getDefaultCoroutine().launch {
            Timber.tag("AiPlayerUtils").d("onAiFrameEnqueued: from=$from source=$source")
            if (!isFileInit) {
                Timber.tag("AiPlayerUtils").d("Initializing AI PTT input file…")
                val destination = from.trim().replace("[\"", "").replace("\"]", "")

                initPttInputFile(context, StardustAPIPackage(source, destination), RecorderUtils.CODE_TYPE.AI)
            }
        }
    }

    fun onAiFrameDecoded(frame: ShortArray, sampleRate: Int) {
        aiLastKnownSampleRate = sampleRate
        measureAiFrameTime()
        synchronized(aiFrameBuffer) { aiFrameBuffer.add(frame) }
    }

    fun onAiStreamReleased() {
        aiMeasureLastFrameTime = 0L
        aiMeasureTotalSilenceTime = 0L
        aiMeasureCounterSilence = 0L
        aiMeasureMinTime = 0L
        aiMeasureMaxTime = 0L
        aiIsRecoded = false
        aiIsFirst = true
    }

    // ── Internal helpers ──────────────────────────────────────────────────────────────────────────
    private fun measureAiFrameTime() {
        val currentTime = System.currentTimeMillis()
        if (aiMeasureLastFrameTime > 0) {
            val actualGap = currentTime - aiMeasureLastFrameTime
            if (actualGap < 950) {
                aiMeasureTotalSilenceTime += actualGap
                aiMeasureCounterSilence++
                if (aiMeasureMinTime == 0L || actualGap < aiMeasureMinTime) aiMeasureMinTime = actualGap
                if (actualGap > aiMeasureMaxTime) aiMeasureMaxTime = actualGap
                Timber.tag("AiPlayerUtils").d(
                    "actualGap: $actualGap, avg: ${aiMeasureTotalSilenceTime / aiMeasureCounterSilence}, min: $aiMeasureMinTime, max: $aiMeasureMaxTime"
                )
            } else if (actualGap < 1200) {
                Timber.tag("AiPlayerUtils").d("AI frame dropped")
            }
        }
        aiMeasureLastFrameTime = currentTime
    }

    private fun saveAiFramesToWav(file: File, sampleRate: Int) {
        Timber.tag("AiPlayerUtils").d("saveAiFramesToWav frames=${aiFrameBuffer.size} isRecoded=$aiIsRecoded")
        if (aiFrameBuffer.isEmpty() || aiIsRecoded) return

        if (aiIsFirst) {
            aiIsFirst = false
            aiStartRecored = System.currentTimeMillis()
        }

        Scopes.getDefaultCoroutine().launch {
            delay(1500)
            if (System.currentTimeMillis() - aiStartRecored > 1500) {
                Timber.tag("AiPlayerUtils").d("Saving AI frames to WAV after 1.5s")
                val sampleArray = synchronized(aiFrameBuffer) {
                    aiFrameBuffer.flatMap { it.asIterable() }.toShortArray()
                        .also { aiFrameBuffer.clear() }
                }
                WavHelper.createWavFile(sampleArray, sampleRate, file)
                aiIsRecoded = false
            }
        }
    }

    companion object {
        private const val AI_INACTIVITY_TIMEOUT_MS = 2000L
        private const val AI_SAMPLE_RATE = 24000
    }

}
