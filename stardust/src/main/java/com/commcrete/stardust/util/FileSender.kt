package com.commcrete.stardust.util


import android.os.Handler
import android.os.Looper
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.StardustConfigurationParser
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.CarriersUtils.getRadioToSend
import com.commcrete.stardust.util.FileUtils.FileType
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import timber.log.Timber
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

class FileSender(val data: FileUtils.FileTransferData.Send) {

    // Simple vars suffice — these are private and never observed externally
    private var isSendingInProgress = false
    private var sendingPercentage = 0
    private var isComplete = false
    private val mutablePackagesMap: MutableMap<Float, StardustFilePackage> = mutableMapOf()
    private var current = 0f
    private var sendInterval: Long = 900
    private var packagesSent = 0
    private var onFileStatusChange: OnFileStatusChange? = null
    private val handler: Handler = Handler(Looper.getMainLooper())
    var randomMisses: MutableSet<Int> = mutableSetOf()

    private val runnable: Runnable = Runnable {
        mutablePackagesMap[current]?.let { sendPackage(it) }
        current += 1f
        resetSendTimer()
        updateStep(mutablePackagesMap.size)
    }

    /**
     * Kicks off the file send. The returned [Deferred] resolves to `true`
     * after [saveLocalMessages] has finished persisting the local
     * [MessageEntity], `false` if persistence failed. Callers that don't
     * care about the local-save outcome can simply ignore the returned value.
     */
    fun sendFile(onFileStatusChange: OnFileStatusChange): Deferred<Boolean> {
        this.onFileStatusChange = onFileStatusChange
        isSendingInProgress = true
        val fileList = listOf(data.file)
        val numOfPackages = calculateNumOfPackages(fileList, data.stardustAPIPackage.spare)
        this.onFileStatusChange?.startSending(data)
        return CoroutineScope(Dispatchers.IO).async {
            var packages = createPackages(fileList)

            if (data.stardustAPIPackage.spare > 0) {
                val dataWithSpare = createSparePackages(packages, data.stardustAPIPackage.spare)
                packages = dataWithSpare.first
                createStartPackage(totalPackages = numOfPackages, spareData = dataWithSpare.second)
            } else {
                createStartPackage(totalPackages = numOfPackages, spareData = 0)
            }
            getRandomMisses(data.stardustAPIPackage.spare, numOfPackages)
            mutablePackagesMap.clear()
            mutablePackagesMap.putAll(packages)
            resetSendTimer()
            saveLocalMessages()
        }
    }

    fun stopSendingPackages() {
        removeSendTimer()
        isSendingInProgress = false
        sendingPercentage = 0
        current = 0f
        packagesSent = 0
        isComplete = false
        onFileStatusChange?.stopSending(data)
        sendInterval = 900
    }

    private fun updateStep(numOfPackages: Int) {
        sendingPercentage = ((current.toDouble() / numOfPackages) * 100).toInt()
        onFileStatusChange?.updateStep(data, sendingPercentage)
        if (sendingPercentage >= 100) { finishSending() }
    }

    private fun getRandomMisses(spare: Int, numOfPackages: Int): List<Int> {
        randomMisses = (0 until numOfPackages).shuffled().take(spare).toMutableSet()
        Timber.d("randomMisses: $randomMisses")
        return randomMisses.toList()
    }

    /**
     * Persist the locally-stored copy of the file + the corresponding
     * [MessageEntity]. Returns `true` once the message entity has been
     * written successfully, `false` if persistence threw.
     */
    private suspend fun saveLocalMessages(): Boolean {
        val destDir = File("${DataManager.appContext.filesDir}/${data.chatId}/files").also { it.mkdirs() }
        val destFile = File(destDir, data.file.name)

        // Try to copy the source into the chat-local directory. We do this in a
        // dedicated try/catch so that a failed copy does NOT prevent us from
        // persisting the MessageEntity — the message must still be saved even
        // if the file vanished or is on a path we cannot read directly.
        val copyOk = copySourceToLocal(destFile)

        return try {
            DataManager.getAppRepo().saveMessage(
                MessageEntity(
                    chatId = data.chatId,
                    senderID = data.stardustAPIPackage.senderId,
                    receiverID = data.stardustAPIPackage.receiverId,
                    state = MessageState.SENT,
                    extraData = MessageExtraData.Attachment(
                        title = data.file.name,
                        // Fall back to the original path if the local copy
                        // failed; UI can still try to open it directly.
                        path = if (copyOk) destFile.absolutePath else data.file.absolutePath,
                        subtype = data.fileType.toAttachmentType()
                    )
                ))
            true
        } catch (e: Exception) {
            Timber.e(e, "Error persisting MessageEntity for ${data.file.name}")
            false
        }
    }

    /**
     * Copy [data.file] into [destFile] for the given [data.fileType].
     *
     * `data.file` is a [java.io.File] but on Android the underlying path may
     * not actually be readable via [java.io.FileInputStream]:
     *  - URI-derived paths from SAF / MediaStore (`/document/image:1234`)
     *  - cache files reaped between picker and send
     *  - paths in another app's private storage
     *
     * We try, in order:
     *   1. Direct file stream (works for normal app-private / public files).
     *   2. ContentResolver via `Uri.fromFile(...)` (works for some paths).
     *
     * @return true on success, false if the source could not be opened — in
     *         which case we log a diagnostic and let the caller decide.
     */
    private fun copySourceToLocal(destFile: File): Boolean {
        val src = data.file
        return try {
            when (data.fileType) {
                FileType.Image -> {
                    openSourceStream(src)?.use { input ->
                        destFile.outputStream().use { input.copyTo(it) }
                    } ?: run {
                        logUnreadableSource(src)
                        return false
                    }
                }
                FileType.File -> {
                    if (!src.exists() || !src.canRead()) {
                        logUnreadableSource(src)
                        return false
                    }
                    decompressTextFile(src, destFile)
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Error copying source file locally: ${src.absolutePath}")
            false
        }
    }

    /** Returns an [java.io.InputStream] for [src] or null if unreadable. */
    private fun openSourceStream(src: File): java.io.InputStream? {
        // Path 1: real filesystem file.
        if (src.exists() && src.canRead()) {
            return runCatching { src.inputStream() }.getOrNull()
        }
        // Path 2: try ContentResolver in case the File is a thin wrapper around
        // a content-URI-derived path. android.net.Uri.fromFile expects a real
        // path so this only helps when the path *is* a valid file but read
        // permissions are odd.
        return runCatching {
            DataManager.appContext.contentResolver.openInputStream(android.net.Uri.fromFile(src))
        }.getOrNull()
    }

    private fun logUnreadableSource(src: File) {
        Timber.w(
            "Source file unreadable: path=%s exists=%s canRead=%s length=%d",
            src.absolutePath, src.exists(), src.canRead(),
            runCatching { src.length() }.getOrDefault(-1L)
        )
    }

    private fun first50BytesUtf8(input: String): String {
        val utf8 = input.toByteArray(Charsets.UTF_8)
        if (utf8.size <= 50) return input

        // Take only the first 50 bytes
        val cut = utf8.copyOf(50)

        // Now we need to avoid splitting a multi-byte char.
        // We trim invalid trailing bytes.
        var end = cut.size
        while (end > 0) {
            val tryString = try {
                String(cut.copyOf(end), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            if (tryString != null) {
                return tryString
            }
            end--
        }

        return "" // fallback, should never happen
    }

    private fun createStartPackage(totalPackages: Int, spareData: Int) {
        val fileName = first50BytesUtf8(data.file.nameWithoutExtension)
        val fileEnding = data.file.extension

        val fileStart = StardustFileStartPackage(type = data.fileType.bitCode, total = totalPackages, data.stardustAPIPackage.spare, spareData, fileEnding, fileName)

        val radio = getRadioToSend(functionalityType = data.fileType.relatedFunctionalityType(), carrier = data.stardustAPIPackage.carrier)  ?: return

        DataManager.getClientConnection().let {
            val (appId, _) = requireLocalSrcDst() ?: return

            val sosString = "STR"
            val sosBytes = sosString.toByteArray()
            var dataToSend : Array<Int> = arrayOf()
            dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes).size + fileStart.toArrayInt().size)
            dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(sosBytes))
            dataToSend = dataToSend.plus(fileStart.toArrayInt())

            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                source = appId,
                destination = data.stardustAPIPackage.receiverId,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                data = dataToSend)
            fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
            it.addMessageToQueue(fileStartMessage)
        }
    }

    private fun createSparePackages(
        packages: Map<Float, StardustFilePackage>,
        spare: Int
    ): Pair<Map<Float, StardustFilePackage>, Int> {
        val reed = ReedSolomon(totalDataPackets = packages.size, totalParityPackets = spare)
        val dataList = packages.map { it.value.data }.toMutableList()

        var paddingAdded = 0
        if (dataList.isNotEmpty()) {
            val lastIndex = dataList.lastIndex
            val originalSize = dataList[lastIndex].size
            if (originalSize < FILE_CHUNK_SIZE) {
                paddingAdded = FILE_CHUNK_SIZE - originalSize
                dataList[lastIndex] = dataList[lastIndex].copyOf(FILE_CHUNK_SIZE)
            }
        }

        val newArray = reed.encode(dataList)
        val fileList = newArray.withIndex().associate { (index, data) ->
            index.toFloat() to StardustFilePackage(
                current = index,
                data = data,
                isLast = index == newArray.lastIndex
            )
        }

        return fileList to paddingAdded
    }

    private fun createPackages(files: List<File>): Map<Float, StardustFilePackage> {
        var packageIndex = 0
        val bittelFileList = mutableMapOf<Float, StardustFilePackage>()

        for ((fileIndex, file) in files.withIndex()) {
            val fileBytes = file.readBytes()
            Timber.tag("FileUpload").d("fileBytes: ${fileBytes.size}")

            var offset = 0
            while (offset < fileBytes.size) {
                val chunk = fileBytes.copyOfRange(offset, minOf(offset + FILE_CHUNK_SIZE, fileBytes.size))
                val isLast = (offset + FILE_CHUNK_SIZE >= fileBytes.size) && (fileIndex == files.lastIndex)

                bittelFileList[packageIndex.toFloat()] = StardustFilePackage(
                    current = packageIndex,
                    data = chunk,
                    isLast = isLast
                )
                Timber.tag("FileUpload").d("chunk: ${chunk.size}, isLast: $isLast")

                offset += FILE_CHUNK_SIZE
                packageIndex++
            }
        }

        Timber.tag("FileUpload").d("Total packages created: ${bittelFileList.size}")
        return bittelFileList
    }

    private fun sendPackage(stardustFilePackage: StardustFilePackage) {
        DataManager.getClientConnection().let {
            val functionalityType = when (data.fileType) {
                FileType.File -> FunctionalityType.FILE
                else -> FunctionalityType.IMAGE
            }
            val radio = getRadioToSend(
                functionalityType = functionalityType,
                carrier = data.stardustAPIPackage.carrier
            ) ?: return
            sendInterval = if (radio.first.type == StardustConfigurationParser.StardustTypeFunctionality.ST) 300L else 900L
            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                source = data.stardustAPIPackage.senderId,
                destination = data.stardustAPIPackage.receiverId,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                data = stardustFilePackage.toArrayInt()
            )
            fileStartMessage.stardustControlByte.stardustDeliveryType = radio.second
            if (stardustFilePackage.isLast) {
                fileStartMessage.stardustControlByte.stardustPartType = StardustControlByte.StardustPartType.LAST
            }
            packagesSent++
            Timber.tag("FileUpload").d("send: $packagesSent")
            it.addMessageToQueue(fileStartMessage)
        }
    }

    private fun resetSendTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, sendInterval)
    }

    private fun removeSendTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
    }

    private fun finishSending() {
        isSendingInProgress = false
        sendingPercentage = 0
        removeSendTimer()
        current = 0f
        packagesSent = 0
        isComplete = true
        // Reuse existing handler — do NOT create a new Handler instance here
        handler.postDelayed({ isComplete = false }, 3000)
        onFileStatusChange?.finishSending(data)
        sendInterval = 900
    }

    interface OnFileStatusChange {
        fun startSending(data: FileUtils.FileTransferData.Send) {}
        fun finishSending(data: FileUtils.FileTransferData.Send) {}
        fun stopSending(data: FileUtils.FileTransferData.Send) {}
        fun updateStep(data: FileUtils.FileTransferData.Send, percentage: Int) {}
    }

    companion object {

        private const val FILE_CHUNK_SIZE = 60

        fun calculateNumOfPackages(files: List<File>, spare: Int): Int {
            return files.sumOf { ceil(it.length().toDouble() / FILE_CHUNK_SIZE).toInt() } + spare
        }

        fun calculateSendTime(numOfPackages: Int, functionalityType: FunctionalityType): String {
            val radio: Pair<Carrier?, StardustControlByte.StardustDeliveryType?>? =
                getRadioToSend(null, functionalityType)

            val totalTime =
                if (radio?.first != null &&
                    radio.first?.component2() == StardustConfigurationParser.StardustTypeFunctionality.ST
                ) 0.3 else 1.3

            val totalSeconds = numOfPackages * totalTime // Total time in seconds as a Double
            val minutes = totalSeconds.toInt() / 60 // Whole minutes
            val seconds = totalSeconds % 60 // Remaining seconds

            return if (minutes > 0) {
                String.format(
                    "%d minute%s and %.1f second%s",
                    minutes,
                    if (minutes > 1) "s" else "",
                    seconds,
                    if (seconds > 1.0) "s" else ""
                )
            } else {
                String.format("%.1f second%s", totalSeconds, if (totalSeconds > 1.0) "s" else "")
            }
        }

        fun calculateAddedPackages (numOfPackages: Int) : Int{
            val factor = SharedPreferencesUtil.getResilience()
            return packageNumToAdd(numOfPackages, factor.value)
        }

        private fun packageNumToAdd(packageNum: Int, factor: Int): Int {
            require(factor in listOf(20, 60, 120)) { "Factor must be one of: 20, 60, or 120" }
            require(packageNum > 0) { "packageNum must be > 0" }

            // Step 1: raw percentage
            var percent = 10 + factor / sqrt(packageNum.toDouble())

            // Step 2: clamp between 5% and 100%
            percent = percent.coerceIn(5.0, 100.0)

            // Step 3: calculate packages to add
            var toAdd = ceil(packageNum * (percent / 100)).toInt()

            // Step 4: enforce minimum 2 packages
            toAdd = maxOf(2, toAdd)

            return toAdd
        }
    }
}

enum class Resilience (val value : Int) {
    Low (20),
    Medium (60),
    High (120),
}
