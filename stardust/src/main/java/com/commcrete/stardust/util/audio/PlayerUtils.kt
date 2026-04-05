package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.aiaudio.media.PcmStreamPlayer
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.PttReceiveManager
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.DataManager.context
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.UsersUtils
import com.commcrete.stardust.util.UsersUtils.mRegisterUser
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
    val isPttReceived : MutableLiveData<String> = MutableLiveData("empty")
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
                file?.let {
                    val byteArray = byteArrayOutputStream.toByteArray().copyOf()
                    writePTTReceivedData(byteArray, it)
                }
                fileSniffer?.let {
                    val byteArray = byteArrayOutputStream.toByteArray().copyOf()
                    writePTTReceivedData(byteArray, it)
                }
                fileToWrite = null
                fileToWriteSniffer = null
                byteArrayOutputStream.reset()
                numOfPackagesRecieved = 0
            }
            val packageToPass = StardustAPIPackage(source, destination)
            val realSource = packageToPass.getRealSourceId()
            updateAudioReceived(source, realSource, false)
            destination = ""
            Handler(Looper.getMainLooper()).postDelayed({
                source = ""
                ts = ""
            }, 500)
            ts = ""
            isFileInit = false
            PcmStreamPlayer.releaseLegacyTrack()
            isPlaying = false
            if(numOfPackagesRecieved == 1) {
                PcmStreamPlayer.writeMinimumSilenceAndPlay(byteArrayOutputStream.toByteArray().copyOf()) {
                    Timber.tag("WavRecorder.TAG_PTT_DEBUG").d("bufferSizeInFrames")
                }
                byteArrayOutputStream.reset()
            }
            StardustPackageUtils.packageLiveData.value = null
            mCodec2Decoder.rawAudioOutBytesBuffer.clear()
            Timber.tag(WavRecorder.TAG_PTT_DEBUG).d("rawAudioOutBytesBuffer.clear() runnable")
//            removeSyncBleDevices ()
            isPttReceived.value = "empty"
        }
    }

    var mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)

    var destination : String = ""
    var source : String = ""
    var fileToWrite : File? = null
    var fileToWriteSniffer : File? = null


    private fun playAudio(
        context: Context,
        pttAudio: ByteArray,
        receiverID: String,
        senderID: String,
        snifferContacts: List<ChatContact>?
    ) {
        Log.d(
            PLAYBACK_TRACE_TAG,
            "playAudio receiver=$receiverID sender=$senderID size=${pttAudio.size} sniffer=${snifferContacts != null} flag=${DataManager.isPlayPttFromSdk}"
        )
        val myId = mRegisterUser?.appId.orEmpty()

        val isLocalGroupCall = when {
            snifferContacts != null ->
                isLocalGroup(
                    snifferContacts[0].bittelId,
                    snifferContacts[1].bittelId
                )
            else ->
                isLocalGroup(senderID, receiverID)
        }

        val shouldHandleAudio = when {
            snifferContacts != null ->
                !isMyId(
                    myId,
                    snifferContacts[0].bittelId,
                    snifferContacts[1].bittelId
                ) && !isLocalGroupCall
            else -> true
        }

        if (shouldHandleAudio) {
            Log.d(
                PLAYBACK_TRACE_TAG,
                "playAudio -> playPTT shouldHandleAudio=true isLocalGroup=$isLocalGroupCall"
            )
            playPTT(
                pttAudio,
                pttAudio.size,
                senderID,
                receiverID,
                isLocalGroupCall
            )
        }
        if (!shouldHandleAudio) {
            Log.d(PLAYBACK_TRACE_TAG, "playAudio skipped playPTT because shouldHandleAudio=false")
        }

        resetTimer()
        setTs()

        pttScope.launch {

            runCatching {
                if (!isFileInit) {
                    initPttInputFile(context, receiverID, senderID, snifferContacts)
                }
                if (shouldHandleAudio) {
                    byteArrayOutputStream.write(pttAudio)
                }
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    fun handleSnifferMessage (
        bittelPackage: StardustPackage,
        id: String,
        snifferContacts: List<ChatContact>?
    ) {
        Log.d(
            PLAYBACK_TRACE_TAG,
            "handleSnifferMessage source=${bittelPackage.getSourceAsString()} destination=${bittelPackage.getDestAsString()} id=$id"
        )
        getPackageByFrames(bittelPackage, id, snifferContacts)
    }

    private fun getPackageByFrames(bittelPackage: StardustPackage, receiverID: String, snifferContacts: List<ChatContact>? = null){
        bittelPackage.data?.let { dataArray -> //dataArray = Array<Int>
            val byteArray = intArrayToByteArray(dataArray.toMutableList())
            source = bittelPackage.getSourceAsString()
            Log.d(
                PLAYBACK_TRACE_TAG,
                "getPackageByFrames source=$source receiver=$receiverID rawSize=${byteArray.size} sniffer=${snifferContacts != null}"
            )
            testPlayPackage(byteArray, source, receiverID, snifferContacts)
        }
    }


    private fun playPTT(audioStream: ByteArray, size: Int, source: String, destination: String, isGroup: Boolean) {
            Log.d(
                PLAYBACK_TRACE_TAG,
                "playPTT source=$source destination=$destination size=$size isGroup=$isGroup flag=${DataManager.isPlayPttFromSdk}"
            )
        PcmStreamPlayer.playLegacyStream(
            audioData = audioStream,
            bufferSizeInBytes = size,
            receivedPkgs = numOfPackagesRecieved,
            playFromSdk = true
        )
        //here
        DataManager.getCallbacks()?.receivePTT(StardustAPIPackage(source, destination), audioStream)

    }

    private fun writePTTReceivedData(pttAudio: ByteArray, file: File ){
        val outputStream: OutputStream = FileOutputStream(file, true)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        val dataOutputStream = DataOutputStream(bufferedOutputStream)
        dataOutputStream.write(pttAudio)
        dataOutputStream.close()
    }

    private fun initPttInputFile(context: Context, destinations: String, source: String
                                         , snifferContacts: List<ChatContact>?) : File? {

        if(snifferContacts != null) {
            return initPttSnifferFile(context ,destinations,  snifferContacts)
        }
        val destination = destinations.trim().replace("[\"", "").replace("\"]", "")
        this.destination = destinations

        val packageToPass = StardustAPIPackage(source, destination)
        val realSource = packageToPass.getRealSourceId()
        updateAudioReceived(source, realSource, true)
        val directory = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/${source}")
        val file = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/${source}/$ts-$source.pcm")
        if(directory != null){
            if(!directory.exists()){
                directory.mkdir()
            }
            if (file != null) {
                if(!file.exists()){
                    file.createNewFile()
                    fileToWrite = file
                    Scopes.getDefaultCoroutine().launch {
                        val userName = UsersUtils.getUserName(realSource)
                        try {
                            Timber.tag("savePTT").d("ts : ${ts.toLong()}")
                        }catch (e :Exception){
                            e.printStackTrace()
                        }
                        DataManager.getAppRepo(context).saveMessage(
                            context = context,
                            isPTT = true,
                            messageItem = MessageItem(
                                senderID = realSource,
                                epochTimeMs = ts.toLong(),
                                senderName = userName ,
                                chatId = source,
                                text = "",
                                fileLocation = file.absolutePath,
                                isAudio = true)
                        )
                        DataManager.getCallbacks()?.startedReceivingPTT(packageToPass, file)
                    }
                }
                isFileInit = true
            }
        }
        return file
    }

    fun initPttSnifferFile(
        context: Context,
        destinations: String,
        snifferContacts: List<ChatContact>?
    ) : File? {
        var sniffed : MutableList<ChatContact> = mutableListOf()
        val directory = if(fileToWriteSniffer !=null) fileToWriteSniffer else File("${context.filesDir}/$destinations")
        val file = if(fileToWriteSniffer !=null) fileToWriteSniffer else File("${context.filesDir}/$destinations/$ts.pcm")

        if(directory != null){
            if(!directory.exists()){
                directory.mkdir()
            }
            if (file != null) {
                if(!file.exists()){
                    file.createNewFile()
                    fileToWriteSniffer = file
                    Scopes.getDefaultCoroutine().launch {
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

                        val senderID = sniffed[0].chatUserId ?: ""
                        val receiverID = sniffed[1].chatUserId ?: ""

                        DataManager.getCallbacks()?.startedReceivingPTT(StardustAPIPackage(senderID, receiverID), file)
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

    fun setTs(){
        if(ts.isEmpty()){
            ts = (System.currentTimeMillis()).toString()
        }
    }

    fun saveBittelMessageToDatabase(context: Context, bittelPackage: StardustPackage){
        Scopes.getDefaultCoroutine().launch {

            if(bittelPackage.getSourceAsString().isNotEmpty()){
                val chatsRepo = ChatsRepository(com.commcrete.stardust.room.new_db.AppDatabase.getDatabase(context).chatsDao())
                val packageToPass = StardustAPIPackage(bittelPackage.getSourceAsString(), bittelPackage.getDestAsString())
                val realSource = packageToPass.getRealSourceId()
                getPackageByFrames(bittelPackage, bittelPackage.getDestAsString())
                val chatItem = chatsRepo.getChatByBittelID(realSource)
                chatItem?.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        val chatName = if(packageToPass.isGroup) "${contact.displayName} to Group" else contact.displayName
                        val message = "PTT From : $chatName"
                        Scopes.getMainCoroutine().launch {
                            isPttReceived.value = message
                        }
                    }
                }
            }
        }
    }

    fun saveBittelPTTAiToDatabase(bittelPackage: StardustPackage) {
        Scopes.getDefaultCoroutine().launch {
            val source = bittelPackage.getSourceAsString()
            if(source.isNotEmpty()){
                val chatsRepo = ChatsRepository(com.commcrete.stardust.room.new_db.AppDatabase.getDatabase(DataManager.context).chatsDao())
                val destination = bittelPackage.getDestAsString()
                val packageToPass = StardustAPIPackage(source, destination)
                val realSource = packageToPass.getRealSourceId()
                val chatItem = chatsRepo.getChatByBittelID(realSource)
                chatItem?.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        val chatName = if(packageToPass.isGroup) "${contact.displayName} to Group" else contact.displayName
                        val message = "PTT From : $chatName"
                        Scopes.getMainCoroutine().launch {
                            isPttReceived.value = message

                        }
                    }
                }

                bittelPackage.data?.let { dataArray -> //dataArray = Array<Int>
                    val byteArray = intArrayToByteArray(dataArray.toMutableList())
                    Log.d("PlayerUtils", "Received PTT AI data size: ${byteArray.size}")
                    if (byteArray.size > 1) {
                        val model = byteArray.copyOfRange(0, 1)
                        val selectedModule = getModel(model[0].toInt())
                        val withoutFirstByte = byteArray.copyOfRange(1, byteArray.size)
                        Log.d("PlayerUtils", "Received PTT AI data size withoutFirstByte: ${withoutFirstByte.size}")
                        PttReceiveManager.addNewData(byteArray, realSource, source, selectedModule)
                        //here
                        DataManager.getCallbacks()?.receivePTT(packageToPass, byteArray)
                    }
                }
            }
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
        }catch (e : Exception) {
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

    private fun testPlayPackage(byteArray: ByteArray, source: String, receiverID : String, snifferContacts: List<ChatContact>? = null){
        PcmStreamPlayer.ensureLegacyTrack((14080 * bufferSizeMulti).toInt(), speedFactor)
        var bytes = splitByteArray(byteArray, 7)
        var bytesListToPlay : MutableList<ByteArray> = mutableListOf()
        for(mByte in bytes) {
            Timber.tag("decodedBytes").d("decodedBytes : ${byteArray.size}")
            var decodedBytes = handleBittelAudioMessage(mByte)
            if(!mByte.contentEquals(embpyByte)){
                bytesListToPlay.add(decodedBytes)

            }
        }
        val combined = combine(bytesListToPlay)
        Log.d(
            PLAYBACK_TRACE_TAG,
            "testPlayPackage(sniffer) source=$source receiver=$receiverID frames=${bytes.size} decodedChunks=${bytesListToPlay.size} combinedSize=${combined.size}"
        )
        playAudio(context, combined, receiverID, source, snifferContacts)
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

    fun updateAudioReceived(chatId: String, senderID: String, isAudioReceived : Boolean){
        if(!DataManager.getSavePTTFilesRequired(context) || chatId.isEmpty()) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val repo = DataManager.getAppRepo(context)
            repo.updateAudioReceived(chatId, isAudioReceived)
            val chatItem = repo.getChatByDeviceId(chatId)
            chatItem?.let {
                chatItem.message = Message(senderID = senderID, text = "Ptt Received", seen = true)
                repo.addChat(it)
                repo.updateNumOfUnseenMessages(chatId, chatItem.numOfUnseenMessages + 1)
            }
        }
    }


    fun playNotificationSound(context: Context) {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    fun isMyId(myId : String, sender : String?, receiver : String?) : Boolean {
        return sender.equals(myId, ignoreCase = true) || receiver.equals(myId, ignoreCase = true)
    }

    fun isLocalGroup(sender : String?, receiver : String?) : Boolean {
        return GroupsUtils.isGroup(sender) || GroupsUtils.isGroup(receiver)
    }
}
