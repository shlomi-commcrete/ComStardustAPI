package com.commcrete.stardust.util

import com.commcrete.pign.PigN
import com.commcrete.stardust.room.StardustStorage
import com.commcrete.stardust.room.new_db.message.MessageEntity
import com.commcrete.stardust.room.new_db.message.MessageExtraData
import com.commcrete.stardust.room.new_db.message.MessageState
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.stardust.model.V2DataPacket
import com.commcrete.stardust.stardust.model.V2HeaderData
import com.commcrete.stardust.util.FileReceiver.FileFailure
import com.commcrete.stardust.util.FileUtils.FileType
import com.commcrete.stardust.util.FileUtils.buildFileSummary
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
import kotlin.math.ceil
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Receives a V2 ("ST1") file transfer.
 *
 * The receiver is registered by [com.commcrete.stardust.stardust.StardustPackageHandler] when it
 * sees an ST1 header packet. Subsequent data/parity packets are routed here by transport key.
 *
 * Key differences from [FileReceiver] (V1):
 *  - RS slot 0 is the 58-byte header_vector sent as part of the ST1 header packet itself; the
 *    sender transmits N-1 data/parity wire packets in addition to the header.
 *  - N (total slots) and P (parity count) are discovered lazily from the arriving data packets
 *    (odd-indexed → meta=N, even-indexed → meta=P). Up to [MAX_PENDING] packets that arrive
 *    before the header is processed are buffered and replayed.
 *  - Decompression is driven by [V2HeaderData.encodingWireBits]:
 *      0   → gzip (or raw for IMAGE)
 *      1   → PigN: payload is [method(1 byte)] + [PigN compressed bytes]
 */
class FileReceiverV2(
    private val stardustPackage: StardustPackage,
    val transferKey: String,
    val startedAtMs: Long,
    val onComplete: (FileReceiverV2) -> Unit,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lane = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // 0-based codeword index → 58-byte slot data. Slot 0 = headerVector.
    private val slots = mutableMapOf<Int, ByteArray>()

    private var headerData: V2HeaderData? = null
    private var totalSlots: Int? = null   // N (codeword length including slot 0)
    private var parityCount: Int? = null  // P

    // Data packets that arrive on the lane before the header has been processed.
    private val pendingPackets = ArrayDeque<V2DataPacket>()

    private var gapTimer: Job? = null

    @Volatile private var isDisposed = false

    private enum class Outcome { NONE, FAILED, RECEIVED }
    private val outcome = AtomicReference(Outcome.NONE)

    @Volatile var data: FileUtils.FileTransferData.Receive? = null

    private val rowMutex = Mutex()
    @Volatile private var messageId: Long? = null

    private fun arrivedOn() =
        CarriersUtils.getCarrierByControl(stardustPackage.stardustControlByte.stardustDeliveryType)

    // ── Public API (called from the handler's thread; work dispatched to [lane]) ──────────────

    fun addHeaderPacket(hd: V2HeaderData) {
        lane.launch {
            if (isDisposed) return@launch
            if (headerData != null) return@launch  // copy-header or duplicate; first wins
            headerData = hd
            slots[0] = hd.headerVector
            val fileType = contentTypeToFileType(hd.contentTypeBits)
            data = FileUtils.FileTransferData.Receive(
                id = transferKey,
                chatId = stardustPackage.chatId,
                senderId = stardustPackage.senderId,
                fileName = hd.fileName,
                fileEnding = hd.fileExtension,
                fileType = fileType,
                numOfPackages = totalSlots ?: 0,
                deliveryChannel = stardustPackage.stardustControlByte.stardustDeliveryType,
                timestamp = startedAtMs,
                spare = parityCount ?: 0,
                originalSize = hd.originalSize,
            )
            startInFlightRow()
            val pending = pendingPackets.toList()
            pendingPackets.clear()
            pending.forEach { processDataPacket(it) }
            if (!isDisposed) resetGapTimer()
        }
    }

    fun addDataPacket(dp: V2DataPacket) {
        lane.launch {
            if (isDisposed) return@launch
            if (headerData == null) {
                if (pendingPackets.size < MAX_PENDING) pendingPackets.addLast(dp)
                return@launch
            }
            processDataPacket(dp)
        }
    }

    fun failOnDisconnect() {
        lane.launch {
            if (isDisposed) {
                updateFailure(FileFailure.DISCONNECTED)
                return@launch
            }
            gapTimer?.cancel()
            checkData(FileFailure.DISCONNECTED)
        }
    }

    fun dispose() {
        lane.launch {
            val settled = outcome.get() != Outcome.NONE
            isDisposed = true
            gapTimer?.cancel()
            gapTimer = null
            slots.clear()
            pendingPackets.clear()
            if (!settled) {
                CoroutineScope(Dispatchers.IO).launch {
                    rowMutex.withLock {
                        val id = messageId ?: return@launch
                        messageId = null
                        try {
                            DataManager.getAppRepo().deleteInFlightMessage(id)
                        } catch (e: Exception) {
                            StardustLog.e(TAG, "Error removing abandoned V2 transfer row $id", e)
                        }
                    }
                }
            }
            lane.cancel()
        }
    }

    // ── Internal lane-only logic ──────────────────────────────────────────────────────────────

    private fun processDataPacket(dp: V2DataPacket) {
        // rsIndex (1-based) equals 0-based codeword index since slot 0 is the header.
        val slotIndex = dp.rsIndex
        if (slots.containsKey(slotIndex)) return  // duplicate

        slots[slotIndex] = dp.payload

        // Discover N from odd-indexed packets, P from even-indexed packets.
        if (dp.rsIndex % 2 == 1) {
            if (totalSlots == null) {
                totalSlots = dp.meta
                data = data?.copy(numOfPackages = dp.meta)
            }
        } else {
            if (parityCount == null) {
                parityCount = dp.meta
                data = data?.copy(spare = dp.meta)
            }
        }

        val n = totalSlots ?: run { resetGapTimer(); return }
        val pKnown = parityCount
        val p = pKnown ?: estimatedParity(n)

        updateProgress()
        if (canDecode(n, p)) tryDecode(n, p, pIsEstimated = pKnown == null) else resetGapTimer()
    }

    private fun canDecode(n: Int, p: Int): Boolean {
        val k = n - p
        if (k <= 0 || slots.size < k) return false
        val missing = (0 until n).filterNotTo(mutableSetOf()) { it in slots }
        return ReedSolomon(totalDataPackets = k, totalParityPackets = p).canRecover(missing)
    }

    private fun tryDecode(n: Int, p: Int, pIsEstimated: Boolean = false) {
        gapTimer?.cancel()
        gapTimer = null
        val k = n - p
        val hd = headerData ?: return
        try {
            val receivedList: List<ByteArray?> = (0 until n).map { slots[it] }
            val decoded: List<ByteArray> = ReedSolomon(totalDataPackets = k, totalParityPackets = p)
                .decode(receivedList)
            // decoded[0] = headerVector (slot 0); decoded[1..k-1] = data chunks
            val chunks = decoded.subList(1, decoded.size)
            val assembled = assemblePayload(chunks, hd.padding)
            val fileBytes = decompressV2(assembled, hd)
            saveFile(fileBytes, hd)
        } catch (e: Exception) {
            StardustLog.e(TAG, "V2 decode/decompress failed", e)
            if (pIsEstimated) resetGapTimer() else updateFailure(FileFailure.ERROR)
        }
    }

    private fun assemblePayload(chunks: List<ByteArray>, padding: Int): ByteArray {
        val buf = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(buf, pos); pos += c.size }
        return if (padding > 0 && padding <= buf.size) buf.copyOfRange(0, buf.size - padding) else buf
    }

    private fun decompressV2(data: ByteArray, hd: V2HeaderData): ByteArray = when {
        // PigN is checked before the IMAGE case: images are PigN-packed too now.
        hd.encodingWireBits == 1 -> {
            // PigN: payload is [method(1)] + compressed bytes
            val method = data[0].toInt() and 0xFF
            PigN.decompress(data.copyOfRange(1, data.size), method)
        }
        // IMAGE with encoding 0 is raw (senders that don't use PigN, or whose PigN call failed).
        hd.contentTypeBits == 1 -> data
        hd.encodingWireBits == 0 ->
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        else -> data
    }

    private fun saveFile(fileBytes: ByteArray, hd: V2HeaderData) {
        removeReceiveTimer()
        val fileType = contentTypeToFileType(hd.contentTypeBits)
        val ext = fileExtension(fileType, hd.fileExtension)
        val destDir = File(StardustStorage.chatDir(stardustPackage.chatId), "files")
        if (!destDir.exists()) destDir.mkdirs()
        val targetFile = File(destDir, "${System.currentTimeMillis()}_${hd.fileName}$ext")
        try {
            FileOutputStream(targetFile).use { it.write(fileBytes) }
            StardustLog.i(LogType.FILES, TAG, "V2 file received: ${slots.size} of $totalSlots slot(s)")
            saveToMessages(targetFile, hd, fileType)
            val snapshot = data ?: return
            Scopes.getMainCoroutine().launch {
                try {
                    PlayerUtils.playNotificationSound()
                    when (fileType) {
                        FileType.File, FileType.Contact ->
                            DataManager.getCallbacks()?.receiveFile(data = snapshot, file = targetFile)
                        FileType.Image ->
                            DataManager.getCallbacks()?.receiveImage(data = snapshot, file = targetFile)
                    }
                } catch (e: Exception) {
                    StardustLog.e(TAG, "Error notifying received V2 file", e)
                }
            }
        } catch (e: Exception) {
            StardustLog.e(TAG, "Error saving V2 file", e)
            if (outcome.get() == Outcome.FAILED) removeReceiveTimer() else updateFailure(FileFailure.ERROR)
        }
    }

    private fun checkData(failure: FileFailure) {
        if (isDisposed) return
        val n = totalSlots ?: run { updateFailure(failure); return }
        val pKnown = parityCount
        val p = pKnown ?: estimatedParity(n)
        if (canDecode(n, p)) tryDecode(n, p, pIsEstimated = pKnown == null) else updateFailure(failure)
    }

    private fun updateProgress() {
        val n = totalSlots ?: return
        val pct = (slots.size * 100 / n).coerceIn(0, 99)
        if (pct <= 0) return
        val snapshot = data ?: return
        Scopes.getMainCoroutine().launch {
            DataManager.getCallbacks()?.receiveFileStatus(data = snapshot, percentage = pct)
        }
    }

    private fun resetGapTimer() {
        if (isDisposed) return
        gapTimer?.cancel()
        gapTimer = lane.launch {
            delay(GAP_TIMEOUT_MS)
            checkData(FileFailure.MISSING)
        }
    }

    private fun removeReceiveTimer() {
        if (isDisposed) return
        isDisposed = true
        gapTimer?.cancel()
        gapTimer = null
        try { onComplete(this) } catch (e: Exception) {
            StardustLog.e(TAG, "Error invoking V2 completion callback", e)
        }
    }

    private fun updateFailure(failure: FileFailure) {
        if (!outcome.compareAndSet(Outcome.NONE, Outcome.FAILED)) return
        StardustLog.w(LogType.FILES, TAG, "V2 transfer failed: reason=$failure")
        removeReceiveTimer()
        val snapshot = data ?: return
        CoroutineScope(Dispatchers.IO).launch {
            saveFailureToMessages(failure)
            if (outcome.get() != Outcome.FAILED) return@launch
            withContext(Dispatchers.Main) {
                if (outcome.get() != Outcome.FAILED) return@withContext
                try {
                    DataManager.getCallbacks()?.receiveFailure(data = snapshot, failure = failure)
                } catch (e: Exception) {
                    StardustLog.e(TAG, "Error notifying V2 failure", e)
                }
            }
        }
    }

    // ── DB row management ─────────────────────────────────────────────────────────────────────

    private fun startInFlightRow() {
        val hd = headerData ?: return
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        val fileType = contentTypeToFileType(hd.contentTypeBits)
        val ext = fileExtension(fileType, hd.fileExtension)
        StardustLog.i(LogType.FILES, TAG, "V2 receiving file: ${hd.fileName}$ext")
        CoroutineScope(Dispatchers.IO).launch {
            rowMutex.withLock {
                if (outcome.get() != Outcome.NONE || isDisposed) return@launch
                try {
                    val id = DataManager.getAppRepo().saveMessage(
                        MessageEntity(
                            chatId = stardustPackage.chatId,
                            senderID = stardustPackage.senderId,
                            receiverID = appId,
                            state = MessageState.RECEIVING,
                            extraData = MessageExtraData.Attachment(
                                title = "${hd.fileName}$ext",
                                path = "",
                                subtype = fileType.toAttachmentType(),
                                originalSize = hd.originalSize.takeIf { it > 0 }?.toLong(),
                            ),
                        ).withRd(arrivedOn())
                    )
                    messageId = id
                    if (id != null) data = data?.copy(messageId = id)
                } catch (e: Exception) {
                    StardustLog.e(TAG, "Error persisting V2 in-flight transfer", e)
                }
            }
        }
    }

    private suspend fun saveFailureToMessages(failure: FileFailure) {
        rowMutex.withLock {
            if (outcome.get() != Outcome.FAILED) return
            val hd = headerData
            val fileType = if (hd != null) contentTypeToFileType(hd.contentTypeBits) else FileType.File
            val ext = if (hd != null) fileExtension(fileType, hd.fileExtension) else ""
            val title = "${hd?.fileName ?: ""}$ext"
            messageId?.let { id ->
                try {
                    if (DataManager.getAppRepo().markIncomingTransferFailed(id, failure)) return
                    StardustLog.d(LogType.FILES, TAG, "V2 failure not recorded on row $id; already settled")
                    return
                } catch (e: Exception) {
                    StardustLog.e(TAG, "Error failing in-flight V2 row $id; inserting instead", e)
                }
            }
            val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
            try {
                messageId = DataManager.getAppRepo().saveMessage(
                    MessageEntity(
                        chatId = stardustPackage.chatId,
                        senderID = stardustPackage.senderId,
                        receiverID = appId,
                        state = MessageState.FAILED,
                        extraData = MessageExtraData.Attachment(
                            title = title,
                            path = "",
                            subtype = fileType.toAttachmentType(),
                            failure = failure,
                            originalSize = hd?.originalSize?.takeIf { it > 0 }?.toLong(),
                        ),
                    ).withRd(arrivedOn())
                )
            } catch (e: Exception) {
                StardustLog.e(TAG, "Error persisting V2 failed transfer", e)
            }
        }
    }

    private fun saveToMessages(file: File, hd: V2HeaderData, fileType: FileType) {
        val appId = RegisteredUserUtils.currentUserFlow.value?.appId ?: return
        if (!outcome.compareAndSet(Outcome.NONE, Outcome.RECEIVED) &&
            !outcome.compareAndSet(Outcome.FAILED, Outcome.RECEIVED)
        ) return
        CoroutineScope(Dispatchers.IO).launch {
            val mFileName = trimUntilUnderscore(file.name)
            val subtype = fileType.toAttachmentType()
            val summary = buildFileSummary(file, subtype)
            val landedSize = summary.size.takeIf { it > 0 }
            rowMutex.withLock {
                messageId?.let { id ->
                    try {
                        if (DataManager.getAppRepo()
                                .markIncomingTransferReceived(id, file.absolutePath, summary, landedSize)
                        ) return@launch
                        StardustLog.d(LogType.FILES, TAG, "V2 arrival not recorded on row $id; already settled")
                        return@launch
                    } catch (e: Exception) {
                        StardustLog.e(TAG, "Error settling in-flight V2 row $id; inserting instead", e)
                    }
                }
                try {
                    DataManager.getAppRepo().saveMessage(
                        MessageEntity(
                            chatId = stardustPackage.chatId,
                            senderID = stardustPackage.senderId,
                            receiverID = appId,
                            state = MessageState.RECEIVED,
                            extraData = MessageExtraData.Attachment(
                                title = mFileName,
                                path = file.absolutePath,
                                subtype = subtype,
                                fileSummary = summary,
                                originalSize = landedSize ?: hd.originalSize.takeIf { it > 0 }?.toLong(),
                            ),
                        ).withRd(arrivedOn())
                    )
                } catch (e: Exception) {
                    StardustLog.e(TAG, "Error persisting received V2 transfer", e)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun fileExtension(fileType: FileType, ext: String): String = when {
        fileType == FileType.Image -> ".jpg"
        ext.isNotEmpty() -> ".$ext"
        else -> ""
    }

    companion object {
        private const val TAG = "FileReceiverV2"
        private const val GAP_TIMEOUT_MS = 30_000L
        private const val MAX_PENDING = 16

        /** Used when P hasn't arrived yet: ceil(15% of N), minimum 2. */
        fun estimatedParity(n: Int): Int = ceil(n * 0.15).toInt().coerceAtLeast(2)

        fun contentTypeToFileType(bits: Int): FileType = when (bits) {
            1 -> FileType.Image
            2 -> FileType.Contact
            else -> FileType.File
        }

        /** Same transport key as V1 — one sender/dest/RD tuple, one slot. */
        fun getUniqueKey(mPackage: StardustPackage): String = FileReceiver.getUniqueKey(mPackage)

        fun transferId(transportKey: String, hd: V2HeaderData, startedAtMs: Long, sequence: Long): String {
            val source = listOf(
                transportKey, hd.fileName, hd.fileExtension, hd.originalSize, startedAtMs, sequence,
            ).joinToString("_")
            return MessageDigest.getInstance("MD5")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }
}
