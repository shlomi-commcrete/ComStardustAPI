package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import com.commcrete.stardust.util.FileUtils.trimUntilUnderscore
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.commcrete.stardust.room.StardustStorage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class FileReceiver(
    val firstPackage: StardustFileStartPackage,
    val stardustPackage: StardustPackage,
    val onLastPackageReceived: (receiver: FileReceiver) -> Unit) {

    val dataList: MutableList<StardustFilePackage> = mutableListOf()
    var lastReportedProgress: Int = 0
    val lostPackagesIndex: MutableSet<Int> = mutableSetOf()

    /** Written by [start] on an IO thread, read by the progress callbacks on the main one. */
    @Volatile var data: FileUtils.FileTransferData.Receive

    private val receivingInterval : Long = 1800
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable { checkData() }

    @Volatile private var isDisposed = false

    /**
     * Claimed by whichever terminal outcome persists its row first, so exactly one —
     * the saved file OR a failure — reaches the message DB. A transfer that arrived
     * beats a failure declared for it afterwards.
     */
    private val terminalOutcomePersisted = AtomicBoolean(false)

    /**
     * The in-flight row this transfer is filling in, once [start] has written it. Null until then,
     * and null for good if the insert was refused — every settle path falls back to inserting a
     * finished row, which is what the receive side did for every transfer before this existed.
     */
    @Volatile private var messageId: Long? = null

    /**
     * Serializes everything that touches this transfer's row — the insert, the settle, the delete —
     * because the insert is asynchronous and a transfer can settle before it has run. Holding the
     * lock is what makes "insert, unless it already settled" and "update if there is a row, else
     * insert" one decision each rather than two racing ones.
     */
    private val rowMutex = Mutex()

    init {
        data = FileUtils.FileTransferData.Receive(
            id = getUniqueKey(stardustPackage),
            senderId = stardustPackage.senderId,
            chatId = stardustPackage.chatId,
            fileName = firstPackage.fileName,
            fileEnding = firstPackage.fileEnding,
            fileType = firstPackage.fileType,
            deliveryChannel = stardustPackage.stardustControlByte.stardustDeliveryType,
            numOfPackages = firstPackage.total,
        )
    }

    /**
     * Writes the in-flight row for this transfer: an attachment the conversation can show as
     * arriving, with no path yet because no file exists until every package is in. Called once,
     * by whoever registers this receiver, after any previous transfer on the transport has been
     * replaced — so the retry's row is never the one the abandoned attempt deletes.
     *
     * Failure is not fatal and is not retried: [messageId] simply stays null and each settle path
     * inserts its finished row the way it always did.
     *
     * Skipped entirely if the transfer has already settled or been disposed by the time this runs —
     * both are claimed synchronously by their caller, so seeing either under [rowMutex] means the
     * row question is answered and an insert now would only add a bubble that never finishes.
     */
    fun start() {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            rowMutex.withLock {
                if (terminalOutcomePersisted.get() || isDisposed) return@launch
                try {
                    val id = DataManager.getAppRepo().saveMessage(
                        message = MessageEntity(
                            chatId = data.chatId,
                            senderID = data.senderId,
                            receiverID = appId,
                            state = MessageState.RECEIVING,
                            extraData = MessageExtraData.Attachment(
                                title = "${data.fileName}${fileExtension()}",
                                // No file on disk yet: the packages are still being collected and
                                // are only assembled once. Readers go by the row's state, not this.
                                path = "",
                                subtype = data.fileType.toAttachmentType(),
                            ),
                        )
                    )
                    messageId = id
                    // Carried on every later progress callback so the host can bind the animation
                    // to the bubble this row renders as.
                    if (id != null) data = data.copy(messageId = id)
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error persisting in-flight transfer: ${data.fileName}", e)
                }
            }
        }
    }

    fun addDataPackage(filePackage: StardustFilePackage) {
        if (isDisposed) return
        dataList.add(filePackage)
        updateProgress()
        resetReceiveTimer()
    }

    fun resetReceiveTimer() {
        if (isDisposed) return
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        val timesDelay = calculateDelay()
        handler.postDelayed(
            runnable,
            receivingInterval * timesDelay
        )
    }

    fun removeReceiveTimer() {
        if (isDisposed) return
        isDisposed = true  // Seal receiver: prevents re-entry from late packets or in-flight coroutines
        try {
            onLastPackageReceived.invoke(this)
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error invoking callback", e)
        } finally {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }
    }

    /**
     * Drops this receiver without recording an outcome. Used when the same sender
     * restarts a transfer on this transport: the retry replaces this one, and it is the
     * retry's outcome that belongs in the conversation. Use [failOnDisconnect] when the
     * transfer is genuinely lost.
     *
     * The in-flight row goes with it. An abandoned attempt is not history — leaving it
     * would put a second, permanently-arriving bubble above the retry that supersedes it.
     * The delete is guarded on the row still being in flight, so it cannot touch a
     * transfer that settled in the meantime.
     */
    fun dispose() {
        isDisposed = true
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        dataList.clear()
        CoroutineScope(Dispatchers.IO).launch {
            rowMutex.withLock {
                // Read under the lock: the insert may still have been in flight when isDisposed was
                // set above, in which case it has already given up and there is nothing to remove.
                val id = messageId ?: return@launch
                messageId = null
                try {
                    DataManager.getAppRepo().deleteInFlightMessage(id)
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error removing abandoned transfer row $id", e)
                }
            }
        }
    }

    fun updateProgress() {
        if (isDisposed) return
        checkMissingPackages()
        Scopes.getMainCoroutine().launch {
            if (isDisposed) return@launch
            val newProgress = ((dataList.size.toDouble() / firstPackage.total) * 100).toInt()

            // Only update if progress has actually changed
            if (newProgress != lastReportedProgress) {
                lastReportedProgress = newProgress
                // Handle completion
                if (newProgress >= 100) {
                    checkData()
                    removeReceiveTimer()
                } else {
                    DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = newProgress)
                }
            }
        }
    }

    private fun checkData() {
        if (isDisposed) return
        // Check if we have all main packages or reached the last package
        val isComplete = hasMainPackages() ||
                         (dataList.isNotEmpty() && firstPackage.total == dataList.last().current + 1)

        if (isComplete) {
            saveFile()
            notifyTransferComplete()
        }
        else {
            updateFailure(FileFailure.MISSING)
        }
    }

    private fun notifyTransferComplete() {
        if (isDisposed) return
        Scopes.getMainCoroutine().launch {
            try {
                DataManager.getCallbacks()?.receiveFileStatus(data = data, percentage = 100)
            } catch (e: Exception) {
                Log.e("FileReceiver", "Error notifying completion", e)
            } finally {
                removeReceiveTimer()
                dataList.clear()
            }
        }
    }

    private fun calculateDelay(): Int {
        val spareDelay = firstPackage.spare - lostPackagesIndex.size

        val totalDelay = firstPackage.total - dataList.last().current + 1

        return maxOf(1, listOfNotNull(spareDelay, totalDelay).minOrNull() ?: 0)
    }

    /**
     * Settles this transfer as failed: writes the FAILED message row, then tells the
     * host. Persisting first is deliberate — a host that answers [receiveFailure] by
     * re-reading the conversation must find the row already there.
     *
     * Not guarded on [isDisposed]: the disconnect path fails and then disposes every
     * receiver, and a guard here would drop the very write that disconnect is for.
     * [terminalOutcomePersisted] is what keeps it to one outcome per transfer.
     */
    private fun updateFailure(failure: FileFailure) {
        if (!terminalOutcomePersisted.compareAndSet(false, true)) return
        Log.w("FileReceiver", "transfer failed: id=${data.id} name=${data.fileName} reason=$failure")
        // Seal the receiver before anything asynchronous: the write and the callback
        // below hop threads, and a package landing in that gap would otherwise still be
        // able to run the completion path and contradict the failure just declared.
        removeReceiveTimer()
        CoroutineScope(Dispatchers.IO).launch {
            saveFailureToMessages(failure)
            // Notified after the row exists, never beside it: a host that answers this
            // by re-reading the conversation must find the failed message there.
            withContext(Dispatchers.Main) {
                try {
                    DataManager.getCallbacks()?.receiveFailure(data = data, failure = failure)
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error notifying failure", e)
                }
            }
        }
    }

    /**
     * The radio went away mid-transfer: no further package, completion or failure will
     * ever be reported for this receiver, so settle its row now instead of leaving the
     * transfer to vanish silently.
     */
    fun failOnDisconnect() = updateFailure(FileFailure.DISCONNECTED)

    private fun saveFile () {
        removeReceiveTimer()
        val destDir = File(StardustStorage.chatDir(data.chatId), "files")
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val name = data.fileName
        val type = fileExtension()
        val ts = System.currentTimeMillis()
        val completeFileName = "$ts"+ "_"+"$name$type"
        val targetFile = File(destDir, "$completeFileName")
        try {

            // File and Contact are gzip-compressed text payloads; Image is raw bytes.
            if(data.fileType != FileUtils.FileType.Image) {
                // Step 1: Create a temporary file for the concatenated data
                val tempOutputFile = File.createTempFile("output_temp", null)
                // Write concatenated data to the temporary file

                //After i get all the packages.
                FileOutputStream(tempOutputFile).use { outputStream ->
                    writeDataToFile(outputStream)
                }
                // Step 2: Decompress the temporary file into the target file
                decompressTextFile(tempOutputFile, targetFile)
                // Clean up: Delete the temporary file
                tempOutputFile.delete()
            } else {
                FileOutputStream(targetFile).use { outputStream ->
                    writeDataToFile(outputStream)
                }
            }
            Log.d("FileReceiver", "File saved successfully: ${targetFile.absolutePath}")
            saveToMessages(targetFile)
            PlayerUtils.playNotificationSound()
            when (data.fileType) {
                FileUtils.FileType.File, FileUtils.FileType.Contact -> DataManager.getCallbacks()?.receiveFile(data = data, file = targetFile)
                FileUtils.FileType.Image -> DataManager.getCallbacks()?.receiveImage(data = data, file = targetFile)
            }
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error saving file: ${data.fileName}", e)
            updateFailure(FileFailure.ERROR)
        }
    }

    private fun writeDataToFile(outputStream: FileOutputStream) {
        val sortedList = dataList.sortedBy { it.current }
        if(firstPackage.spare == 0 || lostPackagesIndex.isEmpty()) {
            for (packageData in sortedList) {
                outputStream.write(packageData.data)
            }
        } else {
            val total = firstPackage.total
            val parityPackets = firstPackage.spare
            val receivedWithNulls: List<Packet?> = (0 until total).map { index ->
                if (lostPackagesIndex.contains(index)) {
                    null
                } else {
                    // Find the packet with current == index
                    sortedList.find { it.current == index }?.data
                }
            }
            val reedSolomonAuto = ReedSolomon (totalDataPackets = total - parityPackets , totalParityPackets = parityPackets)
            val decodedReed = reedSolomonAuto.decode(receivedPackets = receivedWithNulls,
                missingIndices = lostPackagesIndex.toIntArray() ).toMutableList()

            firstPackage.let {
                val spare = it.spareData
                val lastIndex = decodedReed.lastIndex
                decodedReed[lastIndex] = decodedReed[lastIndex].copyOfRange(0, decodedReed[lastIndex].size - spare)
            }

            for (packageData in decodedReed) {
//                    Log.d("decoded" , "decoded 2: ${packageData.toHexString()}")
                outputStream.write(packageData)
            }
        }
    }

    private fun checkMissingPackages() {
        if (dataList.isEmpty()) return

        val presentIndices = dataList.map { it.current }.toSet()
        val maxIndex = dataList.maxOf { it.current }

        for (i in 0..maxIndex) {
            if (i !in presentIndices) {
                lostPackagesIndex.add(i)
            }
        }

        if (lostPackagesIndex.isNotEmpty()) {
            Log.d("FileReceiver", "Missing packages: $lostPackagesIndex")
        }
    }

    private fun hasMainPackages(): Boolean {
        val mainCount = firstPackage.total
        val spare = firstPackage.spare
        // Check if any missing package index is in the main range
        return ((mainCount - spare) == dataList.size) && lostPackagesIndex.isEmpty()
    }

    /** ".jpg" for an image, otherwise the sender-supplied ending. */
    private fun fileExtension(): String =
        if (data.fileType == FileUtils.FileType.Image) ".jpg" else ".${data.fileEnding}"

    /**
     * Settles the transfer as failed so the conversation shows that a file was on its way
     * and did not make it, instead of the transfer vanishing.
     *
     * Normally this updates the in-flight row [start] wrote, in place: the bubble the user
     * has been watching arrive becomes the bubble that failed, keeping its position in the
     * conversation. Only when there is no such row — the insert was refused — does it fall
     * back to writing a finished FAILED row, which is what this always did. Either way the
     * row carries no path and no summary: a failure writes no file to disk at all.
     */
    private suspend fun saveFailureToMessages(failure: FileFailure) {
        rowMutex.withLock {
            messageId?.let { id ->
                try {
                    if (DataManager.getAppRepo().markIncomingTransferFailed(id, failure)) return
                    // Refused: the row settled by another path already, so it is not ours to
                    // overwrite and a second row would contradict it.
                    Log.d("FileReceiver", "failure not recorded on $id; row already settled")
                    return
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error failing in-flight row $id; inserting instead", e)
                }
            }
            val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
            try {
                DataManager.getAppRepo().saveMessage(
                    message = MessageEntity(
                        chatId = data.chatId,
                        senderID = data.senderId,
                        receiverID = appId,
                        state = MessageState.FAILED,
                        extraData = MessageExtraData.Attachment(
                            title = "${data.fileName}${fileExtension()}",
                            path = "",
                            subtype = data.fileType.toAttachmentType(),
                            failure = failure,
                        )
                    )
                )
            } catch (e: Exception) {
                Log.e("FileReceiver", "Error persisting failed transfer: ${data.fileName}", e)
            }
        }
    }

    /**
     * Settles the transfer as arrived: the in-flight row [start] wrote gets the path the file
     * landed at and its summary, and becomes RECEIVED in place — same row, same position in the
     * conversation, so the bubble the user watched arrive is the one they can now open. Falls
     * back to inserting a finished row when there is no in-flight row to fill in.
     */
    private fun saveToMessages (file: File) {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        // Claim the terminal outcome so a failure reported afterwards — a late
        // watchdog tick, a disconnect landing on the completing transfer — cannot add
        // a second, contradicting row for the file that did arrive.
        if (!terminalOutcomePersisted.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            val mFileName = trimUntilUnderscore(file.name)
            val subtype = data.fileType.toAttachmentType()
            // Parsed once here, for the same reason it always was: so the conversation UI
            // renders a received contact from the summary without re-reading the file.
            val summary = FileUtils.buildFileSummary(file, subtype)
            rowMutex.withLock {
                messageId?.let { id ->
                    try {
                        if (DataManager.getAppRepo()
                                .markIncomingTransferReceived(id, file.absolutePath, summary)
                        ) return@launch
                        Log.d("FileReceiver", "arrival not recorded on $id; row already settled")
                        return@launch
                    } catch (e: Exception) {
                        Log.e("FileReceiver", "Error settling in-flight row $id; inserting instead", e)
                    }
                }
                // Caught here rather than left to the default handler: this runs on a bare
                // scope, so an escaping exception would take the process down over one row.
                try {
                    DataManager.getAppRepo().saveMessage(
                        message = MessageEntity(
                            chatId = data.chatId,
                            senderID = data.senderId,
                            receiverID = appId,
                            state = MessageState.RECEIVED,
                            extraData = MessageExtraData.Attachment(
                                title = mFileName,
                                path = file.absolutePath,
                                subtype = subtype,
                                fileSummary = summary,
                            )
                        )
                    )
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error persisting received transfer: ${file.name}", e)
                }
            }
        }
    }

    /**
     * Why a file/image transfer did not deliver its file. Covers BOTH directions — a
     * send failure carries the same reasons (see
     * [FileSender.OnFileStatusChange.failedSending]) — and is persisted on the message
     * row as
     * [com.commcrete.stardust.room.new_db.message.MessageExtraData.Attachment.failure].
     * A user-cancelled send is NOT one of these; see
     * [com.commcrete.stardust.room.new_db.message.FileTransferCancellation].
     *
     * Persisted BY NAME: members may be added, never renamed or reordered, or an
     * already-written row stops parsing back.
     *
     * The SDK deliberately carries no user-facing wording for these — the app owns every
     * string it shows. Note [MISSING] means different things in each direction, so it
     * usually wants two different messages.
     */
    enum class FileFailure {
        /**
         * The transfer ran its course but packages never made it.
         *
         * Receiving: more packages were lost than the parity tail could repair.
         * Sending: packages had no radio to go out on, beyond what parity covers.
         */
        MISSING,
        /** Something went wrong on this side — disk write, unreadable source, no radio to send on. */
        ERROR,
        /** The radio went away mid-transfer; nothing was wrong with the transfer itself. */
        DISCONNECTED,

        /**
         * The app stopped while the transfer was still arriving, so no outcome was ever
         * reported for it. Recorded by the startup sweep, which is the only thing that can
         * tell this apart from a transfer still in flight — the receiver that would have
         * settled it died with the process.
         *
         * Receive-only: an interrupted SEND is already covered, because the send row settles
         * from the sender's own side.
         */
        INTERRUPTED,
    }

    companion object {
        fun getUniqueKey(filePackage: StardustPackage): String {
            return "${filePackage.getSourceAsString()}_${filePackage.getDestAsString()}_${filePackage.stardustControlByte.stardustDeliveryType}".hashCode().toString()
        }
    }
}
fun Packet.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }
