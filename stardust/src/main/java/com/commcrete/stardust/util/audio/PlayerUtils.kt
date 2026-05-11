package com.commcrete.stardust.util.audio


import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.stardust.ai.codec.WavTokenizerDecoder
import com.commcrete.stardust.ai.codec.Codec2PcmStreamPlayer
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.PttReceiveManager
import com.commcrete.stardust.room.legacy_db.contacts.ChatContact
import com.commcrete.stardust.room.new_db.message.EncoderType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.appContext
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.RegisteredUserUtils.mRegisterUser
import com.ustadmobile.codec2.Codec2Decoder
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*


object PlayerUtils : BleMediaConnector() {

    private const val PLAYBACK_TRACE_TAG = "PTTPlaybackTrace"

    val embpyByte : ByteArray = byteArrayOf(0,0,0,0)

    const val sampleRate = 8000
    private var spareBytes : ByteArray? = null

    private val handler : Handler = Handler(Looper.getMainLooper())
    var ts = ""
    var byteArrayOutputStream = ByteArrayOutputStream()
    var isPlaying = false
    var isFileInit = false
    const val bufferSizeMulti = 1.0
    val speedFactor = 1.0f // 0.9x speed
    var numOfPackagesRecieved = 0

    private val pttScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runnable : Runnable = Runnable {
        Scopes.getMainCoroutine().launch {
            CoroutineScope(Dispatchers.IO).launch {
                val file = fileToWrite
                val fileSniffer = fileToWriteSniffer
                val byteArray = byteArrayOutputStream.toByteArray().copyOf()
                file?.let {
                    writePTTReceivedData(byteArray, it)
                }
                fileSniffer?.let {
                    writePTTReceivedData(byteArray, it)
                }
                fileToWrite = null
                fileToWriteSniffer = null
                byteArrayOutputStream.reset()
                numOfPackagesRecieved = 0
            }
            destination = ""
            Handler(Looper.getMainLooper()).postDelayed({
                source = ""
                ts = ""
            }, 500)
            ts = ""
            isFileInit = false
            Codec2PcmStreamPlayer.releaseTrack()
            isPlaying = false
            if(numOfPackagesRecieved == 1) {
                Codec2PcmStreamPlayer.writeMinimumSilenceAndPlay(byteArrayOutputStream.toByteArray().copyOf()) {
                    Timber.tag("WavRecorder.TAG_PTT_DEBUG").d("bufferSizeInFrames")
                }
                byteArrayOutputStream.reset()
            }
            StardustPackageUtils.packageLiveData.value = null
            mCodec2Decoder.rawAudioOutBytesBuffer.clear()
            Timber.tag(WavRecorder.TAG_PTT_DEBUG).d("rawAudioOutBytesBuffer.clear() runnable")
        }
    }

    var mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

    var destination : String = ""
    var source : String = ""
    var fileToWrite : File? = null
    var fileToWriteSniffer : File? = null


    private fun parseAIPackageByFrames(dataPackage: StardustPackage): ParsedAiData? {
        dataPackage.data?.let { dataArray -> //dataArray = Array<Int>
            val byteArray = intArrayToByteArray(dataArray.toMutableList())

            if (byteArray.size > 1) {
                val model = byteArray.copyOfRange(0, 1)
                val selectedModule = getModel(model[0].toInt())
                val withoutFirstByte = byteArray.copyOfRange(1, byteArray.size)

                Log.d("PlayerUtils", "Received PTT AI data size withoutFirstByte: ${withoutFirstByte.size}")

                return ParsedAiData(
                    decodedBytes = byteArray,
                    selectedModule = selectedModule
                )
            }
        }
        return null
    }

    data class ParsedAiData(
        val decodedBytes: ByteArray,
        val selectedModule: WavTokenizerDecoder.ModelType?
    )

    private fun parseCodecPackageByFrames(dataPackage: StardustPackage): ByteArray? {
        val parsedData = dataPackage.data?.let { dataArray -> intArrayToByteArray(dataArray.toMutableList()) } ?: return null
        val bytes = splitByteArray(parsedData, 7)
        val bytesListToPlay : MutableList<ByteArray> = mutableListOf()

        for(mByte in bytes) {
            Timber.tag("decodedBytes").d("decodedBytes : ${parsedData.size}")
            val decodedBytes = handleBittelAudioMessage(mByte)

            if(!mByte.contentEquals(embpyByte)) {
                bytesListToPlay.add(decodedBytes)
            }
        }

        return combine(bytesListToPlay)
    }


    private fun playPTT(audioStream: ByteArray) {
        Codec2PcmStreamPlayer.ensureTrack((14080 * bufferSizeMulti).toInt(), speedFactor)
        Codec2PcmStreamPlayer.playStream(
            audioData = audioStream,
            bufferSizeInBytes = audioStream.size,
            receivedPkgs = numOfPackagesRecieved,
            playFromSdk = true
        )
    }

    private fun writePTTReceivedData(pttAudio: ByteArray, file: File) {
        val outputStream: OutputStream = FileOutputStream(file, true)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        val dataOutputStream = DataOutputStream(bufferedOutputStream)
        dataOutputStream.write(pttAudio)
        dataOutputStream.close()
    }

    suspend fun initPttInputFile(
        senderId: String,
        groupId: String? = null,
        receiverId: String,
        type: EncoderType) : File? {

        val appId = mRegisterUser.value?.appId ?: return null
        val dirSource = groupId ?: senderId
        this.destination = receiverId

        val directory = if(fileToWrite != null) fileToWrite else File("${appContext.filesDir}/${dirSource}")
        val file = if(fileToWrite != null) fileToWrite else File("${appContext.filesDir}/${dirSource}/$ts-$senderId.pcm")

        if(directory != null) {
            if(!directory.exists()) { directory.mkdir() }
            if (file != null) {
                if(!file.exists()) {
                    file.createNewFile()
                    fileToWrite = file
                    DataManager.getAppRepo().saveMessage(
                        MessageEntity(
                            senderID = senderId,
                            receiverID = appId,
                            state = MessageState.RECEIVING,
                            epochTimeMs = ts.toLong(),
                            extraData = MessageExtraData.PTT(
                                encoderType = type,
                                path = file.absolutePath
                            )
                        ), groupId
                    )

                }
                isFileInit = true
            }
        }
        return file
    }

    fun initPttSnifferFile(
        destinations: String,
        snifferContacts: List<ChatContact>?
    ) : File? {
        val appId = mRegisterUser.value?.appId ?: return null
        var sniffed : MutableList<ChatContact>
        val directory = if(fileToWriteSniffer !=null) fileToWriteSniffer else File("${appContext.filesDir}/$destinations")
        val file = if(fileToWriteSniffer !=null) fileToWriteSniffer else File("${appContext.filesDir}/$destinations/$ts.pcm")

        if(directory != null){
            if(!directory.exists()){
                directory.mkdir()
            }
            if (file != null) {
                if(!file.exists()){
                    file.createNewFile()
                    fileToWriteSniffer = file
                    CoroutineScope(Dispatchers.IO).launch {
                        // TODO: Change to sniffer message
                        try {
                            Timber.tag("savePTT").d("ts : ${ts.toLong()}")
                        } catch (e :Exception){
                            e.printStackTrace()
                        }

                        sniffed = mutableListOf()
                        snifferContacts?.get(0)?.let {
                            sniffed.add(it)
                        }
                        snifferContacts?.get(1)?.let {
                            sniffed.add(it)
                        }
                        if(snifferContacts != null && snifferContacts[0].isGroup) {
                            val tempSender = snifferContacts[0]
                            val tempReceiver = snifferContacts[1]
                            sniffed = mutableListOf()
                            sniffed.add(tempReceiver)
                            sniffed.add(tempSender)
                        }

                        val ids = GroupsUtils.resolveGroupAndContact(sniffed[0].chatUserId ?: "", sniffed[1].chatUserId ?: "")

                        DataManager.getCallbacks()?.startedReceivingPTT(
                            StardustAPIPackage(
                                senderId = ids.senderId,
                                groupId = ids.groupId,
                                receiverId = appId),
                            file)
                    }
                }
            }
        }
        return file
    }

    private fun resetTimer(){
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 2000)
    }

    fun setTs() {
        if(ts.isEmpty()) {
            ts = (System.currentTimeMillis()).toString()
        }
    }

    private suspend fun saveReceivingPttMessage(
        dataPackage: StardustPackage,
        appId: String,
        encoderType: EncoderType,
        file: File
    ): Long? {
        val senderId = dataPackage.senderId
        val groupId = dataPackage.groupId

        return DataManager.getAppRepo().saveMessage(
            MessageEntity(
                senderID = senderId,
                receiverID = appId,
                state = MessageState.RECEIVING,
                extraData = MessageExtraData.PTT(
                    path = file.absolutePath,
                    encoderType = encoderType
                ),
                epochTimeMs = System.currentTimeMillis()
            ),
            groupId
        )
    }

    fun onPTTCodecReceived(dataPackage: StardustPackage) {
        val destinationId = mRegisterUser.value?.appId ?: return
        val parsedData = parseCodecPackageByFrames(dataPackage) ?: return

        source = dataPackage.getSourceAsString()
        resetTimer()

        updateReceivingPtt(dataPackage, destinationId, parsedData)

        playPTT(parsedData)
    }

    private fun updateReceivingPtt(
        dataPackage: StardustPackage,
        destinationId: String,
        parsedData: ByteArray
    ) {
        pttScope.launch {

            runCatching {
                val pkg = StardustAPIPackage(senderId = dataPackage.senderId, groupId = dataPackage.groupId, receiverId = destinationId)

                if (!isFileInit) {
                    setTs()
                    val file = initPttInputFile(senderId = dataPackage.senderId, groupId = dataPackage.groupId, receiverId = destinationId, type = EncoderType.CODEC2) ?: return@runCatching
                    DataManager.getCallbacks()?.startedReceivingPTT(pkg, file)
                }
                else {
                    DataManager.getCallbacks()?.receivePTT(pkg, parsedData)
                }
                byteArrayOutputStream.write(parsedData)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    fun onPTTAiReceived(dataPackage: StardustPackage) {
        CoroutineScope(Dispatchers.IO).launch {
            val parsedData = parseAIPackageByFrames(dataPackage) ?: return@launch

            PttReceiveManager.addNewData(parsedData, dataPackage)

        }
    }

    private fun getModel(modelValue: Int): WavTokenizerDecoder.ModelType? {
        return WavTokenizerDecoder.ModelType.fromInt(modelValue)
    }

    private fun handleBittelAudioMessage(byteArray: ByteArray) : ByteArray{
        try {
            val byteBuffer = mCodec2Decoder.readFrame(byteArray)
            val bDataCodec = byteBuffer.array()
            logByteArray("logByteArrayOutputPlayer", bDataCodec)
            val data = arrayListOf<Byte>()
            for (byte in bDataCodec) data.add(byte)
            return data.toByteArray()
        } catch (e : Exception) {
            e.printStackTrace()
            mCodec2Decoder.destroy()
            mCodec2Decoder.rawAudioOutBytesBuffer.clear()
            mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
            return byteArrayOf()
        }
    }

    private fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }

    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    private fun splitByteArray(input: ByteArray, chunkSize: Int): List<ByteArray> {
        Timber.tag("receiveBeforeSplit").d("input : ${input.toHex()}")
        val splits = mutableListOf<ByteArray>()
        var startIndex = 0

        while (startIndex < input.size) {
            // Calculate endIndex for the current chunk
            val endIndex = minOf(startIndex + chunkSize, input.size)
            // Copy a portion of the array into a new array
            val chunk = input.copyOfRange(startIndex, endIndex)
            // Add the chunk to the result list
            splits.add(chunk)
            // Move the start index forward by chunkSize
            startIndex += chunkSize
        }

        val result = mutableListOf<ByteArray>()
        for (split in splits) {
            Timber.tag("receiveAfterSplit").d("split : ${split.toHex()}")
            result.addAll(splitByteArray2(split))
        }
        return result
    }

    private fun splitByteArray2(combined: ByteArray): List<ByteArray> {
        return try {
            Timber.tag("concatenateByteArraysWithIgnoring")
                .d("byteArray origin : ${combined.toHex()}")

            val byteArray1 = ByteArray(4)
            val byteArray2 = ByteArray(4)

            // Extract byteArray1 from the first 4 bytes of the combined array
            for (i in 0 until 4) {
                byteArray1[i] = combined[i]
            }
            byteArray1[3] = (byteArray1[3].toInt() and 0xF0).toByte()

            // Reverse the manipulation to retrieve byteArray2
            byteArray2[0] = ((combined[3].toUByte().toInt() shl 4) or (combined[4].toUByte().toInt() shr 4)).toByte()
            byteArray2[1] = ((combined[4].toUByte().toInt() shl 4) or (combined[5].toUByte().toInt() shr 4)).toByte()
            byteArray2[2] = ((combined[5].toUByte().toInt() shl 4) or (combined[6].toUByte().toInt() shr 4)).toByte()
            byteArray2[3] = (combined[6].toUByte().toInt() shl 4).toByte()

            Timber.tag("receiveAfterSplit").d("byteArray 1 : ${byteArray1.toHex()}")
            Timber.tag("receiveAfterSplit").d("byteArray 2 : ${byteArray2.toHex()}")

            listOf(byteArray1, byteArray2)

        } catch (e: Exception) {
            Timber.tag("splitByteArray2").e(e, "Error while splitting byte array")
            // Return safe default
            listOf(ByteArray(4), ByteArray(4))
        }
    }


    fun combine(byteArrayList: List<ByteArray>): ByteArray {
        var combinedSize = 0
        for (array in byteArrayList) {
            combinedSize += array.size
        }

        val result = ByteArray(combinedSize)
        var position = 0
        for (array in byteArrayList) {
            System.arraycopy(array, 0, result, position, array.size)
            position += array.size
        }

        return result
    }

    suspend fun updateAudioReceived(messageId: Long) {
        DataManager.getAppRepo().updateMessageReceived(messageId)
    }


    fun playNotificationSound() {
        Handler(Looper.getMainLooper()).post {
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(appContext, notificationUri)
                ringtone.play()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}
