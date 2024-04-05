package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.ble.BleManager
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.messages.SeenStatus
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.FileUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.ustadmobile.codec2.Codec2Decoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private fun sendRecordEnd(){
        setMinData()
        //todo Correction crash
        sendData(mutableByteListToSend.toByteArray().copyOf(), true)
        mutableByteListToSend.clear()
        numOfPackage++
//        Toast.makeText(context, "Sent $numOfPackage Packages", Toast.LENGTH_LONG ).show()
        numOfPackage = 0
    }

    @SuppressLint("MissingPermission")
    fun startRecording(path: String, destination: String) {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RECORDER_SAMPLE_RATE, RECORDER_CHANNELS,
            RECORDER_AUDIO_ENCODING, BufferElements2Rec)

        recorder?.audioSessionId?.let { setRecordingParams(it) }
        syncBleDevice(context)
        recorder?.startRecording()
        isRecording = true

        recordingThread = thread(true) {
            writeAudioDataToFile(path)
        }
    }

    private fun syncBleDevice (context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager)
        bleDevice?.let {
            recorder?.setPreferredDevice(it)
            audioManager.startBluetoothSco()
        }
    }

    private fun removeSyncBleDevices (context: Context) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager)
        bleDevice?.let {
            audioManager.stopBluetoothSco()
        }
    }


    fun stopRecording(chatID: String, path: String, context: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            recorder?.run {
                sendRecordEnd()
                isRecording = false
                stop()
                release()
                removeSyncBleDevices (context)
                recordingThread = null
                recorder = null
                savePtt(chatID, path, context)
            }
        }, 400)
    }

    private fun writeAudioDataToFile(path: String) {


        val targetGain = 1.5f // Adjust to the desired target gain level
        val sData = ShortArray(BufferElements2Rec)
        var os: FileOutputStream? = null
        try {
            os = FileOutputStream(path)

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
//                val codec2Encoder = Codec2Encoder(RecorderUtils.CodecValues.MODE700.mode)
//                val charArray = CharArray(RecorderUtils.CodecValues.MODE700.charNumOutput)
//                codec2Encoder.encode(sData, charArray)
//                val byteaArray = charsToBytes(charArray)
//                byteaArray?.let {
//                    logByteArray("logByteArrayInputRecorder", it)
//                    for (byte in byteaArray)
//                        dataPrint.add(byte)
//                }
//                val byteBuffer = codec2Decoder.readFrame(byteaArray)
//                val bDataCodec = byteBuffer.array()
//                logByteArray("logByteArrayOutputRecorder", bDataCodec)
//                for (byte in bDataCodec)
//                    data.add(byte)


//                if(BleManager.isNetworkEnabled()){
//                    handleBlePackage(byteaArray)
//                }
//                else if (BleManager.isBluetoothEnabled()) {
////                    send to BLE
//                    handleBlePackage(byteaArray)
//                }else {
//                    Scopes.getMainCoroutine().launch {
////                        viewModel?.error?.value = "Unable To Send Message - No Connection"
//                    }
//                }
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
                MessagesRepository(MessagesDatabase.getDatabase(context).messagesDao()).savePttMessage(
                    MessageItem(senderID = it,
                        epochTimeMs = RecorderUtils.ts, senderName = "" ,
                        chatId = chatID, text = "", fileLocation = path,
                        isAudio = true, seen = SeenStatus.SENT)
                )
            }
            RecorderUtils.ts = 0
            RecorderUtils.file = null
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

    private fun sendData(byteArray: ByteArray, isLast : Boolean = false){
        if(BleManager.isNetworkEnabled()){
//            sendToServer(byteArray, isLast)
        }else if (BleManager.isBluetoothEnabled()) {
            sendToBle(byteArray, isLast)
        }
    }

    private fun sendToBle(byteArray: ByteArray, isLast : Boolean = false) {
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
            bittelPackage?.stardustControlByte?.stardustPartType = if( isLast) StardustControlByte.StardustPartType.LAST else StardustControlByte.StardustPartType.MESSAGE
            bittelPackage?.checkXor =
                bittelPackage?.getStardustPackageToCheckXor()
                    ?.let { StardustPackageUtils.getCheckXor(it) }

            bittelPackage?.let {
                viewModel?.sendDataToBle(it)
//                sendWithTimer(it)
            }

        }
    }

    private fun generateRandomNumber(): Int {
        return Random.nextInt(0, 41) // 41 is exclusive
    }

    private fun appendToArray (byteArray: ByteArray?){
        val maxSecondsPTT = SharedPreferencesUtil.getPTTTimeout(context)
        if(numOfPackage.times(880) > maxSecondsPTT){

            viewModel?.let {
                it.getDestenation()
                    ?.let { it1 ->
                        RecorderUtils.file?.absolutePath?.let { it2 ->
                        stopRecording(it1,
                            it2, context
                        )
                            it.maxPTTTimeoutReached()
                    } }
            }
        }else {
            byteArray?.toList()?.let {
                for(byte in it){
                    mutableByteListToSend.add(byte)
                    if(mutableByteListToSend.size == 77){
                        mutableByteListToSend.add(byte)
                        sendData(mutableByteListToSend.toByteArray().copyOf())
                        mutableByteListToSend.clear()
                        numOfPackage++
                    }
                }
            }
            resetTimer()
        }
    }

    private fun concatenateByteArraysWithIgnoring(byteArray1: ByteArray, byteArray2: ByteArray): ByteArray {
        var byteArrayToReturn = ByteArray((byteArray1.size + byteArray2.size)-1)
        var index = 0
        var insertedIndex = 0
        while (index<4){
            byteArrayToReturn[index] = byteArray1[index]
            index ++
            insertedIndex ++
        }
        val shiftRight = byteArray2[0].toInt() shr 4
        byteArrayToReturn[3] = byteArrayToReturn[3] or shiftRight.toByte()
        byteArrayToReturn[4] = getBytesShift(byteArray2[0], byteArray2[1])
        byteArrayToReturn[5] = getBytesShift(byteArray2[1], byteArray2[2])
        byteArrayToReturn[6] = getBytesShift(byteArray2[2], byteArray2[3])
        return byteArrayToReturn
    }

    fun getBytesShift(byte1 : Byte , byte2 : Byte) : Byte {
        val byteShift1 = byte1.toUByte().toInt() shl 4
        val byteShift2 = byte2.toUByte().toInt() shr 4
        return byteShift1.toByte() or byteShift2.toByte()
    }

    private fun handleBlePackage (byteArray: ByteArray?){
        byteArray?.let { logByteArray("handleBlePackage", it) }
        if(savedByteArray == null){
            savedByteArray = byteArray
        }else {
            savedByteArray?.let { mArray ->
                byteArray?.let {
                    val concatenatedByteArray = concatenateByteArraysWithIgnoring(mArray, it)
                    logByteArray("handleBlePackageconcate", concatenatedByteArray)
                    appendToArray(concatenatedByteArray)
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

    private fun setRecordingParams(audioSessionID : Int){
        try {
            val TAG_RECORDER = "setRecordingParams"
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager.setParameters("noise_suppression=on")
            if (NoiseSuppressor.isAvailable() && NoiseSuppressor.create(audioSessionID) == null) {
                NoiseSuppressor.create(audioSessionID).enabled = true
            } else {
            }

            if (AutomaticGainControl.isAvailable() && AutomaticGainControl.create(audioSessionID) == null) {
                AutomaticGainControl.create(audioSessionID).enabled = false
            } else {
            }

            if (AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(audioSessionID) == null) {
                AcousticEchoCanceler.create(audioSessionID).enabled = false
            } else {
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
        return Base64.getEncoder().encodeToString(bytes)
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