package com.commcrete.stardust.ai.codec

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import android.content.Context

object PyTorchInitGate {
    private const val LOCK_NAME = "pytorch_once.lock"
    @Volatile private var holder: Holder? = null

    fun isPrimaryInitializer(ctx: Context): Boolean {
        if (holder != null) return true
        return try {
            val f = File(ctx.filesDir, LOCK_NAME)
            val raf = RandomAccessFile(f, "rw")
            val ch: FileChannel = raf.channel
            val lk: FileLock? = ch.tryLock()  // null if already locked by another classloader
            if (lk == null) false
            else {
                holder = Holder(raf, ch, lk)
                true
            }
        } catch (_: Throwable) { false }
    }
    private data class Holder(val raf: RandomAccessFile, val ch: FileChannel, val lk: FileLock)
}