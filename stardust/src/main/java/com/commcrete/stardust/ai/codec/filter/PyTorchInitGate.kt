package com.commcrete.stardust.ai.codec.filter

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import com.commcrete.stardust.room.StardustStorage

object PyTorchInitGate {
    private const val TAG = "PyTorchInitGate"
    private const val LOCK_NAME = "pytorch_once.lock"
    @Volatile private var holder: Holder? = null

    /**
     * Set when this process already holds the lock through another classloader — the plugin was
     * reloaded into a running ATAK. That copy keeps it until the process ends, so it is final.
     */
    @Volatile private var heldByOtherClassLoader = false
    @Volatile private var refusalLogged = false

    fun isPrimaryInitializer(): Boolean {
        if (holder != null) return true
        if (heldByOtherClassLoader) return false
        var raf: RandomAccessFile? = null
        return try {
            val f = File(StardustStorage.internalDir("locks"), LOCK_NAME)
            raf = RandomAccessFile(f, "rw")
            val ch: FileChannel = raf.channel
            val lk: FileLock? = ch.tryLock()
            if (lk == null) {
                refused("lock held by another process")
                false
            } else {
                holder = Holder(raf, ch, lk)
                true
            }
        } catch (e: OverlappingFileLockException) {
            heldByOtherClassLoader = true
            refused("lock already held in this process: plugin reloaded into a running ATAK; restart ATAK", e)
            false
        } catch (t: Throwable) {
            refused("lock check failed", t)
            false
        } finally {
            // Only the holder keeps its file open.
            if (holder?.raf !== raf) runCatching { raf?.close() }
        }
    }

    private fun refused(reason: String, cause: Throwable? = null) {
        if (refusalLogged) return
        refusalLogged = true
        Log.w(TAG, "AI codec refused for this process: $reason", cause)
    }

    private data class Holder(val raf: RandomAccessFile, val ch: FileChannel, val lk: FileLock)
}
