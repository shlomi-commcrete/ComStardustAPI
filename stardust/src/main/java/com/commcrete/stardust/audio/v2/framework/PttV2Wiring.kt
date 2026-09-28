package com.commcrete.stardust.audio.v2.framework

import android.content.Context
import com.commcrete.stardust.PttRecordingError
import com.commcrete.stardust.audio.v2.adapter.RecorderUtilsBridge
import com.commcrete.stardust.audio.v2.adapter.StardustPackageRouter
import com.commcrete.stardust.audio.v2.adapter.VolumeUiAdapter
import com.commcrete.stardust.audio.v2.adapter.ai.WavTokenizerCodec
import com.commcrete.stardust.audio.v2.adapter.codec2.Codec2Codec
import com.commcrete.stardust.ai.codec.AIModuleInitializer
import com.commcrete.stardust.audio.v2.application.codec.CodecBootstrap
import com.commcrete.stardust.audio.v2.application.port.KeepAlive
import com.commcrete.stardust.audio.v2.application.port.MaxPttTimeoutNotifier
import com.commcrete.stardust.audio.v2.domain.CodecId
import com.commcrete.stardust.audio.v2.domain.RecordingId
import com.commcrete.stardust.util.audio.AudioRecordingKeepAlive
import com.commcrete.stardust.util.audio.RecorderUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.audio.v2.application.receive.PttReceiveCoordinator
import com.commcrete.stardust.audio.v2.application.receive.StreamRegistry
import com.commcrete.stardust.audio.v2.application.send.PttSendCoordinator
import com.commcrete.stardust.audio.v2.application.send.TransmitSequencer
import com.commcrete.stardust.util.SharedPreferencesUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    // What [shutdown] needs to get hold of. Rebuilt by every [build]; null while torn down.
    private var scopes: List<CoroutineScope> = emptyList()
    private var sequencer: TransmitSequencer? = null
    private var sendCoordinator: PttSendCoordinator? = null
    private var streamRegistry: StreamRegistry? = null
    private var receiveStore: PttReceiveStore? = null

    /**
     * Double-checked locking, and [initialized] is set only on success: the flag used to be set before
     * the body ran, so two callers could race (the receive path calls this per packet) and — worse — a
     * throw mid-body left `recorderBridge` permanently uninitialized, turning every later key-down into
     * an `UninitializedPropertyAccessException`. A failed init is now simply retried by the next caller.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            build(context)
            initialized = true
        }
    }

    /**
     * Tear the pipeline down: abort recordings, stop the transmit gate, close receive streams, cancel
     * every scope, and drop the routing table and the wake lock.
     *
     * The host calls this when the SDK's process keeps running but this pipeline should not — plugin
     * unload, service teardown, logout. Nothing calls it automatically, because only the host knows
     * which of those has happened; what the SDK guarantees is that afterwards nothing of this build is
     * still running and the next [init] builds a clean one (every call site re-inits before use).
     *
     * Synchronous except for the abort: stopping a microphone means touching the device, and a teardown
     * on the main thread must not block on it. The abort therefore runs on its own scope — deliberately
     * not one of [scopes], since it has to outlive them — while everything below proceeds. Cancelling
     * the scopes is itself enough to end every recording; the abort exists to release the device even
     * when a capture thread is parked where a cancellation cannot reach it.
     */
    fun shutdown() {
        synchronized(this) {
            if (!initialized) return
            val coordinator = sendCoordinator
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { coordinator?.abortAll() }
            }
            runCatching { sequencer?.shutdown() }
            // Releases each stream's AudioTrack and decoder now, rather than leaving them to a
            // cancellation that cannot run their close paths for them.
            streamRegistry?.let { registry -> registry.activeStreams.forEach { runCatching { registry.evict(it) } } }
            // After the evictions above, so the end-of-stream events they raise are already queued:
            // the store drains them, closing each WAV it still has open. It owns a thread of its own,
            // which is why a torn-down pipeline has to tell it to stop.
            runCatching { receiveStore?.shutdown() }
            scopes.forEach { runCatching { it.cancel() } }
            routing.clear()
            // This instance is process-wide and refcounted; a session killed before its own release ran
            // would otherwise leave the CPU pinned awake for good.
            AudioRecordingKeepAlive.reset()

            scopes = emptyList()
            sequencer = null
            sendCoordinator = null
            streamRegistry = null
            receiveStore = null
            initialized = false
        }
    }

    private fun build(context: Context) {
        val clock = SystemClock()
        // KeepAlive port backed directly by the legacy refcounted wake-lock object — no wrapper class.
        val keepAlive = object : KeepAlive {
            override fun acquire() = AudioRecordingKeepAlive.acquire(context)
            override fun release() = AudioRecordingKeepAlive.release()
        }
        // Port backed by RecorderUtils, where the legacy beep + host callbacks already live, so both
        // pipelines announce the ceiling identically.
        val maxTimeoutNotifier = object : MaxPttTimeoutNotifier {
            override fun onMaxTimeoutReached(id: RecordingId) = RecorderUtils.notifyPttMaxTimeoutReached()
        }
        val sendStore = PttSendStore(context)
        val watchdogMs = SharedPreferencesUtil.getPTTTimeout().toLong()
        // Everything one recording can legitimately spend between key-down and "all frames committed":
        // the whole max-PTT hold, the grace its microphone gets to actually release, and a spell at the
        // transmit gate behind a stalled predecessor (the gate discards a head after HEAD_TIMEOUT_MS, so
        // two of them covers the queue that keeping a recording waiting requires), plus slack. Past this
        // the recording is not slow, it is stuck, and its routing and session are reclaimed.
        val finalizeTimeoutMs =
            watchdogMs + CAPTURE_STOP_GRACE_MS + (2 * HEAD_TIMEOUT_MS) + FINALIZE_SLACK_MS
        val txScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val receiveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes = listOf(txScope, sendScope, receiveScope, bridgeScope)

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
            // Capture at the OWNING CODEC's native rate with that codec's configured audio source —
            // WavTokenizer needs 24 kHz (legacy AudioRecorderAI) and CODEC2 needs 8 kHz. A fixed 8 kHz
            // for both band-limits AI audio to 4 kHz and then upsamples it, which produces garbage tokens.
            captureProvider = { codecId, nativeRate ->
                MicCaptureSource(
                    context = context,
                    requestedRateHz = nativeRate,
                    audioSource = if (codecId == CodecId.CODEC2) SharedPreferencesUtil.getCodecAudioSource()
                    else SharedPreferencesUtil.getAIAudioSource(),
                    // The mic is opened and released asynchronously here, so the host-facing lifecycle is
                    // reported from the capture thread rather than from RecorderUtils.startRecording /
                    // stopRecording, which only enqueue. SENT comes later still, from the bridge.
                    onCaptureStarted = { RecorderUtils.notifyPttRecordingStarted() },
                    onCaptureStopped = { RecorderUtils.notifyPttRecordingStopped() },
                    onCaptureFailed = { RecorderUtils.notifyPttRecordingError(PttRecordingError.MIC_UNAVAILABLE) },
                )
            },
            dspFactory = { targetRate ->
                ResampleGainDsp(
                    targetRateHz = targetRate,
                    // Read once per recording, as legacy did at recording start.
                    noiseSuppressionEnabled = SharedPreferencesUtil.getNoiseSuppressorEnableState(),
                    gainProvider = { SharedPreferencesUtil.getAudioGain() / 100f },
                )
            },
            mirrorFactory = { id ->
                if (DataManager.getSavePTTFilesRequired()) WavLocalMirror(sendStore.mirrorFile(id))
                else NoOpLocalMirror
            },
            store = NoOpMessageStore,
            keepAlive = keepAlive,
            clock = clock,
            watchdogMs = watchdogMs,
            captureStopGraceMs = CAPTURE_STOP_GRACE_MS,
            finalizeTimeoutMs = finalizeTimeoutMs,
            maxConcurrentRecordings = MAX_CONCURRENT_RECORDINGS,
            maxTimeout = maxTimeoutNotifier,
            scope = sendScope,
        )

        val receiveStore = PttReceiveStore(context).also { this.receiveStore = it }
        val registry = StreamRegistry(
            scope = receiveScope,
            onDecoded = { key, pcm -> receiveStore.onDecodedPcm(key, pcm) },
            onEvicted = { key -> receiveStore.onEnd(key) },
        )
        val receiveCoordinator = PttReceiveCoordinator(registry)

        recorderBridge = RecorderUtilsBridge(
            send = sendCoordinator,
            routing = routing,
            sendStore = sendStore,
            finalizeTimeoutMs = finalizeTimeoutMs,
            scope = bridgeScope,
        )
        router = StardustPackageRouter(receiveCoordinator, receiveStore)
        volumeAdapter = VolumeUiAdapter(receiveCoordinator)

        this.sequencer = sequencer
        this.sendCoordinator = sendCoordinator
        this.streamRegistry = registry
    }

    private const val HEAD_TIMEOUT_MS = 60_000L

    /**
     * How long a recording may take to actually release the microphone after being asked to, before its
     * session is cancelled out from under it. Sized for the slowest legitimate teardown — finish the
     * in-flight 40 ms read, release the `AudioRecord`, then undo the communication-device route
     * (`stopBluetoothSco` is the slow one, and it runs inside the capture flow's `finally`) — because
     * firing early would turn a healthy key-up into a CANCELLED recording with its tail dropped.
     */
    private const val CAPTURE_STOP_GRACE_MS = 3_000L

    /** Headroom on top of the deadlines the finalize ceiling is built from, so it never fires first. */
    private const val FINALIZE_SLACK_MS = 15_000L

    /**
     * How many recordings may be encoding at once before the oldest is aborted to make room.
     *
     * Two is the designed overlap — recording N flushing its tail while N+1 captures — so three leaves
     * a spare and still bounds what a mashed PTT button or a stalled wire can stack up.
     */
    private const val MAX_CONCURRENT_RECORDINGS = 3
}
