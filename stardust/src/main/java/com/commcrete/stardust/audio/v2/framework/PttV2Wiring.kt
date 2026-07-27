package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.audio.v2.adapter.RecorderUtilsBridge
import com.commcrete.stardust.audio.v2.adapter.StardustPackageRouter
import com.commcrete.stardust.audio.v2.adapter.VolumeUiAdapter
import com.commcrete.stardust.audio.v2.adapter.codec2.Codec2Codec
import com.commcrete.stardust.audio.v2.application.codec.CodecBootstrap
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
        val txScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sequencer = TransmitSequencer(
            transport = BleSendTransport(routing),
            clock = clock,
            headTimeoutMs = HEAD_TIMEOUT_MS,
            scope = txScope,
        )

        CodecBootstrap.bootstrap(
            Codec2Codec(playbackSinkFactory = { Codec2PlaybackSink() }),
        )

        val sendCoordinator = PttSendCoordinator(
            sequencer = sequencer,
            captureProvider = { MicCaptureSource(context) },
            dspFactory = { targetRate -> ResampleGainDsp(targetRate) { SharedPreferencesUtil.getAudioGain() / 100f } },
            mirrorFactory = { NoOpLocalMirror },
            store = NoOpMessageStore,
            clock = clock,
            watchdogMs = SharedPreferencesUtil.getPTTTimeout().toLong(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        val registry = StreamRegistry(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val receiveCoordinator = PttReceiveCoordinator(registry)

        recorderBridge = RecorderUtilsBridge(sendCoordinator, routing, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        router = StardustPackageRouter(receiveCoordinator)
        volumeAdapter = VolumeUiAdapter(receiveCoordinator)
    }

    private const val HEAD_TIMEOUT_MS = 60_000L
}
