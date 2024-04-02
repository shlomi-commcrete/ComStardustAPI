package com.commcrete.stardust.util.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.RingtoneManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.room.chats.ChatsDatabase
import com.commcrete.stardust.room.chats.ChatsRepository
import com.commcrete.stardust.room.messages.MessageItem
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.util.UsersUtils
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import kotlinx.coroutines.*
import java.io.*
import java.util.Date
import kotlin.experimental.and
import kotlin.experimental.or


object PlayerUtils : BleMediaConnector() {

    val embpyByte : ByteArray = byteArrayOf(0,0,0,0)

    private var track: AudioTrack? = null
    const val sampleRate = 8000
    private var spareBytes : ByteArray? = null
    private val messagesRepository = MessagesRepository(MessagesDatabase.getDatabase(DataManager.context).messagesDao())
    private val chatsRepository = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())

    private val handler : Handler = Handler(Looper.getMainLooper())
    val isPttReceived : MutableLiveData<String> = MutableLiveData("empty")
    var ts = ""
    var isPlaying = false
    var numOfPackagesRecieved = 0
    private val runnable : Runnable = Runnable {
        Scopes.getMainCoroutine().launch {
            updateAudioReceived(destination, false)
            destination = ""
            ts = ""
            fileToWrite = null
            StardustPackageUtils.packageLiveData.value = null
//            mCodec2Decoder.rawAudioOutBytesBuffer.clear()

            track?.flush()
            track?.release()
            enhancer?.release()
            equalizer?.release()
            track = null
            isPlaying = false
            numOfPackagesRecieved = 0
            isPttReceived.value = "empty"
        }
    }

//    var mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)


    val destinationLiveData : MutableLiveData<String> = MutableLiveData()
    var destination : String = ""
    var fileToWrite : File? = null



    var enhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    val gainIncrease = 3000  // This value is in millibels (mB). 2000 mB equals a 200% gain increase.

    private fun playAudio(context: Context, pttAudio: ByteArray, destinations: String, source: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            resetTimer()
            setTs()
            val file: File? = initPttInputFile(context, destinations, source)
            file?.let { writePTTReceivedData(pttAudio, it) }
            playPTT(pttAudio, pttAudio.size )
        }
    }


    private fun playPTT(audioStream: ByteArray, size: Int) {
//        if(App.isAppInForeground || SharedPreferencesUtil.getEnablePttSound(DataManager.context)){
            track?.let { playStream(it, audioStream, size) }
//        }
    }

    private fun syncBleDevice () {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager)
        bleDevice?.let {
            track?.setPreferredDevice(it)
            audioManager.startBluetoothSco()
        }
    }

    private fun removeSyncBleDevices () {
        val audioManager = DataManager.context.getSystemService(AudioManager::class.java)
        val bleDevice = getPreferredDevice(audioManager)
        bleDevice?.let {
            audioManager.stopBluetoothSco()
        }
    }

    private fun playStream(audioTrack: AudioTrack , audioData: ByteArray, bufferSizeInBytes: Int) {
        numOfPackagesRecieved++
//        App.getMainCoroutine().launch {
            try {
                val audioManager = DataManager.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
//                syncBleDevice ()
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        // Handle focus change (e.g., pause your audio when losing focus)
                    }
                    .build()

                val focusResult = audioManager.requestAudioFocus(focusRequest)
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    // Start playback
                    track?.notificationMarkerPosition = bufferSizeInBytes/2
                    val value = 1*40
                    val size = 1/4*320 // Specify the desired size of your byte array
                    val byteArray = ByteArray(size) { 0.toByte() }
                    Scopes.getMainCoroutine().launch{
                        if (audioData.size >= value) {
                            track?.write(audioData, 0, bufferSizeInBytes)
                            track?.write(byteArray, 0, byteArray.size)
                        }
                    }
                    Scopes.getDefaultCoroutine().launch{
                        if(!isPlaying) {
                            track?.play()
                        }
                        isPlaying = true
                    }


//                    track?.write(audioData, 0, bufferSizeInBytes)
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

    private fun initPttInputFile(context: Context, destinations: String, source: String) : File? {
        val destination = destinations.trim().replace("[\"", "").replace("\"]", "")
        //Todo stuck?
        this.destination = destinations
        updateAudioReceived(destination, true)
        val directory = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/$destination")
        val file = if(fileToWrite !=null) fileToWrite else File("${context.filesDir}/$destination/$ts-$source.pcm")
        if(directory!=null){
            if(!directory.exists()){
                directory.mkdir()
            }
            if (file != null) {
                if(!file.exists()){
                    file.createNewFile()
                    fileToWrite = file
                    Scopes.getDefaultCoroutine().launch {
                        val userName = UsersUtils.getUserName(destination)
                        try {
                        }catch (e :Exception){
                            e.printStackTrace()
                        }
                        messagesRepository.savePttMessage(
                            MessageItem(senderID = destination,
                            epochTimeMs = ts.toLong(), senderName = userName ,
                            chatId = destination, text = "", fileLocation = file.absolutePath,
                            isAudio = true)
                        )
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
    private fun initAudioTrack(bufferSizeInBytes: Int) {

        try {
            if(track == null){
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(bufferSizeInBytes).build()
            }

            track?.bufferSizeInFrames = 640
            track?.setVolume(2.0f)
            track?.audioSessionId?.let {
                Equalizer().getEq(it, DataManager.context)
            }
        }catch (e : Exception){
            e.printStackTrace()
//            return null
        }
//        return track
    }

    private fun resetTimer(){
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, 1500)
    }

    private fun setTs(){
        if(ts.isEmpty()){
            ts = (System.currentTimeMillis()).toString()
        }
    }

    fun saveBittelMessageToDatabase(bittelPackage: StardustPackage){
        Scopes.getDefaultCoroutine().launch {
            if(bittelPackage.getSourceAsString().isNotEmpty()){
                val chatsRepo = ChatsRepository(ChatsDatabase.getDatabase(DataManager.context).chatsDao())
                var from = bittelPackage.getSourceAsString()
                if(from == "00000002") {
                    from = bittelPackage.getDestAsString()
                }
                val chatItem = chatsRepo.getChatByBittelID(from)
                chatItem?.let { chat ->
                    val chatContact = chat.user
                    chatContact?.let { contact ->
                        val chatName = if(bittelPackage.getSourceAsString() != "00000002") contact.displayName else "${contact.displayName} to Group"
                        val message = "PTT From : $chatName"
                        Scopes.getMainCoroutine().launch {
                            isPttReceived.value = message
                        }
                        contact.appId?.let { appIdArray ->
                            if(appIdArray.isNotEmpty()){
                                getPackageByFrames(bittelPackage, appIdArray[0])
                            }
                        }

                    }
                }
            }
        }
    }

    private fun handleBittelAudioMessage(audioData: List<Int>?): String? {
        audioData?.let {
            val byteaArray = intArrayToByteArray(it.toMutableList())
//            val byteBuffer = mCodec2Decoder.readFrame(byteaArray)
//            val bDataCodec = byteBuffer.array()
            val data = arrayListOf<Byte>()
//            for (byte in bDataCodec)
//                data.add(byte)
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
//            val byteBuffer = mCodec2Decoder.readFrame(byteArray)
//            val bDataCodec = byteBuffer.array()
//            logByteArray("logByteArrayOutputPlayer", bDataCodec)
            val data = arrayListOf<Byte>()
//            for (byte in bDataCodec) data.add(byte)
            return data.toByteArray()
        }catch (e : Exception) {
            e.printStackTrace()
//            mCodec2Decoder.destroy()
//            mCodec2Decoder.rawAudioOutBytesBuffer.clear()
//            mCodec2Decoder = Codec2Decoder(RecorderUtils.CodecValues.MODE700.mode)
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

    private fun getPackageByFrames(bittelPackage: StardustPackage, chatUserId: String?){
        bittelPackage.data?.let { dataArray -> //dataArray = Array<Int>
            chatUserId?.let {
                val byteArray = intArrayToByteArray(dataArray.toMutableList())
                testPlayPackage(byteArray,it)
            }
        }
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

    private fun testPlayPackage(byteArray: ByteArray, dest : String){
        initAudioTrack(640)
        var bytes = breakByteArray(byteArray)
        var bytesListToPlay : MutableList<ByteArray> = mutableListOf()
        for(mByte in bytes) {
//            logByteArray("logByteArrayInputPlayer", mByte)
            var decodedBytes = handleBittelAudioMessage(mByte)
            if(!mByte.contentEquals(embpyByte)){
                bytesListToPlay.add(decodedBytes)
            }
        }
        SharedPreferencesUtil.getAppUser(DataManager.context)?.appId?.let {
            playAudio(DataManager.context, combine(bytesListToPlay),dest,it)
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

    fun updateAudioReceived(chatId: String, isAudioReceived : Boolean){
        Scopes.getDefaultCoroutine().launch {
            chatsRepository.updateAudioReceived(chatId, isAudioReceived)
        }
    }
    fun playClickSound (context: Context, audioResource : Int){

        val player = ExoPlayer.Builder(DataManager.context).build()

        // Create a MediaItem from the raw resource URI
        val rawResourceUri = RawResourceDataSource.buildRawResourceUri(audioResource)
        val mediaItem = MediaItem.fromUri(rawResourceUri)

        // Prepare the player with the media item
        player.setMediaSource(DefaultMediaSourceFactory(DataManager.context).createMediaSource(mediaItem))
        player.prepare()

        // Start playback
        player.playWhenReady = true
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