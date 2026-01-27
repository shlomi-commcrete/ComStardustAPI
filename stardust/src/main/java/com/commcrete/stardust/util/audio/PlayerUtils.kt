package com.commcrete.stardust.util.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRouter
import android.media.RingtoneManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.util.UnstableApi
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.bittell.room.sniffer.SnifferDatabase
import com.commcrete.bittell.room.sniffer.SnifferItem
import com.commcrete.bittell.room.sniffer.SnifferRepository
import com.commcrete.bittell.util.sniffer.isLocalGroup
import com.commcrete.bittell.util.sniffer.isMyId
import com.commcrete.stardust.StardustAPIPackage
import com.commcrete.stardust.ai.codec.PttReceiveManager
import com.commcrete.stardust.request_objects.Message
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.contacts.ChatContact
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
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
import kotlin.experimental.and
import kotlin.experimental.or


object PlayerUtils : BleMediaConnector() {

    val embpyByte : ByteArray = byteArrayOf(0,0,0,0)

    private var mediaPlayer : SafePlayer? =  null
    private var track: AudioTrack? = null
    const val sampleRate = 8000
    private var spareBytes : ByteArray? = null
    val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())
    val chatsRepository = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
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
            Scopes.getDefaultCoroutine().launch {
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

            val sentAsUserInGroup = GroupsUtils.isGroup(source) && (destination != mRegisterUser?.appId)
            val realSource = if(sentAsUserInGroup) destination else source
            updateAudioReceived(source, realSource, false)
            destination = ""
            Handler(Looper.getMainLooper()).postDelayed({
                source = ""
                ts = ""
            }, 500)
            ts = ""
            isFileInit = false
            track?.flush()
            track?.release()
            enhancer?.release()
            equalizer?.release()
            track = null
            isPlaying = false
            if(numOfPackagesRecieved == 1) {
                writeMinimumSilenceAndPlay(byteArrayOutputStream.toByteArray().copyOf()) {
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


    val destinationLiveData : MutableLiveData<String> = MutableLiveData()
    var destination : String = ""
    var source : String = ""
    var fileToWrite : File? = null
    var fileToWriteSniffer : File? = null


    var enhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    val gainIncrease = 3000  // This value is in millibels (mB). 2000 mB equals a 200% gain increase.

//    private fun playAudio(context: Context, pttAudio: ByteArray, receiverID: String, senderID: String, snifferContacts: List<ChatContact>?) {
//
//        if(snifferContacts != null) {
//            if(!isMyId(mRegisterUser?.appId ?: "", snifferContacts[0].bittelId, snifferContacts[1].bittelId)
//                && !isLocalGroup(snifferContacts[0].bittelId, snifferContacts[1].bittelId)){
//                playPTT(pttAudio, pttAudio.size, senderID, receiverID , false)
//            }
//        }else {
//            playPTT(pttAudio, pttAudio.size , senderID, receiverID, isLocalGroup(senderID, receiverID))
//        }
//
//        resetTimer()
//        setTs()
//        CoroutineScope(Dispatchers.Default).launch {
//            if(!isFileInit){
//                initPttInputFile(context, receiverID, senderID, snifferContacts)
//            }
//            try {
//                if(snifferContacts != null) {
//                    if(!isMyId(mRegisterUser?.appId ?: "", snifferContacts[0].bittelId, snifferContacts[1].bittelId)
//                        && !isLocalGroup(snifferContacts[0].bittelId, snifferContacts[1].bittelId)){
//                        byteArrayOutputStream.write(pttAudio)
//                    }
//                }else {
//                    byteArrayOutputStream.write(pttAudio)
//                }
//            } catch (e: IOException) {
//                e.printStackTrace()
//            }
//        }
//
//    }

    private fun playAudio(
        context: Context,
        pttAudio: ByteArray,
        receiverID: String,
        senderID: String,
        snifferContacts: List<ChatContact>?
    ) {
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
            playPTT(
                pttAudio,
                pttAudio.size,
                senderID,
                receiverID,
                isLocalGroupCall
            )
        }

        resetTimer()
        setTs()

        pttScope.launch {
            if (!isFileInit) {
                initPttInputFile(context, receiverID, senderID, snifferContacts)
            }

            runCatching {
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
        getPackageByFrames(bittelPackage, id, snifferContacts)
    }

    private fun writeMinimumSilenceAndPlay(
        byteArray: ByteArray,
        onPlaybackComplete: () -> Unit
    ) {
        // Step 1: Get the minimum buffer size (in frames)

        val tempTrack = getTempTrack ()
        tempTrack.play()
        tempTrack.write(byteArray, 0, byteArray.size)

        // Step 4: Start playback
        Handler(Looper.getMainLooper()).postDelayed({
            tempTrack.flush()
            onPlaybackComplete()
        },880)
    }

    @SuppressLint("NewApi")
    private fun getTempTrack () : AudioTrack {
        val audioTrack: AudioTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(
                    AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(642).build()
//        audioTrack.setVolume(2.0f)
        audioTrack.audioSessionId.let {
//            Equalizer().getEq(it, DataManager.context)
            enhancer = LoudnessEnhancer(it)
            val audioPct = 5.4
            val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt() // Correct the gain calculation
            //                val gainmB = 1000
            enhancer?.setTargetGain(gainmB)
            enhancer?.setEnabled(true)
        }
        return audioTrack
    }

    private fun getPackageByFrames(bittelPackage: StardustPackage, receiverID: String, snifferContacts: List<ChatContact>? = null){
        bittelPackage.data?.let { dataArray -> //dataArray = Array<Int>
            val byteArray = intArrayToByteArray(dataArray.toMutableList())
            source = bittelPackage.getSourceAsString()
            testPlayPackage(byteArray, receiverID, snifferContacts)
        }
    }


    private fun playPTT(audioStream: ByteArray, size: Int, source: String, destination: String, isGroup: Boolean) {
//        if(App.isAppInForeground || SharedPreferencesUtil.getEnablePttSound(DataManager.context)){
            track?.let { playStream(it, audioStream, size) }
            DataManager.getCallbacks()?.receivePTT(StardustAPIPackage(source, destination), audioStream)

//        }
    }

    @SuppressLint("NewApi")
    private fun syncBleDevice (context: Context) {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager,AudioManager.GET_DEVICES_OUTPUTS, context)
        bleDevice?.let {
            track?.setPreferredDevice(it)
            audioManager.startBluetoothSco()
            audioManager.setBluetoothScoOn(true)
            if (it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                try {
                    routeAudioToMediaRouter(context)

                }catch (e : Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun routeAudioToMediaRouter(context: Context) {
        val mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
        val selectedRoute = mediaRouter.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)
        if (selectedRoute != null) {

        } else {

        }
    }


    @SuppressLint("NewApi")
    private fun removeSyncBleDevices (context: Context) {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager,AudioManager.GET_DEVICES_OUTPUTS, context)
        bleDevice?.let {
            audioManager.stopBluetoothSco()
        }
    }

    @SuppressLint("NewApi")
    private fun playStream(audioTrack: AudioTrack, audioData: ByteArray, bufferSizeInBytes: Int) {
        numOfPackagesRecieved++
//        App.getMainCoroutine().launch {
            try {
                val audioManager = DataManager.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//                syncBleDevice ()
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()


                    // Start playback
                    track?.notificationMarkerPosition = bufferSizeInBytes/2
                    val value = 1*40
                    val size = 1/4*320 // Specify the desired size of your byte array
                    val byteArray = ByteArray(size) { 0.toByte() }
                    Scopes.getDefaultCoroutine().launch{
                        track?.let {
                            if(DataManager.isPlayPttFromSdk) {
                                synchronized(it) {
                                    if (audioData.size >= value) {
                                        it.write(audioData, 0, audioData.size)
                                    }

                                }
                            }
                        }
                    }
            }catch (e: IllegalStateException){
                e.printStackTrace()
                initAudioTrack(bufferSizeInBytes)
                audioTrack.flush()
            }
//        }

    }

    private fun writePTTReceivedData(pttAudio: ByteArray, file: File ){
        val outputStream: OutputStream = FileOutputStream(file, true)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        val dataOutputStream = DataOutputStream(bufferedOutputStream)
        dataOutputStream.write(pttAudio)
        dataOutputStream.close()

    }

    private fun writePTTReceivedData(pttAudio: String, file: File ){
        val outputStream: OutputStream = FileOutputStream(file, true)
        val bufferedOutputStream = BufferedOutputStream(outputStream)
        val dataOutputStream = DataOutputStream(bufferedOutputStream)
        val splitString = pttAudio.trim().split(",")
        val data = arrayListOf<Byte>()
        for (audioData in splitString){
            if(audioData.isNotEmpty()){
                data.add(audioData.toByte())
            }
        }
        dataOutputStream.write(data.toByteArray())
        dataOutputStream.close()
    }

    private suspend fun initPttInputFile(context: Context, destinations: String, source: String
                                         , snifferContacts: List<ChatContact>?) : File? {

        if(snifferContacts != null) {
            return initPttSnifferFile(context ,destinations,  snifferContacts)
        }
        val destination = destinations.trim().replace("[\"", "").replace("\"]", "")
        this.destination = destinations

        val sentAsUserInGroup = GroupsUtils.isGroup(source) && (destination != mRegisterUser?.appId)
        val realSource = if(sentAsUserInGroup) destination else source
        updateAudioReceived(destination, realSource, true)
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
                        messagesRepository.savePttMessage(
                            context = context,
                            MessageItem(
                                senderID = realSource,
                                epochTimeMs = ts.toLong(),
                                senderName = userName ,
                                chatId = source,
                                text = "",
                                fileLocation = file.absolutePath,
                                isAudio = true)
                        )
                        DataManager.getCallbacks()?.startedReceivingPTT(StardustAPIPackage(source, destination), file)
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

                        val repo = SnifferRepository(SnifferDatabase.getDatabase(context).snifferDao())
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

                        repo.addContact(
                            SnifferItem(
                            senderID = senderID,
                            receiverID = receiverID,
                            senderName = sniffed[0].displayName,
                            receiverName = sniffed[1].displayName,
                            epochTimeMs = ts.toLong(),
                            chatId = destinations,
                            text = "",
                            fileLocation = file.absolutePath,
                            isAudio = true)
                        )
                        DataManager.getCallbacks()?.startedReceivingPTT(StardustAPIPackage(senderID, receiverID), file)
                    }
                }
            }
        }
        return file
    }

    private fun getPttInputStream(pttAudio : ByteArray) : ByteArray? {
        try {
            val audioShorArray = ByteArray(pttAudio.size)
            for ((indexCounter, audioData) in pttAudio.withIndex()){
                audioShorArray[indexCounter] = audioData.toByte()
            }
            return audioShorArray
        }catch (e : Exception){
            e.printStackTrace()
        }
        return null
    }

    private fun getPttInputStream(pttAudio : String) : ByteArray? {
        try {
            val splitString = pttAudio.trim().split(",")
            val audioShorArray = ByteArray(splitString.size)
            for ((indexCounter, audioData) in splitString.withIndex()){
                if(audioData.isNotEmpty()){
                    audioShorArray[indexCounter] = audioData.toByte()
                }else{
                    audioShorArray[indexCounter] = audioShorArray[indexCounter-1]
                }
            }
            return audioShorArray
        }catch (e : Exception){
            e.printStackTrace()
        }
        return null
    }
    @SuppressLint("NewApi")
    private fun initAudioTrack(bufferSizeInBytes: Int) {

        try {
            if(track == null){
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(
                    AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate((sampleRate * speedFactor).toInt()).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM) // Set to streaming mode
                    .setBufferSizeInBytes(bufferSizeInBytes).build()
                syncBleDevice(DataManager.context)
            }

            track?.audioSessionId?.let {
//                Equalizer().getEq(it, DataManager.context)
                enhancer = LoudnessEnhancer(it)
                val audioPct = 5.4
                val gainmB = Math.round(Math.log10(audioPct) * 2000).toInt()
                enhancer?.setTargetGain(gainmB)
                enhancer?.setEnabled(true)
            }
            Handler(Looper.getMainLooper()).postDelayed( {
                track?.play()

            }, 150)
        }catch (e : Exception){
            e.printStackTrace()
//            return null
        }
//        return track
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
                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(context).chatsDao())
                var realSource = bittelPackage.getSourceAsString()
                val sentAsUserInGroup = GroupsUtils.isGroup(realSource) && (bittelPackage.getDestAsString() != mRegisterUser?.appId)
                if(sentAsUserInGroup) {
                    realSource = bittelPackage.getDestAsString()
                }
                getPackageByFrames(bittelPackage, realSource)
                val chatItem = chatsRepo.getChatByBittelID(realSource)
                chatItem?.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        val chatName = if(sentAsUserInGroup) "${contact.displayName} to Group" else contact.displayName
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
                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
                var realSource = source
                val destination = bittelPackage.getDestAsString()
                val sentAsUserInGroup = GroupsUtils.isGroup(realSource) && (destination != mRegisterUser?.appId)
                if(sentAsUserInGroup) {
                    realSource = destination
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
                    }
                }
                val chatItem = chatsRepo.getChatByBittelID(realSource)
                chatItem?.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        val chatName = if(sentAsUserInGroup) "${contact.displayName} to Group" else contact.displayName
                        val message = "PTT From : $chatName"
                        Scopes.getMainCoroutine().launch {
                            isPttReceived.value = message
                            DataManager.getCallbacks()?.receivePTT(StardustAPIPackage(source, destination), byteArrayOf())
                        }
                    }
                }
            }
        }
    }

    private fun getModel(modelValue: Int): WavTokenizerDecoder.ModelType? {
        return WavTokenizerDecoder.ModelType.fromInt(modelValue)
    }

    private fun handleBittelAudioMessage(audioData: List<Int>?): String? {
        audioData?.let {
            val byteaArray = intArrayToByteArray(it.toMutableList())
            val byteBuffer = mCodec2Decoder.readFrame(byteaArray)
            val bDataCodec = byteBuffer.array()
            val data = arrayListOf<Byte>()
            for (byte in bDataCodec)
                data.add(byte)
            val stringBuilder = StringBuilder()
            for (element in data) {
                stringBuilder.append("${element},")
            }
            return stringBuilder.toString()
        }
        return null
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

    private fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }


    private fun getMaxFourBytes(input: Array<Int>): List<ByteArray> {
        val bytes = mutableListOf<ByteArray>()
        var byteArray = ByteArray(4)
        var numOfBytesInserted = 0
        for(audioInt in input){
            if(numOfBytesInserted == 4) {
                bytes.add(byteArray.copyOf())
                byteArray = ByteArray(4)
                numOfBytesInserted = 0
            }
            byteArray[numOfBytesInserted] = audioInt.toByte()
            numOfBytesInserted++
        }
        return bytes
    }

    private fun getMaxFourBytes(input: ByteArray): List<ByteArray> {
        val bytes = mutableListOf<ByteArray>()
        var byteArray = ByteArray(4)
        var numOfBytesInserted = 0
        if(spareBytes != null) {
            byteArray = spareBytes!!.copyOf()
            numOfBytesInserted = 2
        }
        for(audioInt in input){
            byteArray[numOfBytesInserted] = audioInt
            numOfBytesInserted++
            if(numOfBytesInserted == 4) {
                bytes.add(byteArray.copyOf())
                byteArray = ByteArray(4)
                numOfBytesInserted = 0
            }
        }
        if(numOfBytesInserted == 2) {
            spareBytes = byteArray
        } else {
            spareBytes = null
        }
        return bytes
    }

    private fun logByteArray(tagTitle: String, bDataCodec: ByteArray) {
        val stringBuilder = StringBuilder()
        for (element in bDataCodec) {
            stringBuilder.append("${element},")
        }
    }

    fun testPlayAudio(dest : String){

        val byteArray1 = byteArrayOf(-50, -10, -128, 0, -50, -10, -128, 0, -50, -10, -128, 0, -87, -80, -128, 64, 119, -9, 66, -128, 17, 53, -127, 32, 17, 126, 64, -16, 17, 26, -126, -64, 17, 25, -62, 64, 17, 14, 1, -16, 17, 64, -126, -64, 17, 127, -127, 32, 36, -1, -127, 16, 58, 30, -127, 32, -80, 49, 65, 48, 17, 51, 1, 16, 12, 36, -128, -64, 17, 127, -125, 16, 17, 26, -126, 112, 17, 127, -126, 96)
        val byteArray2 = byteArrayOf(-80, 11, -63, 16, 17, 57, 66, 16, 17, 5, 66, -96, 17, 30, -127, 16, 12, 127, -128, 48, 17, 113, -127, 32, 17, 100, 64, 48, 12, 127, -126, -96, -44, 105, -64, -64, -89, -42, 20, 0, -18, -2, 64, -48, -109, -82, 65, 0, 17, 41, 66, -112, 17, 105, -63, -112, -122, -20, 5, -48, -6, 86, 28, 0, -18, -15, -32, 0, -89, -16, -32, 0, -50, -82, -108, 16, -6, 94, -92, 0)
        val byteArray3 = byteArrayOf(58, 80, 41, -64, 52, 105, -19, -80, -35, 118, 109, -112, -13, -107, 101, -112, -105, 21, 89, -128, -61, -69, 37, -112, 58, 50, 33, -96, -27, 18, 93, -64, 58, 40, 95, 0, 58, 37, -115, -64, 58, 116, 14, -128, 58, 120, -60, 112, 58, 86, 6, -96, -87, -27, -64, -128, -87, -25, -63, 80, 17, 36, -127, 32, 17, 44, -127, 96, 17, 127, -126, 112, 17, 113, -127, 48, 17, 127, -126, -48)
        val byteArray4 = byteArrayOf(58, 120, -55, -128, -87, -35, -35, -112, 58, 44, -123, 112, 17, 73, -95, -96, 58, 61, 97, -128, 124, -88, -116, 16, 22, -42, 20, 0, -18, -68, 28, 16, 119, 86, 45, -128, -35, 44, -79, -128, -35, 44, -83, -128, -35, 14, 41, -96, -61, -84, -95, -80, 68, 14, 17, -112, -87, -113, 72, -64, 51, 126, 70, 80, 51, 108, 1, 0, 25, 108, 1, 112, 12, 26, -127, 64, 51, 1, 66, 112)
        val byteArray5 = byteArrayOf(-87, -56, -62, 0, 51, 14, 0, -80, 31, 44, -127, 32, 31, 46, -98, -80, 103, -94, 37, 16, 103, -96, 36, 0, 31, 34, 28, 16, 21, 28, -91, -48, -35, 126, 105, -128, -35, 12, 109, -128, -101, -84, -91, -128, -125, -2, 78, 64, -38, 100, 74, -32, -61, -15, -122, -64, -18, -51, 24, 0, 22, -84, -115, 16, 98, -23, -64, 16, 98, -82, -127, 16, -18, -15, 18, -112, -89, -55, -103, 48)
        val byteArray6 = byteArrayOf(-89, -110, 96, 0, -89, -88, 84, 0, -18, -36, 68, 0, -18, -71, 108, 0, 52, 44, -88, -64, -35, 44, -83, -128, -35, 14, 41, -128, 52, 44, -87, -128, -27, 16, 33, -128, -27, 39, 93, 112, 68, 120, -43, 96, 58, 79, -111, 112, 17, 126, 77, 112, -61, -77, 6, -64, -6, 25, 28, 0, -43, -20, 28, 0, -89, -88, -96, 0, -43, -84, -92, 0, 58, 57, 34, 16, 52, 89, -91, 112)
        val byteArray7 = byteArrayOf(17, 127, -103, 96, 58, 16, 89, 80, -61, -17, 29, 96, -87, -78, 21, 80, -61, -109, 86, -96, 17, 15, 93, -96, -89, -112, 32, 0, -89, -35, -36, -112, -72, 75, -36, 0, -50, -35, -32, 0, -6, 27, -100, 0, 91, 28, 69, -32, 17, 98, -127, 112, 17, 15, 64, -80, 17, 101, -62, -80, -109, -101, 0, 16, 17, 127, -126, -112, 17, 127, -126, 112, 17, 127, -126, -64, 17, 57, 65, -48)

        val byteArrayList = listOf(byteArray1, byteArray2, byteArray3, byteArray4, byteArray5, byteArray6, byteArray7)

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

    private fun testPlayPackage(byteArray: ByteArray, receiverID : String, snifferContacts: List<ChatContact>? = null){
        initAudioTrack((14080 * bufferSizeMulti).toInt())
        var bytes = splitByteArray(byteArray, 7)
        var bytesListToPlay : MutableList<ByteArray> = mutableListOf()
        for(mByte in bytes) {
            Timber.tag("decodedBytes").d("decodedBytes : ${byteArray.size}")
            var decodedBytes = handleBittelAudioMessage(mByte)
            if(!mByte.contentEquals(embpyByte)){
                bytesListToPlay.add(decodedBytes)

            }
        }
        mRegisterUser?.appId?.let { playAudio(context, combine(bytesListToPlay), receiverID, it, snifferContacts) }
    }



    private fun testPlayPackage(byteArray: ByteArray, dest : String){
        initAudioTrack(640)
        var bytes = splitByteArray(byteArray, 7)
        var bytesListToPlay : MutableList<ByteArray> = mutableListOf()
        for(mByte in bytes) {
//            logByteArray("logByteArrayInputPlayer", mByte)
            var decodedBytes = handleBittelAudioMessage(mByte)
            if(!mByte.contentEquals(embpyByte)){
                bytesListToPlay.add(decodedBytes)
            }
        }
        SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
            playAudio(DataManager.context, combine(bytesListToPlay),dest,it, null)
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

    private fun breakByteArray(byteArray: ByteArray) : List<ByteArray>{
        var mutableListByteArray = mutableListOf<ByteArray>()
        val byteArrayToBreak = byteArray.copyOf()
        var tempByte : Byte? = null
        var loopIndex = 0
        var byteArrayToAdd = ByteArray(4)
        for (byte in byteArrayToBreak) {
            if(loopIndex<3){
                if(tempByte!=null){
                    byteArrayToAdd[loopIndex] = getBytesShiftCombine(tempByte, byte)
                    tempByte = byte
                    loopIndex++
                    if(loopIndex == 3){
                        val mTempByte = (byte.toUByte().toInt() shl  4) and (0xFF)
                        byteArrayToAdd[loopIndex] = mTempByte.toByte()
                        tempByte = null
                        mutableListByteArray.add(byteArrayToAdd)
                        byteArrayToAdd = ByteArray(4)
                        loopIndex = 0
//                        Timber.tag("TestMeKot").d("add Short")
                    }
                }else {
                    byteArrayToAdd[loopIndex] = byte
                    loopIndex++
                }
            }else {
                if(tempByte == null){
                    byteArrayToAdd[loopIndex] = byte and (0xF0.toByte())
                    tempByte = byte
                }
                mutableListByteArray.add(byteArrayToAdd)
//                Timber.tag("TestMeKot").d("add Long")
                byteArrayToAdd = ByteArray(4)
                loopIndex = 0
            }
        }
        return mutableListByteArray
    }

    fun getBytesShiftCombine(byte1 : Byte , byte2 : Byte) : Byte {
        val byteShift1 = byte1.toUByte().toInt() shl 4
        val byteShift2 = byte2.toUByte().toInt() shr 4
        return byteShift1.toByte() or byteShift2.toByte()
    }

    fun updateAudioReceived(chatId: String, realSourceId: String, isAudioReceived : Boolean){
        if(!DataManager.getSavePTTFilesRequired(context) || chatId.isEmpty()) {
            return
        }
        Scopes.getDefaultCoroutine().launch {
            chatsRepository.updateAudioReceived(chatId, isAudioReceived)
            val chatItem = chatsRepository.getChatByBittelID(chatId)
            chatItem?.let {
                chatItem.message = Message(senderID = realSourceId, text = "Ptt Received", seen = true)
                chatsRepository.addChat(it)
            }
        }
    }

    fun createMediaPlayer (context: Context, audioResource : Int) {
        if(mediaPlayer == null) {
            mediaPlayer = SafePlayer(context)
        }
    }
    @OptIn(UnstableApi::class)
    fun playClickSound (context: Context, audioResource : Int, onFinished : () -> Unit = {}){
        createMediaPlayer(context, audioResource)
        var isSent = false
        mediaPlayer?.onCompletion = {
            Log.d("playClickSound", "setOnCompletionListener")
            if(!isSent) {
                onFinished()  // Call the callback when playback finishes
            }
            isSent = true
        }
        mediaPlayer?.onError = { what, extra ->
            Log.e("TEST", "Audio error: what=$what extra=$extra")
            Log.d("playClickSound", "Audio error: what=$what extra=$extra")
            if(!isSent) {
                onFinished()  // Call the callback when playback finishes
            }
            isSent = true
        }



        try {
            mediaPlayer?.play(audioResource)
        } catch (e: Exception) {
            Log.d("playClickSound", "error ${e.printStackTrace()}")
            println("MediaPlayer start failed: ${e.message}")
            if(!isSent) {
                onFinished()  // Call the callback when playback finishes
            }
            isSent = true
        }
    }
    class SafePlayer(private val ctx: Context) :
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

        private var mp: MediaPlayer? = null

        var onCompletion: (() -> Unit)? = null
        var onError: ((what: Int, extra: Int) -> Unit)? = null

        fun play(@RawRes resId: Int) {
            stopAndRelease()
            mp = MediaPlayer.create(ctx, resId)?.apply {
                setVolume(0.05f,0.05f)
                setOnCompletionListener(this@SafePlayer)
                setOnErrorListener(this@SafePlayer)
                start()
            }
        }

        override fun onCompletion(player: MediaPlayer) {
            onCompletion?.invoke() // ✅ notify outside
            stopAndRelease()
        }

        override fun onError(player: MediaPlayer, what: Int, extra: Int): Boolean {
            onError?.invoke(what, extra) // ✅ notify outside
            try { player.reset() } catch (_: Throwable) {}
            stopAndRelease()
            return true // we handled it
        }

        fun stopAndRelease() {
            mp?.run {
                try { stop() } catch (_: Throwable) {}
                try { reset() } catch (_: Throwable) {}
                try { release() } catch (_: Throwable) {}
            }
            mp = null
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
}

fun String.stringToByteArray(): ByteArray {
    var values = listOf<Int>()
    try {
        values = this.split(", ", ",").map { it.toInt() }
    }catch (e : Exception) {
        e.printStackTrace()
    }

    val byteArray = ByteArray(values.size) { values[it].toByte() }
    return byteArray
}