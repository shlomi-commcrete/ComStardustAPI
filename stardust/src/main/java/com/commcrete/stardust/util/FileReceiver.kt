package com.commcrete.stardust.util

import android.util.Log
import com.commcrete.bittell.util.bittel_package.model.StardustFilePackage
import com.commcrete.stardust.stardust.model.StardustFileStartPackage
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.FileSummary
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.FileUtils.decompressTextFile
import com.commcrete.stardust.util.FileUtils.trimUntilUnderscore
import com.commcrete.stardust.util.audio.PlayerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.commcrete.stardust.room.StardustStorage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

class FileReceiver(
    val firstPackage: StardustFileStartPackage,
    val stardustPackage: StardustPackage,
    val onLastPackageReceived: (receiver: FileReceiver) -> Unit) {

    /**
     * This transfer's own lane: one thread at a time, in the order things were asked for. Every
     * package, the gap timer, the settle — decode, decompress, disk write — and [dispose] run here,
     * so the package state below needs no lock, and none of it runs on the main thread. The settle
     * used to run on main, and a large image's decode there could freeze ATAK past the ANR limit.
     *
     * FIFO is what [failOnDisconnect] followed by [dispose] relies on: the settle it queues runs
     * before the dispose queued after it, with the packages still there.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lane = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // Package state: touched on [lane] only.
    private val dataList: MutableList<StardustFilePackage> = mutableListOf()
    private val receivedIndices: MutableSet<Int> = HashSet()
    private var lastReportedProgress: Int = 0
    private val lostPackagesIndex: MutableSet<Int> = mutableSetOf()

    /** Written by [start] on an IO thread, read by the progress callbacks on the main one. */
    @Volatile var data: FileUtils.FileTransferData.Receive

    /** The RD the start package's control byte names, as received text and locations record it. */
    private fun arrivedOn(): Carrier? =
        CarriersUtils.getCarrierByControl(stardustPackage.stardustControlByte.stardustDeliveryType)

    private val receivingInterval : Long = 1800

    /** No package for a while: settle from what arrived — see [checkData]. On [lane]. */
    private var gapTimer: Job? = null

    /** Set on [lane]; read there, and by [start]'s row insert. */
    @Volatile private var isDisposed = false

    private enum class Outcome { NONE, FAILED, RECEIVED }

    /**
     * What this transfer has settled as. A failure is claimed once, and only from NONE. RECEIVED
     * can follow a failure — packages that arrive in the [LATE_WINDOW_MS] after it may still make
     * the file buildable — and is final: nothing overwrites a file that arrived.
     */
    private val outcome = AtomicReference(Outcome.NONE)

    /** Closes the late window — see [enterLateWindow]. On [lane]. */
    private var lateWindow: Job? = null

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
            spare = firstPackage.spare,
            originalSize = firstPackage.originalSize,
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
                if (outcome.get() != Outcome.NONE || isDisposed) return@launch
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
                                originalSize = originalSizeOrNull(),
                            ),
                        ).withRd(arrivedOn())
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

    /** From the package handler's thread; the package is taken in on [lane]. */
    fun addDataPackage(filePackage: StardustFilePackage) {
        lane.launch {
            if (isDisposed) return@launch
            val index = filePackage.current
            // Outside the codeword the start announced: not a package of this transfer. Counting
            // it would push progress to 100% with real packages still missing.
            if (index !in 0 until firstPackage.total) {
                Log.w("FileReceiver", "package $index outside 0..${firstPackage.total - 1}; dropped")
                return@launch
            }
            // A repeat (the radio delivers some twice) adds nothing, and counting it would end the
            // transfer early on a count that includes it.
            if (!receivedIndices.add(index)) return@launch
            dataList.add(filePackage)
            if (outcome.get() == Outcome.FAILED) {
                // In the late window. Settles RECEIVED the moment the file can be built; while it
                // cannot, the sender is on air again, so the transfer reopens rather than taking
                // packages in silently under a row that says failed.
                checkMissingPackages()
                checkData(FileFailure.MISSING)
                if (outcome.get() == Outcome.FAILED) reopen()
                return@launch
            }
            updateProgress()
            resetReceiveTimer()
        }
    }

    /**
     * On [lane], after a failure that late packages could still undo — the gap timeout, or the
     * link going away unexpectedly (the sender keeps transmitting, and what reaches the radio
     * after a reconnect lands here). The receiver stays registered for [LATE_WINDOW_MS], taking
     * packages in; then it is sealed and emptied like any finished transfer.
     */
    private fun enterLateWindow() {
        gapTimer?.cancel()
        gapTimer = null
        lateWindow?.cancel()
        lateWindow = lane.launch {
            delay(LATE_WINDOW_MS)
            if (isDisposed) return@launch
            Log.d("FileReceiver", "late window closed: id=${data.id}")
            removeReceiveTimer()
            dataList.clear()
            receivedIndices.clear()
        }
    }

    /**
     * On [lane], from the late window: a package arrived after a MISSING or DISCONNECTED failure
     * and the file still cannot be built. The transfer goes back to arriving — outcome NONE, the
     * row RECEIVING, progress and the gap timer running — so the host sees it resume and a later
     * stall fails it again through the ordinary path, opening a fresh late window.
     */
    private fun reopen() {
        if (!outcome.compareAndSet(Outcome.FAILED, Outcome.NONE)) return
        Log.d("FileReceiver", "transfer resumed: id=${data.id}")
        lateWindow?.cancel()
        lateWindow = null
        CoroutineScope(Dispatchers.IO).launch {
            rowMutex.withLock {
                // Failed again, or arrived, before this ran: that outcome owns the row now.
                if (outcome.get() != Outcome.NONE) return@launch
                // Null only when neither the in-flight insert nor the failure's fallback insert
                // succeeded — there is no row to reopen, and the settle paths insert one.
                val id = messageId ?: return@launch
                try {
                    if (!DataManager.getAppRepo().reopenIncomingTransfer(id)) {
                        // The failure write had not landed yet and now never will (it checks
                        // the outcome), so the row is still RECEIVING.
                        Log.d("FileReceiver", "row $id not reopened; it was not failed")
                    }
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error reopening in-flight row $id", e)
                }
            }
        }
        // Forces a report even when the percentage has not moved since the failure: the host
        // last heard "failed", and only a progress callback tells it the transfer is live again.
        lastReportedProgress = -1
        updateProgress()
        resetReceiveTimer()
    }

    /** On [lane]. */
    private fun resetReceiveTimer() {
        if (isDisposed) return
        gapTimer?.cancel()
        val wait = receivingInterval * calculateDelay()
        gapTimer = lane.launch {
            delay(wait)
            checkData(FileFailure.MISSING)
        }
    }

    /** On [lane]. Seals the receiver: late packages and queued work find [isDisposed] and stop. */
    private fun removeReceiveTimer() {
        if (isDisposed) return
        isDisposed = true
        gapTimer?.cancel()
        gapTimer = null
        // Also when called from the window's own job: cancelling takes effect at its next
        // suspension, and there is none after this.
        lateWindow?.cancel()
        lateWindow = null
        try {
            onLastPackageReceived.invoke(this)
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error invoking callback", e)
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
     *
     * Queued on [lane] behind anything already asked of this receiver — a [failOnDisconnect]
     * just before it settles first, from the packages, and only then are they dropped. A
     * transfer that settled is not abandoned, so its row is left alone.
     */
    fun dispose() {
        lane.launch {
            val settled = outcome.get() != Outcome.NONE
            isDisposed = true
            gapTimer?.cancel()
            gapTimer = null
            dataList.clear()
            receivedIndices.clear()
            if (!settled) {
                CoroutineScope(Dispatchers.IO).launch {
                    rowMutex.withLock {
                        // Read under the lock: the insert may still have been in flight when
                        // isDisposed was set above, in which case it has already given up and
                        // there is nothing to remove.
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
            // Last thing on the lane: nothing more is asked of a disposed receiver. The row
            // writes above run on their own scope, so they finish regardless.
            lane.cancel()
        }
    }

    /** On [lane]. Progress goes to the host on main; reaching 100% settles here. */
    private fun updateProgress() {
        if (isDisposed) return
        checkMissingPackages()
        val newProgress = ((dataList.size.toDouble() / firstPackage.total) * 100).toInt()
        if (newProgress == lastReportedProgress) return
        lastReportedProgress = newProgress
        if (newProgress >= 100) {
            checkData(FileFailure.MISSING)
            removeReceiveTimer()
        } else {
            val snapshot = data
            Scopes.getMainCoroutine().launch {
                DataManager.getCallbacks()?.receiveFileStatus(data = snapshot, percentage = newProgress)
            }
        }
    }

    /**
     * Settles the transfer from the packages that arrived: the one rule for every way a receive
     * ends — all packages in, the gap timeout, and a lost link. It completes when the file can be
     * rebuilt, and fails with [failure] when it cannot.
     *
     * "Can be rebuilt" is [ReedSolomon]'s exact, per-block rule over every package that has not
     * arrived — including a tail that never came. This used to require the last package to have
     * arrived, so a transfer whose missing tail was only parity it could do without was failed
     * MISSING on the timeout and DISCONNECTED on a lost link, while one that had the last package
     * but too many holes went on to a decode that could only throw and fail ERROR.
     *
     * On [lane]: the decode and the disk write are the heaviest work a transfer does.
     */
    private fun checkData(failure: FileFailure) {
        if (isDisposed) return
        // No "all the data is here" shortcut: it took the first total - spare indices to be the
        // data, which they are not once parity is interleaved (over 255 packages) — writeDataToFile
        // knows the layout and skips the decode itself when every data package is present.
        val missing = missingIndices()
        if (isRecoverable(missing)) {
            saveFile()
            notifyTransferComplete()
        } else {
            updateFailure(failure)
        }
    }

    /** Codeword indices of every package that has not arrived. */
    private fun missingIndices(): Set<Int> {
        val present = dataList.mapTo(HashSet()) { it.current }
        return (0 until firstPackage.total).filterNotTo(HashSet()) { it in present }
    }

    /**
     * Whether the file can be assembled without the packages at [missing]. With no parity every
     * package is needed. Must agree with the sender's own rule (`FileSender.isDelivered`), which
     * builds the same [ReedSolomon] plan from the same data/parity split.
     */
    private fun isRecoverable(missing: Set<Int>): Boolean {
        if (dataList.isEmpty()) return false
        val spare = firstPackage.spare
        if (spare == 0) return missing.isEmpty()
        return ReedSolomon(totalDataPackets = firstPackage.total - spare, totalParityPackets = spare)
            .canRecover(missing)
    }

    /**
     * On [lane]. The host hears it on main; the receiver is sealed and emptied here.
     *
     * Note: [saveFile] seals the receiver before this runs, so the 100% is not sent after a save —
     * the host learns of the file from receiveFile / receiveImage instead. Kept as it was; sending
     * it now would be a new callback the host has never had.
     */
    private fun notifyTransferComplete() {
        if (!isDisposed) {
            val snapshot = data
            Scopes.getMainCoroutine().launch {
                try {
                    DataManager.getCallbacks()?.receiveFileStatus(data = snapshot, percentage = 100)
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error notifying completion", e)
                }
            }
        }
        removeReceiveTimer()
        dataList.clear()
        receivedIndices.clear()
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
     * [outcome] is what keeps it to one failure per transfer.
     *
     * On [lane]. A failure that missing packages caused — [FileFailure.MISSING],
     * [FileFailure.DISCONNECTED] — opens the late window ([enterLateWindow]): packages that
     * still arrive can turn it into RECEIVED. Any other failure seals the receiver now.
     */
    private fun updateFailure(failure: FileFailure) {
        if (!outcome.compareAndSet(Outcome.NONE, Outcome.FAILED)) return
        Log.w("FileReceiver", "transfer failed: id=${data.id} reason=$failure")
        val lateCanUndo = failure == FileFailure.MISSING || failure == FileFailure.DISCONNECTED
        if (lateCanUndo && !isDisposed) enterLateWindow() else removeReceiveTimer()
        val snapshot = data
        CoroutineScope(Dispatchers.IO).launch {
            saveFailureToMessages(failure)
            // Notified after the row exists, never beside it: a host that answers this
            // by re-reading the conversation must find the failed message there. Not at all
            // if late packages already completed the file or reopened the transfer — the host
            // has heard of it arriving, and the row refused this failure.
            if (outcome.get() != Outcome.FAILED) return@launch
            withContext(Dispatchers.Main) {
                // Again on main: reopen's progress report is queued there too, and a failure
                // delivered after it would take a resumed transfer down in the host.
                if (outcome.get() != Outcome.FAILED) return@withContext
                try {
                    DataManager.getCallbacks()?.receiveFailure(data = snapshot, failure = failure)
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error notifying failure", e)
                }
            }
        }
    }

    /**
     * The radio went away mid-transfer: no further package, completion or failure will
     * ever be reported for this receiver, so settle its row now instead of leaving the
     * transfer to vanish silently — from what arrived, by the same rule as the timeout
     * ([checkData]). A transfer already holding enough packages to rebuild the file is
     * assembled and RECEIVED; only one that cannot be is DISCONNECTED.
     *
     * Queued on [lane], not run on the caller's thread: the settle can decode and write a whole
     * file. The caller disposes this receiver straight after, which is safe only because the
     * lane is FIFO — this settle runs first, with the packages dispose() then clears.
     */
    fun failOnDisconnect() {
        lane.launch {
            if (isDisposed) {
                // Already sealed by a settled outcome; updateFailure keeps that outcome.
                updateFailure(FileFailure.DISCONNECTED)
                return@launch
            }
            gapTimer?.cancel()
            checkData(FileFailure.DISCONNECTED)
        }
    }

    /**
     * The start announced a transfer no file can be built from — see [isValidStart]. Recorded as
     * a failed file so the operator sees that one was on its way, instead of it vanishing; its
     * packages find the receiver sealed and are dropped.
     */
    fun failInvalidStart() {
        Log.w(
            "FileReceiver",
            "invalid file start: total=${firstPackage.total} spare=${firstPackage.spare} " +
                "spareData=${firstPackage.spareData}",
        )
        lane.launch { updateFailure(FileFailure.ERROR) }
    }

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
            // The file is built on [lane]; the host has always heard of it on main.
            val snapshot = data
            Scopes.getMainCoroutine().launch {
                try {
                    PlayerUtils.playNotificationSound()
                    when (snapshot.fileType) {
                        FileUtils.FileType.File, FileUtils.FileType.Contact ->
                            DataManager.getCallbacks()?.receiveFile(data = snapshot, file = targetFile)
                        FileUtils.FileType.Image ->
                            DataManager.getCallbacks()?.receiveImage(data = snapshot, file = targetFile)
                    }
                } catch (e: Exception) {
                    // saveToMessages has claimed the outcome and is settling the row RECEIVED; a
                    // host that threw here still finds the file in the conversation.
                    Log.e("FileReceiver", "Error notifying received file", e)
                }
            }
        } catch (e: Exception) {
            Log.e("FileReceiver", "Error saving file: id=${data.id}", e)
            // A late attempt that cannot write the file leaves the earlier failure standing,
            // and ends the window: the next package would only fail the same way.
            if (outcome.get() == Outcome.FAILED) removeReceiveTimer() else updateFailure(FileFailure.ERROR)
        }
    }

    /**
     * Writes the file's bytes: the data packages in order, the sender's padding cut off the last.
     *
     * With parity, the data is not "every package received": the parity is written nowhere, and
     * where it sits comes from [ReedSolomon.parityIndices] — interleaved per block once the
     * codeword passes 255 packages, never simply the tail. This used to write every package it
     * had whenever none was missing, parity and padding included, after the file's own bytes.
     */
    private fun writeDataToFile(outputStream: FileOutputStream) {
        assembleFileBytes(firstPackage, dataList.associate { it.current to it.data })
            .forEach { outputStream.write(it) }
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

    /** The start package's original size for the row: its 0 ("unknown") is the row's null. */
    private fun originalSizeOrNull(): Long? = data.originalSize.takeIf { it > 0 }?.toLong()

    /**
     * The size of the file that landed — decompressed, what the operator opens — for the RECEIVED
     * row. It overrides the start package's figure: that is the sender's claim, this is the file.
     * Null when the file could not be measured, which leaves the announced size on the row.
     */
    private fun landedSize(summary: FileSummary): Long? {
        val landed = summary.size.takeIf { it > 0 } ?: return null
        val announced = originalSizeOrNull()
        // Sizes only, never the name: worth seeing in Dev Logs when a sender's figure is off.
        if (announced != null && announced != landed) {
            Log.w("FileReceiver", "received size differs from announced: $landed vs $announced")
        }
        return landed
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
            // Packages resumed (see reopen) or completed the file before this write ran: the
            // failure no longer stands, and writing it would leave a live transfer's row FAILED.
            if (outcome.get() != Outcome.FAILED) return
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
                // Kept: late packages may still complete this transfer, and its arrival must
                // settle this row rather than add a second one beside it.
                messageId = DataManager.getAppRepo().saveMessage(
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
                            originalSize = originalSizeOrNull(),
                        )
                    ).withRd(arrivedOn())
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
     *
     * Also from the late window, over a failure already recorded: the same row goes from FAILED
     * to RECEIVED, its failure reason cleared (the DAO refuses only a row already received,
     * seen, archived or cancelled).
     */
    private fun saveToMessages (file: File) {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        // Claim the outcome so a failure reported afterwards — a late watchdog tick, a
        // disconnect landing on the completing transfer — cannot contradict the file that did
        // arrive. Over a failure is allowed; over an arrival is not.
        if (!outcome.compareAndSet(Outcome.NONE, Outcome.RECEIVED) &&
            !outcome.compareAndSet(Outcome.FAILED, Outcome.RECEIVED)
        ) return
        CoroutineScope(Dispatchers.IO).launch {
            val mFileName = trimUntilUnderscore(file.name)
            val subtype = data.fileType.toAttachmentType()
            // Parsed once here, for the same reason it always was: so the conversation UI
            // renders a received contact from the summary without re-reading the file.
            val summary = FileUtils.buildFileSummary(file, subtype)
            val landedSize = landedSize(summary)
            rowMutex.withLock {
                messageId?.let { id ->
                    try {
                        if (DataManager.getAppRepo()
                                .markIncomingTransferReceived(id, file.absolutePath, summary, landedSize)
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
                                originalSize = landedSize ?: originalSizeOrNull(),
                            )
                        ).withRd(arrivedOn())
                    )
                } catch (e: Exception) {
                    Log.e("FileReceiver", "Error persisting received transfer: ${file.name}", e)
                }
            }
        }
    }

    /**
     * Why a file/image transfer did not deliver its file — or a multi-part text did not get
     * through whole. Covers BOTH directions — a send failure carries the same reasons (see
     * [FileSender.OnFileStatusChange.failedSending]) — and is persisted on the message
     * row as
     * [com.commcrete.stardust.room.new_db.message.MessageExtraData.Attachment.failure] or
     * [com.commcrete.stardust.room.new_db.message.MessageExtraData.Text.failure].
     * A user-cancelled send is NOT one of these; see
     * [com.commcrete.stardust.room.new_db.message.FileTransferCancellation].
     *
     * Named for files because files had it first; a text uses the same reasons rather than a
     * parallel enum, so a consumer words a failed transfer one way whatever it carried.
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
         * Receiving: more packages were lost than the parity tail could repair — or, for a
         * text, its LAST part never arrived before the gap timeout.
         * Sending: packages had no radio to go out on, beyond what parity covers.
         */
        MISSING,
        /** Something went wrong on this side — disk write, unreadable source, no radio to send on, a send loop that died. */
        ERROR,
        /**
         * The radio was not connected: it went away mid-transfer, or was already gone when the
         * send was asked for — nothing was wrong with the transfer itself. Set only when what
         * had got through by then was not enough; one that had completed is settled as sent or
         * received instead.
         */
        DISCONNECTED,

        /**
         * The app stopped while the transfer was still in flight, so no outcome was ever
         * reported for it. Recorded by the startup sweep, which is the only thing that can
         * tell this apart from a transfer still in flight — the sender or receiver that would
         * have settled it died with the process. Both directions: a send the process died in
         * is settled from the progress it recorded, and this is its reason when that was not
         * enough.
         */
        INTERRUPTED,

        /**
         * The payload needs more packages than the wire can number —
         * [FileSender.MAX_TOTAL_PACKAGES]. Send-only, and raised before anything happens:
         * no `startSending`, no local row, nothing on air.
         *
         * **Never persisted, and must stay that way.** `MessageExtraData.failure` is decoded
         * by kotlinx.serialization, which throws on an enum value it does not know — a build
         * older than this value reading a row that carried it would lose the whole extra
         * data. The send is refused before [FileSender] writes a row, so there is none.
         */
        TOO_LARGE,
    }

    companion object {
        /**
         * How long a failed receive keeps taking packages — see [enterLateWindow]. Long enough to
         * outlast a BLE reconnect (one took 42s on the XCover6 Pro after a status-61 drop) with the
         * sender still transmitting; bounded so a transfer that is really over stops holding its
         * packages in memory. A choice, not a radio constant.
         */
        const val LATE_WINDOW_MS = 120_000L

        /**
         * The file's bytes from the packages [received] by codeword index — see [writeDataToFile].
         * The caller has checked the file can be built ([isRecoverable]).
         */
        internal fun assembleFileBytes(
            start: StardustFileStartPackage,
            received: Map<Int, ByteArray>,
        ): List<ByteArray> {
            if (start.spare == 0) {
                // No parity, no padding: every package is data, and none may be missing.
                return (0 until start.total).map { received.getValue(it) }
            }
            val total = start.total
            val rs = ReedSolomon(totalDataPackets = total - start.spare, totalParityPackets = start.spare)
            val parity = rs.parityIndices()
            val dataIndices = (0 until total).filter { it !in parity }
            val packets: MutableList<Packet> = if (dataIndices.all { it in received }) {
                // Every data package arrived: no decode.
                dataIndices.mapTo(mutableListOf()) { received.getValue(it) }
            } else {
                rs.decode(receivedPackets = (0 until total).map { received[it] }).toMutableList()
            }
            // FileSender.createSparePackages padded the last data package to a full chunk.
            val last = packets.lastIndex
            packets[last] = packets[last].copyOfRange(0, packets[last].size - start.spareData)
            return packets
        }

        /**
         * Whether a start describes a file that can be built: at least one package, at least one
         * of them data (parity alone rebuilds nothing), and no more than the wire can number. The
         * header comes off the air: a corrupt one made [ReedSolomon] throw (`spare > total`) on the
         * main thread and took ATAK down with it.
         */
        fun isValidStart(start: StardustFileStartPackage): Boolean =
            start.total in 1..FileSender.MAX_TOTAL_PACKAGES &&
                start.spare in 0 until start.total &&
                start.spareData >= 0

        fun getUniqueKey(filePackage: StardustPackage): String {
            return "${filePackage.getSourceAsString()}_${filePackage.getDestAsString()}_${filePackage.stardustControlByte.stardustDeliveryType}".hashCode().toString()
        }
    }
}
fun Packet.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }
