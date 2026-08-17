package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.audio.v2.adapter.RecorderUtilsBridge
import com.commcrete.stardust.audio.v2.adapter.StardustPackageRouter
import com.commcrete.stardust.audio.v2.adapter.VolumeUiAdapter
import com.commcrete.stardust.audio.v2.adapter.ai.WavTokenizerCodec
import com.commcrete.stardust.audio.v2.adapter.codec2.Codec2Codec
import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.audio.v2.application.codec.CodecBootstrap
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.audio.v2.application.receive.PttReceiveCoordinator
import com.commcrete.stardust.audio.v2.application.receive.StreamRegistry
import com.commcrete.stardust.audio.v2.application.send.PttSendCoordinator
import com.commcrete.stardust.audio.v2.application.send.TransmitSequencer
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Framework ring — the composition root that wires the CODEC2 v2 path end-to-end. Call [init] once at
 * SDK startup (after DataManager is initialized). This is the ONLY place the concrete rings are
 * assembled; everything else depends inward on ports.
 *
 * Send:    RecorderUtilsBridge → PttSendCoordinator → RecordingSession → (ResampleGainDsp,
 *          Codec2EncoderSession) → OutboundBuffer → TransmitSequencer → BleSendTransport.
 * Receive: StardustPackageRouter → PttReceiveCoordinator → StreamRegistry → ReceiveStream →
 *          (Codec2DecoderSession, Codec2PlaybackSink); volume via VolumeUiAdapter.
 *
 * NOT yet consumed by `RecorderUtils` / `StardustPackageHandler` — that flag-guarded delegation is the
 * final wiring step. Persistence and local WAV mirror are NoOp until their adapters land.
 */
object PttV2Wiring {

    @Volatile private var initialized = false

    lateinit var recorderBridge: RecorderUtilsBridge
        private set
    lateinit var router: StardustPackageRouter
        private set
    lateinit var volumeAdapter: VolumeUiAdapter
        private set

    private val routing = PttSendRouting()

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val clock = SystemClock()
        // KeepAlive port backed directly by the legacy refcounted wake-lock object — no wrapper class.
        val keepAlive = object : KeepAlive {
            override fun acquire() = AudioRecordingKeepAlive.acquire(context)
            override fun release() = AudioRecordingKeepAlive.release()
        }
        val sendStore = PttSendStore(context)
        val txScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sequencer = TransmitSequencer(
            transport = BleSendTransport(routing),
            clock = clock,
            headTimeoutMs = HEAD_TIMEOUT_MS,
            scope = txScope,
        )

        // WavTokenizer needs its PyTorch models loaded; idempotent, no-op if already initialized or
        // if this process isn't the AI initializer (in which case AI recording will fail fast, as in legacy).
        AIModuleInitializer.initModules()

        CodecBootstrap.bootstrap(
            Codec2Codec(playbackSinkFactory = { Codec2PlaybackSink() }),
            WavTokenizerCodec(playbackSinkFactory = { AiPlaybackSink() }),
        )

        val sendCoordinator = PttSendCoordinator(
            sequencer = sequencer,
            captureProvider = { MicCaptureSource(context) },
            dspFactory = { targetRate -> ResampleGainDsp(targetRate) { SharedPreferencesUtil.getAudioGain() / 100f } },
            mirrorFactory = { id ->
                if (DataManager.getSavePTTFilesRequired()) WavLocalMirror(sendStore.mirrorFile(id))
                else NoOpLocalMirror
            },
            store = NoOpMessageStore,
            keepAlive = keepAlive,
            clock = clock,
            watchdogMs = SharedPreferencesUtil.getPTTTimeout().toLong(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        val receiveStore = PttReceiveStore(context)
        val registry = StreamRegistry(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onDecoded = { key, pcm -> receiveStore.onDecodedPcm(key, pcm) },
            onEvicted = { key -> receiveStore.onEnd(key) },
        )
        val receiveCoordinator = PttReceiveCoordinator(registry)

        recorderBridge = RecorderUtilsBridge(sendCoordinator, routing, sendStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        router = StardustPackageRouter(receiveCoordinator, receiveStore)
        volumeAdapter = VolumeUiAdapter(receiveCoordinator)
    }

    private const val HEAD_TIMEOUT_MS = 60_000L
}
