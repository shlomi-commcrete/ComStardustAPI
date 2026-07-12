package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.audio.filters.configs.AudioCaptureConfig
import com.commcrete.stardust.util.RegisteredUserUtils
import com.ustadmobile.codec2.Codec2Decoder
import com.ustadmobile.codec2.Codec2Encoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.concurrent.thread


class AudioRecorderCodec2(private val viewModel : PttInterface? = null) :
    BleMediaConnector() {

    companion object {
        const val TAG_PTT_DEBUG = "tag_ptt_debug"
        const val RECORDER_SAMPLE_RATE = 8000
        const val RECORDER_CHANNELS: Int = AudioFormat.CHANNEL_IN_MONO
        const val RECORDER_AUDIO_ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT
        const val BITS_PER_SAMPLE: Short = 16
        const val NUMBER_CHANNELS: Short = 1
        const val BYTE_RATE = RECORDER_SAMPLE_RATE * NUMBER_CHANNELS * 16 / 8

        var BufferElements2Rec = 320
    }

    private var recorder: AudioRecord? = null
    private var isRecording = false

    private var recordingThread: Thread? = null

    /**
     * Active [Codec2SendPipeline] for the current PTT session. Created on
     * each [startRecording] (via [writeAudioDataToFile]) and finalized via
     * [Codec2SendPipeline.finish] on stop. Held here so the `stop*()`
     * paths can flush the trailing partial packet with `isLast = true`
     * even when the recording thread has already exited the capture loop.
     */
    @Volatile private var currentPipeline: Codec2SendPipeline? = null
    private var handler = Handler(Looper.getMainLooper())
    private var numOfPackage = 0
    private var captureRateHz = RECORDER_SAMPLE_RATE
    private val nativeFrameDurationMs = 40
    private var nativeFrameSamples = BufferElements2Rec
    private var runnable = {
    }

    /**
     * Flush the active [Codec2SendPipeline] (sends the last buffered packet
     * with `isLast = true`) and reset the packet counter. Replaces the old
     * `mutableByteListToSend.toByteArray() → sendData(isLast=true)` path
     * the deleted `sendRecordEnd` used to do — same effect on the wire,
     * but the bookkeeping lives in one shared place now.
     */
    private fun flushPipelineEnd() {
        currentPipeline?.finish()
        currentPipeline = null
        numOfPackage = 0
    }

    @SuppressLint("MissingPermission")
    fun startRecording(file: File, carrier: Carrier?) {
        AudioRecordingKeepAlive.acquire(context)
        val capturePlan = AudioCaptureConfig.buildCapturePlan(
            context = context,
            requestedRate = RECORDER_SAMPLE_RATE,
            defaultAudioSource = SharedPreferencesUtil.getCodecAudioSource(DataManager.context),
        )
        captureRateHz = capturePlan.captureRate
        nativeFrameSamples = ((captureRateHz * nativeFrameDurationMs) / 1000).coerceAtLeast(160)
        val minBuffer = AudioRecord.getMinBufferSize(
            captureRateHz,
            RECORDER_CHANNELS,
            RECORDER_AUDIO_ENCODING,
        )
        val recordBufferBytes = maxOf(minBuffer * 2, nativeFrameSamples * 2)
        recorder = AudioRecord(
            capturePlan.audioSource,
            captureRateHz,
            RECORDER_CHANNELS,
            RECORDER_AUDIO_ENCODING,
            recordBufferBytes,
        )

        try {
            Log.d(TAG_PTT_DEBUG, "mWavRecorder Started recorder ${recorder}")
//            AudioRecordManager.register(recorder!!)
        }catch ( e : Exception) {
            e.printStackTrace()
        }
        AudioCaptureConfig.applyInputRoute(context, recorder, capturePlan.preferredInputDevice)
        recorder?.audioSessionId?.let { setRecordingParams(it) }
        syncBleDevice()
        recorder?.startRecording()
        isRecording = true

        recordingThread = thread(true) { writeAudioDataToFile(file, carrier) }
    }

    private fun syncBleDevice() {
        Log.d(TAG_PTT_DEBUG, "mWavRecorder syncBleDevice")
        val context = DataManager.appContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val wantedInputDevice = SharedPreferencesUtil.getInputDevice()

        // No explicit user preference → make sure we are NOT stuck on SCO from a
        // previous session and let the platform pick its own default.
        if (wantedInputDevice == AudioDeviceInfo.TYPE_UNKNOWN) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
            } catch (_: Throwable) {}
            try { audioManager.isBluetoothScoOn = false } catch (_: Throwable) {}
            try { audioManager.stopBluetoothSco() } catch (_: Throwable) {}
            return
        }

        // Resolve the actual AudioDeviceInfo that matches the user preference
        // (falls back through the configured input hierarchy).
        val preferred = getPreferredDevice(audioManager, AudioManager.GET_DEVICES_INPUTS, context)
        if (preferred == null) {
            Log.d(TAG_PTT_DEBUG, "mWavRecorder syncBleDevice: no matching input device")
            return
        }

        Log.d(TAG_PTT_DEBUG,
            "mWavRecorder syncBleDevice: using ${preferred.productName}/${preferred.type}")

        // 1. Force AudioRecord to capture from the chosen device — this is the
        //    main fix: previously we only did this for BLUETOOTH_SCO, so a
        //    plugged-in USB headset would silently hijack the mic.
        try { recorder?.setPreferredDevice(preferred) } catch (_: Throwable) {}

        // 2. On Android 12+ also pin the communication device so VOICE_* sources
        //    don't get re-routed by audio policy when peripherals change.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.setCommunicationDevice(preferred) } catch (e: Exception) {
                Timber.tag(TAG_PTT_DEBUG).w(e, "setCommunicationDevice(${preferred.type}) failed")
            }
        }

        // 3. Only manage SCO when the chosen input actually is the BT SCO mic.
        //    Otherwise SCO would silently override the user's wired/USB/built-in
        //    selection.
        if (preferred.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            try { audioManager.startBluetoothSco() } catch (_: Throwable) {}
            try { audioManager.isBluetoothScoOn = true } catch (_: Throwable) {}
            Log.d(TAG_PTT_DEBUG, "mWavRecorder syncBleDevice SCO enabled")
        } else {
            try { audioManager.isBluetoothScoOn = false } catch (_: Throwable) {}
            try { audioManager.stopBluetoothSco() } catch (_: Throwable) {}
        }
    }

    private fun removeSyncBleDevices() {
        Log.d(TAG_PTT_DEBUG, "mWavRecorder removeSyncBleDevices")
        val audioManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(AudioManager::class.java)
        } else {
            TODO("VERSION.SDK_INT < M")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        } catch (_: Throwable) {}
        try { audioManager.isBluetoothScoOn = false } catch (_: Throwable) {}
        try { audioManager.stopBluetoothSco() } catch (_: Throwable) {}
    }

    fun kill () {
        try {
            isRecording = false
            recorder?.let {
                try {
                    Log.d(TAG_PTT_DEBUG, "Stopping recorder")
                    it.stop()
                    it.release()
                } catch (e: Exception) {
                    e.printStackTrace() // or Timber.e(e, "Failed to stop recorder")
                    Log.d(TAG_PTT_DEBUG, "Exception while stopping recorder: ${e.message}")
                } finally {
                    AudioCaptureConfig.clearInputRoute(context)
                    AudioRecordingKeepAlive.release()
                    recordingThread = null
                    recorder = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRecordingNow(
        retry : Int = 0,
        chatId: String,
        receiverId: String,
        path: String,
        carrier: Carrier?) {

        var retryNum = retry
        retryNum += 1
        if(retryNum > 3) {
            return
        }
        try {
            isRecording = false
            recorder?.let {
                try {
                    Log.d(TAG_PTT_DEBUG, "Stopping recorder")
                    it.stop()
                    it.release()
                    Log.d(TAG_PTT_DEBUG, "mWavRecorder Stopped recorder ${recorder}")
                } catch (e: Exception) {
                    e.printStackTrace() // or Timber.e(e, "Failed to stop recorder")
                    Log.d(TAG_PTT_DEBUG, "Exception while stopping recorder: ${e.message}")
                    stopRecording(retryNum, chatId = chatId, receiverId = receiverId, path = path, carrier)
                } finally {
                    AudioCaptureConfig.clearInputRoute(context)
                    AudioRecordingKeepAlive.release()
                    recordingThread = null
//                    val mRecorder = recorder
//                    mRecorder?.let { it1 -> AudioRecordManager.unregister(it1) }
                    recorder = null
                    Log.d(TAG_PTT_DEBUG, "mWavRecorder Finally Recorder")
                }
            }
            // ✅ Save PTT regardless of whether recorder was null
            saveOrRemovePttFile(chatId, receiverId, path)
        } catch (e: Exception) {
            Log.d(TAG_PTT_DEBUG, "mWavRecorder while stopping ${e.printStackTrace()}")

            stopRecording(retryNum, chatId = chatId, receiverId = receiverId, path = path, carrier)
        } finally {
            // ✅ Always notify
            Log.d(TAG_PTT_DEBUG, "mWavRecorder Finally before sendRecordEnd")
            flushPipelineEnd()
            Log.d(TAG_PTT_DEBUG, "mWavRecorder Finally after sendRecordEnd")
        }
        Log.d(TAG_PTT_DEBUG, "stopRecording called $retryNum")
    }

    fun stopRecording(
        retry : Int = 0,
        chatId: String,
        receiverId: String,
        path: String,
        carrier: Carrier?) {
        CoroutineScope(Dispatchers.IO).launch {
            delay(200)
            stopRecordingNow(retry, chatId, receiverId, path,  carrier)
        }
    }

    private fun writeAudioDataToFile(file: File, carrier: Carrier?) {
        // Input gain: profile setting takes precedence, then SharedPreferences.
        val targetGain = SharedPreferencesUtil.getAudioGain(DataManager.context) / 100f
        val enableNoiseCancellation = SharedPreferencesUtil.getNoiseSuppressorEnableState(DataManager.context)
        val sData = ShortArray(nativeFrameSamples)
        val nativePending = ArrayList<Short>(nativeFrameSamples * 2)
        var os: FileOutputStream? = null
        try {
            if(file.exists()){
                os = FileOutputStream(file)
            }

        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        val data = arrayListOf<Byte>()
        val dataPrint = arrayListOf<Byte>()
        val codec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
        var chunkIndex = 0

        // SAME encode + pack + send pipeline the test feeder uses
        // (`AudioFeederEngine.createCodec2Pipeline`). Replaces the
        // previous in-line `encodeAndSendCodecFrame` + `handleBlePackage`
        // + `appendToArray` + `sendToBle` chain — keeps live and feeder
        // bit-comparable. The PTT timeout (was checked inside the old
        // `appendToArray`) is now enforced via the `onPacketSent`
        // callback. The decoded-WAV artifact is built via
        // `onEncodedFrame`: each 4-byte codec2 frame is decoded right
        // after it's enqueued for transmission, so the local `.wav`
        // matches exactly what the receiver decodes.
        currentPipeline?.reset()
        val pipeline = Codec2SendPipeline(
            context = context,
            carrier = carrier,
            sourceProvider = { viewModel?.getSource() ?: DataManager.getSource() },
            destinationProvider = { viewModel?.getDestenation() },
            onPacketSent = { onPipelinePacketSent(file, carrier) },
            onEncodedFrame = { encodedFrame ->
                logByteArray("logByteArrayInputRecorder", encodedFrame)
                for (byte in encodedFrame) dataPrint.add(byte)
                val byteBuffer = codec2Decoder.readFrame(encodedFrame)
                val bDataCodec = byteBuffer.array()
                logByteArray("logByteArrayOutputRecorder", bDataCodec)
                for (byte in bDataCodec) data.add(byte)
            },
        )
        currentPipeline = pipeline

        while (isRecording) {
            // gets the voice output from microphone to byte format
            val recording = recorder?.read(sData, 0, BufferElements2Rec)
            try {
                if (recording != null && recording > 0) {
                    for (i in 0 until recording) {
                        val gained = (sData[i] * targetGain)
                            .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                            .toInt()
                            .toShort()
                        nativePending.add(gained)
                    }
                }
                while (nativePending.size >= nativeFrameSamples) {
                    val nativeFrame = ShortArray(nativeFrameSamples)
                    for (i in 0 until nativeFrameSamples) {
                        nativeFrame[i] = nativePending[i]
                    }
                    repeat(nativeFrameSamples) { nativePending.removeAt(0) }

                    // Same preprocessing call the test feeder uses internally
                    // via `PttAudioProcessor.process` — applies the active
                    // profile's filter chain and resamples to 8 kHz.
                    val filtered = RecorderUtils.preprocessChunkForEncoding(
                        pcmArray = nativeFrame,
                        nativeRate = captureRateHz,
                        encodingType = RecorderUtils.CODE_TYPE.CODEC2,
                        enableNoiseCancellation = enableNoiseCancellation
                    )
                    pipeline.enqueuePcm(filtered)
                    chunkIndex++
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        // Tail: any unprocessed native samples → pre-process → enqueue.
        // [Codec2SendPipeline.finish] below will zero-pad whatever's left
        // inside the pipeline and send the final packet with `isLast = true`.
        if (nativePending.isNotEmpty()) {
            val paddedNative = ShortArray(nativeFrameSamples)
            for (i in nativePending.indices) paddedNative[i] = nativePending[i]
            val filtered = RecorderUtils.preprocessChunkForEncoding(
                pcmArray = paddedNative,
                nativeRate = captureRateHz,
                encodingType = RecorderUtils.CODE_TYPE.CODEC2,
                enableNoiseCancellation = enableNoiseCancellation,
                isFinal = true
            )
            pipeline.enqueuePcm(filtered)
        }
        pipeline.finish()
        currentPipeline = null

        os?.write(data.toByteArray())
        try {
            os?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        logByteArray("totalRecording", dataPrint.toByteArray())
    }


    /**
     * Per-packet callback from [Codec2SendPipeline] — enforces the same
     * max-PTT-timeout the old `appendToArray` did, but driven by the
     * shared pipeline so live and feeder share identical send semantics.
     */
    private fun onPipelinePacketSent(file: File, carrier: Carrier?) {
        numOfPackage++
        val maxSecondsPTT = SharedPreferencesUtil.getPTTTimeout(context)
        if (numOfPackage.times(880) > maxSecondsPTT) {
            DataManager.getCallbacks()?.pttMaxTimeoutReached()
            viewModel?.let { vm ->
                vm.getDestenation()?.let { dest ->
                    stopRecording(retry = 0, dest, file.absolutePath, context, carrier)
                    vm.maxPTTTimeoutReached()
                }
            }
        } else {
            resetTimer()
        }
    }

    /**
     * Constructs header for wav file format
     */
    private fun wavFileHeader(): ByteArray {
        val headerSize = 44
        val header = ByteArray(headerSize)

        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (0 and 0xff).toByte() // Size of the overall file, 0 because unknown
        header[5] = (0 shr 8 and 0xff).toByte()
        header[6] = (0 shr 16 and 0xff).toByte()
        header[7] = (0 shr 24 and 0xff).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16 // Length of format data
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1 // Type of format (1 is PCM)
        header[21] = 0

        header[22] = NUMBER_CHANNELS.toByte()
        header[23] = 0

        header[24] = (RECORDER_SAMPLE_RATE and 0xff).toByte() // Sampling rate
        header[25] = (RECORDER_SAMPLE_RATE shr 8 and 0xff).toByte()
        header[26] = (RECORDER_SAMPLE_RATE shr 16 and 0xff).toByte()
        header[27] = (RECORDER_SAMPLE_RATE shr 24 and 0xff).toByte()

        header[28] = (BYTE_RATE and 0xff).toByte() // Byte rate = (Sample Rate * BitsPerSample * Channels) / 8
        header[29] = (BYTE_RATE shr 8 and 0xff).toByte()
        header[30] = (BYTE_RATE shr 16 and 0xff).toByte()
        header[31] = (BYTE_RATE shr 24 and 0xff).toByte()

        header[32] = (NUMBER_CHANNELS * BITS_PER_SAMPLE / 8).toByte() //  16 Bits stereo
        header[33] = 0

        header[34] = BITS_PER_SAMPLE.toByte() // Bits per sample
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (0 and 0xff).toByte() // Size of the data section.
        header[41] = (0 shr 8 and 0xff).toByte()
        header[42] = (0 shr 16 and 0xff).toByte()
        header[43] = (0 shr 24 and 0xff).toByte()

        return header
    }

    private fun updateHeaderInformation(data: ArrayList<Byte>) {
        val fileSize = data.size
        val contentSize = fileSize - 44

        data[4] = (fileSize and 0xff).toByte() // Size of the overall file
        data[5] = (fileSize shr 8 and 0xff).toByte()
        data[6] = (fileSize shr 16 and 0xff).toByte()
        data[7] = (fileSize shr 24 and 0xff).toByte()

        data[40] = (contentSize and 0xff).toByte() // Size of the data section.
        data[41] = (contentSize shr 8 and 0xff).toByte()
        data[42] = (contentSize shr 16 and 0xff).toByte()
        data[43] = (contentSize shr 24 and 0xff).toByte()
    }

    private fun saveOrRemovePttFile(chatId: String, receiverId: String, path: String) {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            if(!DataManager.getSavePTTFilesRequired()) {
                File(path).takeIf { it.exists() }?.delete()
                return@launch
            } else {
                DataManager.getAppRepo().saveMessage(
                    MessageEntity(
                        chatId = chatId,
                        senderID = appId,
                        receiverID = receiverId,
                        state = MessageState.SENT,
                        epochTimeMs = RecorderUtils.ts,
                        extraData = MessageExtraData.PTT(
                            path = path,
                            encoderType = EncoderType.CODEC2
                        )
                    )
                )
                RecorderUtils.ts = 0
            }
        }
    }

    fun updateAudioReceived(chatId: String, context: Context){
        Scopes.getDefaultCoroutine().launch {
            val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
            val chatItem = chatsRepo.getChatByBittelID(chatId)
            chatItem?.let {
                chatItem.message = Message(senderID = chatId, text = "Ptt Sent",
                    seen = true)
                chatsRepo.addChat(it)
            }
        }
    }

    // NOTE: The previous `charsToBytes` / `sendData` / `sendToBle` /
    // `generateRandomNumber` / `appendToArray` /
    // `concatenateByteArraysWithIgnoring` / `getBytesShift` /
    // `handleBlePackage` / `shiftByteArrayEvery28Bits` / `setMinData`
    // helpers in this file were a parallel implementation of the
    // codec2 encode + pack + send pipeline. They all collapsed into
    // [Codec2SendPipeline] so the live recorder and the
    // `AudioTestFeeder` now hit identical encode→pack→send code; the
    // recorder's only extra responsibility (decoded-PCM local WAV
    // artifact) is wired in via [Codec2SendPipeline.onEncodedFrame] in
    // [writeAudioDataToFile] above.

    private fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 200)
    }


    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }


    fun ShortToByte_ByteBuffer_Method(input: List<Short>): ByteArray? {
        var index: Int
        val iterations = input.size
        val bb = ByteBuffer.allocate(input.size * 2)
        index = 0
        while (index != iterations) {
            bb.putShort(input[index])
            ++index
        }
        return bb.array()
    }

    fun logByteArrayToBase64(bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            return ""
        }
    }

    fun searchThreshold(arr: ShortArray): Int {
        var threshold: Short = 3000
        var peakIndex: Int
        val arrLen = arr.size
        peakIndex = 0
        while (peakIndex < arrLen) {
            if (arr[peakIndex] >= threshold || arr[peakIndex] <= -threshold) {
                //se supera la soglia, esci e ritorna peakindex-mezzo kernel.
                return peakIndex
            }
            peakIndex++
        }
        return -1 //not found
    }
}

fun Array<Int>.endsWith(suffix: Array<Int>): Boolean {
    if (this.size < suffix.size) return false
    return this.sliceArray(this.size - suffix.size until this.size).contentEquals(suffix)
}