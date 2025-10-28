package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.Carrier
import com.commcrete.stardust.util.CarriersUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.FunctionalityType
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.ustadmobile.codec2.Codec2Decoder
import com.ustadmobile.codec2.Codec2Encoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.experimental.or
import kotlin.random.Random


class WavRecorder(val context: Context, private val viewModel : PttInterface? = null) :
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

        val suffix = arrayOf(-50, -10, -128, -4, -17, 104, 0, 0)
    }

    private var recorder: AudioRecord? = null
    private var isRecording = false

    private var recordingThread: Thread? = null

    private var mutableByteListToSend = mutableListOf<Byte>()
    private var savedByteArray : ByteArray? = null
    private var handler = Handler(Looper.getMainLooper())
    private var numOfPackage = 0
    private var runnable = {
    }

    private fun sendRecordEnd(carrier: Carrier?){
//        setMinData()
        //todo Correction crash
        sendData(mutableByteListToSend.toByteArray().copyOf(), true, carrier)
        mutableByteListToSend.clear()
        numOfPackage++
//        Toast.makeText(context, "Sent $numOfPackage Packages", Toast.LENGTH_LONG ).show()
        numOfPackage = 0
    }

    @SuppressLint("MissingPermission")
    fun startRecording(path: String, destination: String, carrier: Carrier?) {
        val audioSource = SharedPreferencesUtil.getAudioSource(DataManager.context)
        recorder = AudioRecord(
            audioSource,
            RECORDER_SAMPLE_RATE, RECORDER_CHANNELS,
            RECORDER_AUDIO_ENCODING, BufferElements2Rec)

        try {
            AudioRecordManager.register(recorder!!)
        }catch ( e : Exception) {
            e.printStackTrace()
        }
        recorder?.audioSessionId?.let { setRecordingParams(it, DataManager.context) }
        syncBleDevice(context)
        recorder?.startRecording()
        isRecording = true

        recordingThread = thread(true) {
            writeAudioDataToFile(path, carrier)
        }
    }

    private fun syncBleDevice (context: Context) {
        val audioManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(AudioManager::class.java)
        } else {
            TODO("VERSION.SDK_INT < M")
        }
        val wantedInputDevice = SharedPreferencesUtil.getInputDevice(context)

        if (wantedInputDevice == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            val bleDevice =
                getPreferredDevice(audioManager, AudioManager.GET_DEVICES_INPUTS, context)
            bleDevice?.let {
                recorder?.setPreferredDevice(it)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.setCommunicationDevice(it)
                }
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
    }

    private fun removeSyncBleDevices (context: Context) {
        val audioManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(AudioManager::class.java)
        } else {
            TODO("VERSION.SDK_INT < M")
        }
        val wantedInputDevice = SharedPreferencesUtil.getInputDevice(context)
        if(wantedInputDevice == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {

            val bleDevice = getPreferredDevice(audioManager,AudioManager.GET_DEVICES_INPUTS, context)
            bleDevice?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false

            }}
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
                    removeSyncBleDevices(context)
                    recordingThread = null
                    recorder = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRecordingNow(retry : Int = 0 , chatID: String, path: String, context: Context, carrier: Carrier?) {
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
                } catch (e: Exception) {
                    e.printStackTrace() // or Timber.e(e, "Failed to stop recorder")
                    Log.d(TAG_PTT_DEBUG, "Exception while stopping recorder: ${e.message}")
                    stopRecording(retry, chatID, path, context, carrier)
                } finally {
                    removeSyncBleDevices(context)
                    recordingThread = null
                    recorder = null
                }
            }
            // ✅ Save PTT regardless of whether recorder was null
            savePtt(chatID, path, context)
        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording(retry, chatID, path, context, carrier)
        } finally {
            // ✅ Always notify
            sendRecordEnd(carrier)
        }
        Log.d(TAG_PTT_DEBUG, "stopRecording called $retryNum")
    }


    fun stopRecording(retry : Int = 0 , chatID: String, path: String, context: Context, carrier: Carrier?) {
        Handler(Looper.getMainLooper()).postDelayed({
                                                    stopRecordingNow(retry, chatID, path, context, carrier)

        }, 100)
    }

    private fun writeAudioDataToFile(path: String, carrier: Carrier?) {


        val targetGain = (SharedPreferencesUtil.getGain(DataManager.context)/100f)
        val sData = ShortArray(BufferElements2Rec)
        var os: FileOutputStream? = null
        try {
            if(path.isNotEmpty()){
                os = FileOutputStream(path)
            }

        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }
        val data = arrayListOf<Byte>()
        val dataPrint = arrayListOf<Byte>()
        val codec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

        while (isRecording) {
            // gets the voice output from microphone to byte format
            val recording = recorder?.read(sData, 0, BufferElements2Rec)
            try {
                if (recording != null) {
                    if (recording > 0) {
                        // Apply gain factor to each sample
                        for (i in 0 until recording) {
                            sData[i] =
                                (sData[i] * targetGain).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                                    .toInt()
                                    .toShort()
                        }

                        // Now 'buffer' contains the amplified audio data
                        // You can write it to a file, stream it, or process it further as needed
                    }
                }
                val codec2Encoder = Codec2Encoder(RecorderUtils.CodecValues.MODE700.mode)
                val charArray = CharArray(RecorderUtils.CodecValues.MODE700.charNumOutput)
                codec2Encoder.encode(sData, charArray)
                val byteaArray = charsToBytes(charArray)
                byteaArray?.let {
                    logByteArray("logByteArrayInputRecorder", it)
                    for (byte in byteaArray)
                        dataPrint.add(byte)
                }
                val byteBuffer = codec2Decoder.readFrame(byteaArray)
                val bDataCodec = byteBuffer.array()
                logByteArray("logByteArrayOutputRecorder", bDataCodec)
                for (byte in bDataCodec)
                    data.add(byte)


                if(BleManager.isNetworkEnabled()){
                    handleBlePackage(byteaArray, null)
                }
                else if (BleManager.isBluetoothEnabled() || BleManager.isUsbEnabled()) {
//                    send to BLE
                    handleBlePackage(byteaArray, carrier)
                }else {
                    Scopes.getMainCoroutine().launch {
//                        viewModel?.error?.value = "Unable To Send Message - No Connection"
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        os?.write(data.toByteArray())
        try {
            os?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        logByteArray("totalRecording", dataPrint.toByteArray())
//        os2?.write(ShortToByte_ByteBuffer_Method(dataShort))
//        try {
//            os2?.close()
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
    }

    fun sendAudioTest(context: Context) {
        if (!BleManager.isBluetoothEnabled()) {
            Scopes.getMainCoroutine().launch {
//                viewModel?.error?.value = "Unable To Send Message - No Connection"
            }
        }
        FileUtils.clearFile(context, fileName = "pttTestsSend")
        val file = FileUtils.createFile(context, fileName = "pttTestsSend")
        val mutableByteListToSend = mutableListOf<Byte>()
        while (mutableByteListToSend.size < 78){
            mutableByteListToSend.add(0)
        }
        val delay = 880L
        Scopes.getDefaultCoroutine().launch {
            var count = 1
            while(count < 200){
                mutableByteListToSend[0] = count.toByte()
                sendData(mutableByteListToSend.toByteArray().copyOf())
                FileUtils.saveToFile(file.absolutePath, mutableByteListToSend.toByteArray().copyOf())
                count++
                delay(delay)
            }
        }
    }

    fun recordFrom () {

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


    private fun savePtt(chatID : String, path : String, context: Context){
        Scopes.getDefaultCoroutine().launch {
            SharedPreferencesUtil.getAppUser(context)?.appId?.let {
                val chatsRepo = DataManager.getChatsRepo(context)
                val chatItem = chatsRepo.getChatByBittelID(chatID)
                chatItem?.message = Message(
                    senderID = it,
                    text = "PTT Sent",
                    seen = true
                )
                chatItem?.let { chatsRepo.addChat(it) }
                MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).savePttMessage(
                    MessageItem(senderID = it,
                        epochTimeMs = RecorderUtils.ts, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT, audioType = RecorderUtils.CODE_TYPE.CODEC2.id)
                )
            }
            RecorderUtils.ts = 0
            RecorderUtils.file = null
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

    private fun charsToBytes(chars: CharArray?): ByteArray? {
        var byteArray : ByteArray? = null
        chars?.let {chars: CharArray ->
            byteArray= ByteArray(chars.size)
            chars.forEachIndexed { index, c ->
                byteArray!![index] = c.code.toByte()
            }
        }
        return byteArray
    }

    private fun sendData(byteArray: ByteArray, isLast : Boolean = false, carrier: Carrier? = null){
        if(BleManager.isNetworkEnabled()){
//            sendToServer(byteArray, isLast)
        }else if (BleManager.isBluetoothEnabled()|| BleManager.isUsbEnabled()) {
            sendToBle(byteArray, isLast, carrier)
        }
    }

    private fun sendToBle(byteArray: ByteArray, isLast: Boolean = false, carrier: Carrier?) {
        Scopes.getDefaultCoroutine().launch {
            val audioIntArray = StardustPackageUtils.byteArrayToIntArray(byteArray)
            val bittelPackage = viewModel?.let {
                if(audioIntArray.endsWith(suffix)){
                    val num = generateRandomNumber()
                    audioIntArray[audioIntArray.size-1] = num
                    audioIntArray[audioIntArray.size-2] = num
                }
                StardustPackageUtils.getStardustPackage(source = it.getSource(), destenation = it.getDestenation() ?: "" , stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_PTT,
                    data = audioIntArray)
            }
            val radio = CarriersUtils.getRadioToSend(carrier, functionalityType = FunctionalityType.PTT)
            bittelPackage?.stardustControlByte?.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
            bittelPackage?.stardustControlByte?.stardustDeliveryType = radio.second
            bittelPackage?.checkXor =
                bittelPackage?.getStardustPackageToCheckXor()
                    ?.let { StardustPackageUtils.getCheckXor(it) }

            bittelPackage?.let {
                DataManager.sendDataToBle(bittelPackage)
//                sendWithTimer(it)
            }

        }
    }

    private fun generateRandomNumber(): Int {
        return Random.nextInt(0, 41) // 41 is exclusive
    }

    private fun appendToArray (byteArray: ByteArray?, carrier: Carrier?){
        val maxSecondsPTT = SharedPreferencesUtil.getPTTTimeout(context)
        if(numOfPackage.times(880) > maxSecondsPTT){
            DataManager.getCallbacks()?.pttMaxTimeoutReached()
            viewModel?.let {
                it.getDestenation()
                    ?.let { it1 ->
                        RecorderUtils.file?.absolutePath?.let { it2 ->
                        stopRecording(
                            retry = 0,
                            it1,
                            it2, context, carrier
                        )
                            it.maxPTTTimeoutReached()
                    } }
            }
        }else {
            byteArray?.toList()?.let {
                for(byte in it){
                    mutableByteListToSend.add(byte)
                    if(mutableByteListToSend.size == 77){
                        sendData(mutableByteListToSend.toByteArray().copyOf(), carrier = carrier)
                        mutableByteListToSend.clear()
                        numOfPackage++
                    }
                }
            }
            resetTimer()
        }
    }

    private fun concatenateByteArraysWithIgnoring(byteArray1: ByteArray, byteArray2: ByteArray): ByteArray {
        Timber.tag("concatenateByteArraysWithIgnoring").d("byteArray 1 : ${byteArray1.toHex()}")
        Timber.tag("concatenateByteArraysWithIgnoring").d("byteArray 2 : ${byteArray2.toHex()}")
        var byteArrayToReturn = ByteArray((byteArray1.size + byteArray2.size)-1)
        var index = 0
        var insertedIndex = 0
        while (index<4){
            byteArrayToReturn[index] = byteArray1[index]
            index ++
            insertedIndex ++
        }
        val shiftRight = byteArray2[0].toUByte().toInt() shr 4
        byteArrayToReturn[3] = byteArrayToReturn[3] or shiftRight.toByte()
        byteArrayToReturn[4] = getBytesShift(byteArray2[0], byteArray2[1])
        byteArrayToReturn[5] = getBytesShift(byteArray2[1], byteArray2[2])
        byteArrayToReturn[6] = getBytesShift(byteArray2[2], byteArray2[3])
        Timber.tag("concatenateByteArraysWithIgnoring").d("combined : ${byteArrayToReturn.toHex()}")
        return byteArrayToReturn
    }

    fun getBytesShift(byte1 : Byte , byte2 : Byte) : Byte {
        val byteShift1 = byte1.toUByte().toInt() shl 4
        val byteShift2 = byte2.toUByte().toInt() shr 4
        return byteShift1.toByte() or byteShift2.toByte()
    }

    private fun handleBlePackage (byteArray: ByteArray?, carrier: Carrier?){
        byteArray?.let { logByteArray("handleBlePackage", it) }
        if(savedByteArray == null){
            savedByteArray = byteArray
        }else {
            savedByteArray?.let { mArray ->
                byteArray?.let {
                    val concatenatedByteArray = concatenateByteArraysWithIgnoring(mArray, it)
                    logByteArray("handleBlePackageconcate", concatenatedByteArray)
                    appendToArray(concatenatedByteArray, carrier)
                }
            }
            savedByteArray = null
        }
    }


//    private fun appendToArray (byteArray: ByteArray?){
//        byteArray?.toList()?.let {
//            for(byte in it){
//                mutableByteListToSend.add(byte)
//                if(mutableByteListToSend.size == 78){
//                    sendToBle(mutableByteListToSend.toByteArray().copyOf())
//                    mutableByteListToSend.clear()
//                    numOfPackage++
//                }
//            }
//        }
//        resetTimer()
//    }

    fun shiftByteArrayEvery28Bits(input: ByteArray, shiftAmount: Int): ByteArray {
        val output = ByteArray(input.size)

        val shiftBytes = shiftAmount / 8
        val shiftBits = shiftAmount % 8

        for (i in input.indices) {
            val shiftedIndex = (i + shiftBytes) % input.size
            val shiftedByte = input[shiftedIndex].toInt() and 0xFF ushr shiftBits
            val nextIndex = (shiftedIndex + 1) % input.size
            val nextByte = input[nextIndex].toInt() and 0xFF shl (8 - shiftBits)
            output[i] = (shiftedByte or nextByte).toByte()
        }

        return output
    }

    private fun resetTimer(){
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 200)
    }

    private fun setMinData(){
        while (mutableByteListToSend.size < 78){
            mutableByteListToSend.add(0)
        }
    }

    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    private fun setRecordingParams(audioSessionID : Int, context: Context){
        try {
            val audioManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(AudioManager::class.java)
            } else {
                TODO("VERSION.SDK_INT < M")
            }
            val isNoise = SharedPreferencesUtil.getNoiseSuppressor(context)
            val isAGC = SharedPreferencesUtil.getAutoGainControl(context)
            val isAcoustic = SharedPreferencesUtil.getAcousticEchoControl(context)
            audioManager?.setParameters("noise_suppression=${if(isNoise) "on" else "off"}")
            if (NoiseSuppressor.isAvailable() && NoiseSuppressor.create(audioSessionID) == null) {
                var noiseSuppressor : NoiseSuppressor? = null
                noiseSuppressor  = NoiseSuppressor.create(audioSessionID)
                noiseSuppressor.enabled = isNoise
            }
            audioManager?.setParameters("automatic_gain_control=${if(isAGC) "on" else "off"}")
            if (AutomaticGainControl.isAvailable()&& AutomaticGainControl.create(audioSessionID) == null) {
                var agc: AutomaticGainControl? = null
                agc = AutomaticGainControl.create(audioSessionID)
                agc?.enabled = isAGC
            }
            audioManager?.setParameters("echo_cancellation=${if(isAGC) "on" else "off"}")
            if (AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(audioSessionID) == null) {
                var acoustic: AcousticEchoCanceler? = null
                acoustic = AcousticEchoCanceler.create(audioSessionID)
                acoustic.enabled = isAcoustic
            }
        }catch (e : Exception){
            e.printStackTrace()
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