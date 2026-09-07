package com.commcrete.stardust.util


import android.os.Handler
import android.os.Looper
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.enums.FunctionalityType
import com.commcrete.stardust.room.new_db.message.FileTransferCancellation
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireLocalSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.config.CarrierType
import com.commcrete.stardust.stardust.model.StardustControlByte
import com.commcrete.stardust.util.CarriersUtils.getRadioToSend
import com.commcrete.stardust.util.FileUtils.FileType
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt
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

    /**
     * Row id of the local copy of this message, persisted by [saveLocalMessages] before
     * the first package goes out so that any later failure has a row to land on. Null
     * only if that write itself failed.
     */
    @Volatile private var messageId: Long? = null

    /** Packages that had no radio to go out on. Compared against the parity budget in [finishSending]. */
    private var packagesDropped = 0

    /**
     * Positions in [mutablePackagesMap] that carry Reed-Solomon parity rather than file
     * data, as reported by the encoder. Empty when the transfer carries no parity.
     *
     * Deliberately taken from the encoder instead of assumed to be the tail: a codeword
     * longer than 255 packages is split into blocks and each block's parity follows that
     * block's data, so the parity packages are interleaved.
     */
    private var parityIndices: Set<Int> = emptySet()

    /** Data / parity packages actually queued to the radio so far. */
    private var dataPackagesSent = 0
    private var sparePackagesSent = 0

    /**
     * Claimed by whichever terminal outcome comes first, so a send is reported — and
     * written — as failed or finished exactly once. A disconnect arriving right after
     * the last package must not fail a transfer that completed.
     */
    private val terminalOutcomeClaimed = AtomicBoolean(false)

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
            // Persist the row FIRST: everything below can fail, and a failure needs a
            // row to be recorded on.
            val saved = saveLocalMessages()

            val started = try {
                var packages = createPackages(fileList)

                val startSent = if (data.stardustAPIPackage.spare > 0) {
                    val dataWithSpare = createSparePackages(packages, data.stardustAPIPackage.spare)
                    packages = dataWithSpare.first
                    createStartPackage(totalPackages = numOfPackages, spareData = dataWithSpare.second)
                } else {
                    createStartPackage(totalPackages = numOfPackages, spareData = 0)
                }

                if (startSent) {
                    getRandomMisses(data.stardustAPIPackage.spare, numOfPackages)
                    mutablePackagesMap.clear()
                    mutablePackagesMap.putAll(packages)
                    resetSendTimer()
                }
                startSent
            } catch (e: Exception) {
                Timber.e(e, "Error preparing file send for ${data.file.name}")
                false
            }

            // No start package means the receiver never learns a transfer is coming, so
            // no package that follows could be assembled into a file — fail it here
            // rather than let the timer march through packages nothing will accept.
            if (!started) failTransfer(FileReceiver.FileFailure.ERROR)

            saved
        }
    }

    /**
     * User-requested cancel — NOT a failure. The row is written as
     * [MessageState.CANCELLED] with no failure reason, carrying instead how far the send
     * had got: whether the parity tail had started going out decides whether the
     * receiver can still end up with the file.
     *
     * Claiming the terminal outcome is also what keeps a disconnect arriving after the
     * cancel from rewriting the row as a failed transfer — and what makes cancelling a
     * send that just finished a no-op on the row.
     */
    fun stopSendingPackages() {
        val claimed = terminalOutcomeClaimed.compareAndSet(false, true)
        // Snapshot before the counters are reset below.
        val cancellation = FileTransferCancellation.of(
            dataPackagesSent = dataPackagesSent,
            sparePackagesSent = sparePackagesSent,
            dataPackages = mutablePackagesMap.size - parityIndices.size,
            sparePackages = parityIndices.size,
        )

        removeSendTimer()
        isSendingInProgress = false
        sendingPercentage = 0
        current = 0f
        packagesSent = 0
        dataPackagesSent = 0
        sparePackagesSent = 0
        isComplete = false
        sendInterval = 900

        // stopSending() stays the immediate "it stopped now" signal, raised before the
        // write like it always was.
        onFileStatusChange?.stopSending(data)

        if (!claimed) {
            // Already finished or already failed — the cancel came too late to be the
            // outcome, so the row keeps what it has.
            Timber.tag("FileUpload").d("cancel ignored: ${data.file.name} had already settled")
            return
        }

        Timber.tag("FileUpload").w(
            "send cancelled: %s phase=%s data=%d/%d spare=%d/%d msgId=%s",
            data.file.name,
            cancellation.phase,
            cancellation.dataPackagesSent, cancellation.dataPackages,
            cancellation.sparePackagesSent, cancellation.sparePackages,
            messageId,
        )

        val id = messageId
        if (id == null) {
            Timber.w("Send cancelled with no local message row to record it on")
            onFileStatusChange?.cancelledSending(data, cancellation)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DataManager.getAppRepo().markFileSendCancelled(id, cancellation)
            } catch (e: Exception) {
                Timber.e(e, "Error recording send cancellation on message $id")
            }
            // After the write, for the same reason as failedSending.
            onFileStatusChange?.cancelledSending(data, cancellation)
        }
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
     *
     * Also remembers the new row's id in [messageId] — that is what a later failure is
     * recorded on, so this must run before the first package goes out.
     */
    private suspend fun saveLocalMessages(): Boolean {
        val destDir = File("${DataManager.appContext.filesDir}/${data.chatId}/files").also { it.mkdirs() }
        val destFile = File(destDir, data.file.name)

        // Try to copy the source into the chat-local directory. We do this in a
        // dedicated try/catch so that a failed copy does NOT prevent us from
        // persisting the MessageEntity — the message must still be saved even
        // if the file vanished or is on a path we cannot read directly.
        val copyOk = copySourceToLocal(destFile)

        val localFile = if (copyOk) destFile else data.file
        val subtype = data.fileType.toAttachmentType()
        return try {
            messageId = DataManager.getAppRepo().saveMessage(
                MessageEntity(
                    chatId = data.chatId,
                    senderID = data.stardustAPIPackage.senderId,
                    receiverID = data.stardustAPIPackage.receiverId,
                    state = MessageState.SENT,
                    extraData = MessageExtraData.Attachment(
                        title = data.file.name,
                        // Fall back to the original path if the local copy
                        // failed; UI can still try to open it directly.
                        path = localFile.absolutePath,
                        subtype = subtype,
                        // Parse the contact CSV once here so the conversation UI
                        // renders from the summary without re-reading the file.
                        fileSummary = FileUtils.buildFileSummary(localFile, subtype),
                    )
                ))
            messageId != null
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
                FileType.File, FileType.Contact -> {
                    // Both are gzip-compressed text payloads (a contact is a CSV),
                    // so decompress the source into the local copy.
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

    /**
     * Queues the transfer's opening package, which is what tells the receiver a file is
     * coming and how many packages it will be. Returns false when there is no radio to
     * send on or no local address yet — in both cases nothing was queued and the
     * transfer cannot happen at all.
     */
    private fun createStartPackage(totalPackages: Int, spareData: Int): Boolean {
        val fileName = first50BytesUtf8(data.file.nameWithoutExtension)
        val fileEnding = data.file.extension

        val fileStart = StardustFileStartPackage(type = data.fileType.bitCode, total = totalPackages, data.stardustAPIPackage.spare, spareData, fileEnding, fileName)

        val radio = getRadioToSend(functionalityType = data.fileType.relatedFunctionalityType(), carrier = data.stardustAPIPackage.carrier)
            ?: return false

        DataManager.getClientConnection().let {
            val (appId, _) = requireLocalSrcDst() ?: return false


            val bytes = "STR".toByteArray()
            var dataToSend : Array<Int> = arrayOf()
            dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(bytes).size + fileStart.toArrayInt().size)
            dataToSend = dataToSend.plus(StardustPackageUtils.byteArrayToIntArray(bytes))
            dataToSend = dataToSend.plus(fileStart.toArrayInt())

            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                source = appId,
                destination = data.stardustAPIPackage.receiverId,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                data = dataToSend)
            fileStartMessage.stardustControlByte.stardustDeliveryType = radio.deliveryType
            it.addMessageToQueue(fileStartMessage)
        }
        return true
    }

    private fun createSparePackages(
        packages: Map<Float, StardustFilePackage>,
        spare: Int
    ): Pair<Map<Float, StardustFilePackage>, Int> {
        val reed = ReedSolomon(totalDataPackets = packages.size, totalParityPackets = spare)
        // Which of the encoded packages carry parity — asked of the encoder, since with
        // more than one block they are not the tail. Used to tell a cancel that landed
        // before any parity went out from one that landed while it was going out.
        parityIndices = reed.parityIndices()
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
            val functionalityType = data.fileType.relatedFunctionalityType()
            val radio = getRadioToSend(
                functionalityType = functionalityType,
                carrier = data.stardustAPIPackage.carrier
            ) ?: run {
                // No radio for this package: it is simply never transmitted. The timer
                // keeps walking, so count the loss and let finishSending() decide
                // whether the parity packages can still cover it.
                packagesDropped++
                Timber.tag("FileUpload").w("no radio to send on — dropped package (total dropped: $packagesDropped)")
                return
            }
            sendInterval = if (radio.type == CarrierType.ST) 300L else 900L
            val fileStartMessage = StardustPackageUtils.getStardustPackage(
                source = data.stardustAPIPackage.senderId,
                destination = data.stardustAPIPackage.receiverId,
                stardustOpCode = StardustPackageUtils.StardustOpCode.SEND_FILE,
                data = stardustFilePackage.toArrayInt()
            )
            fileStartMessage.stardustControlByte.stardustDeliveryType = radio.deliveryType
            if (stardustFilePackage.isLast) {
                fileStartMessage.stardustControlByte.stardustPartType = StardustControlByte.StardustPartType.LAST
            }
            packagesSent++
            if (stardustFilePackage.current in parityIndices) sparePackagesSent++
            else dataPackagesSent++
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
        val dropped = packagesDropped
        packagesSent = 0
        dataPackagesSent = 0
        sparePackagesSent = 0
        sendInterval = 900

        // Reed-Solomon lets the receiver rebuild the file from any `spare` missing
        // packages, so losing up to that many is a complete transfer, not a failure.
        // Beyond it the file cannot be reassembled — the channel swallowed more than
        // the parity budget covers.
        if (dropped > data.stardustAPIPackage.spare) {
            Timber.tag("FileUpload").w("send incomplete: $dropped package(s) never went out (parity budget ${data.stardustAPIPackage.spare})")
            failTransfer(FileReceiver.FileFailure.MISSING)
            return
        }

        if (!terminalOutcomeClaimed.compareAndSet(false, true)) return
        isComplete = true
        // Reuse existing handler — do NOT create a new Handler instance here
        handler.postDelayed({ isComplete = false }, 3000)
        onFileStatusChange?.finishSending(data)
    }

    /**
     * The radio went away mid-send: the packages still queued will never reach the air
     * and no progress tick will ever complete this transfer, so settle its row now.
     */
    fun failOnDisconnect() = failTransfer(FileReceiver.FileFailure.DISCONNECTED)

    /**
     * Records the failure on the local message row (state FAILED + the reason merged
     * into its extra_data) and tells the host. Runs at most once per sender — the first
     * terminal outcome wins, so a disconnect cannot re-open a finished send.
     *
     * The row is updated rather than inserted: the outgoing row already exists, written
     * when the send started.
     */
    private fun failTransfer(failure: FileReceiver.FileFailure) {
        if (!terminalOutcomeClaimed.compareAndSet(false, true)) return
        removeSendTimer()
        isSendingInProgress = false
        Timber.tag("FileUpload").w("send failed: ${data.file.name} reason=$failure msgId=$messageId")

        val id = messageId
        if (id == null) {
            Timber.w("Send failed ($failure) with no local message row to record it on")
            onFileStatusChange?.failedSending(data, failure)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                DataManager.getAppRepo().markFileTransferFailed(id, failure)
            } catch (e: Exception) {
                Timber.e(e, "Error recording send failure on message $id")
            }
            // Notified after the write, never beside it: a host that answers this by
            // re-reading the conversation would otherwise find the row still unfailed
            // and at its old timestamp.
            onFileStatusChange?.failedSending(data, failure)
        }
    }

    interface OnFileStatusChange {
        fun startSending(data: FileUtils.FileTransferData.Send) {}
        fun finishSending(data: FileUtils.FileTransferData.Send) {}
        fun stopSending(data: FileUtils.FileTransferData.Send) {}
        fun updateStep(data: FileUtils.FileTransferData.Send, percentage: Int) {}

        /**
         * The send did not deliver its file. The local message row is already marked
         * FAILED with [failure] merged into its extra_data by the time this is called,
         * so re-reading the conversation here is safe. The app owns the wording.
         *
         * Called on a background thread (or, when there was no row to write, on
         * whichever thread reached the failure), so hop to the main thread before
         * touching UI. Default no-op.
         */
        fun failedSending(
            data: FileUtils.FileTransferData.Send,
            failure: FileReceiver.FileFailure,
        ) {}

        /**
         * The send was stopped by the user and recorded as CANCELLED — a cancel is not a
         * failure, so [failedSending] does NOT also fire. [cancellation] says how far it
         * had got, and in particular whether the parity tail had started going out, in
         * which case the receiver may still end up with the file.
         *
         * Fires after [stopSending] and after the row is written, so re-reading the
         * conversation here is safe. Does NOT fire when the cancel arrived too late to
         * change anything — a send that had already finished or failed keeps its
         * outcome. Called on a background thread. Default no-op.
         */
        fun cancelledSending(
            data: FileUtils.FileTransferData.Send,
            cancellation: FileTransferCancellation,
        ) {}
    }

    companion object {

        private const val FILE_CHUNK_SIZE = 60

        fun calculateNumOfPackages(files: List<File>, spare: Int): Int {
            return files.sumOf { ceil(it.length().toDouble() / FILE_CHUNK_SIZE).toInt() } + spare
        }

        fun calculateSendTime(numOfPackages: Int, functionalityType: FunctionalityType): String {
            val radio = getRadioToSend(null, functionalityType)

            val totalTime = if (radio?.type == CarrierType.ST) 0.3 else 1.3

            // Round to whole seconds first so minutes/seconds stay consistent
            val totalSeconds = (numOfPackages * totalTime).roundToInt()
            val minutes = totalSeconds / 60 // Whole minutes
            val seconds = totalSeconds % 60 // Remaining whole seconds

            return if (minutes > 0) { "$minutes min $seconds sec"
//                String.format(
//                    "%d minute%s %.1f second%s",
//                    minutes,
//                    if (minutes > 1) "s" else "",
//                    seconds,
//                    if (seconds > 1.0) "s" else ""
//                )
            } else {
                " $seconds sec"
                //String.format("%.1f second%s", totalSeconds, if (totalSeconds > 1.0) "s" else "")
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
