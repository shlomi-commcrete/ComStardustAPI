package com.example.chunkrecorder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Tiny helper that streams raw int16 PCM samples to a WAV file under
 * `Downloads/Stardust/` for debugging. Use it to capture the audio EXACTLY as
 * delivered by `AudioRecord` — i.e. before any DSP / gain / encoding — so it
 * can be inspected in Audacity, ffmpeg, etc.
 *
 * Lifecycle:
 *  ```
 *  val w = DebugRawWavWriter()
 *  w.start(context, sampleRate = 24_000, channels = 1, bitsPerSample = 16)
 *  w.append(samples, len)   // call as many times as needed
 *  w.stop()                 // patches the header with final sizes
 *  ```
 *
 * On API 29+ (Q) the file is created via MediaStore.Downloads (no storage
 * permission required). On older APIs it falls back to legacy public
 * Downloads dir which requires `WRITE_EXTERNAL_STORAGE`.
 */
class DebugRawWavWriter {

    companion object {
        private const val TAG = "DebugRawWavWriter"
        private const val SUBDIR = "Stardust"
    }

    private var fos: FileOutputStream? = null
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var pfd: android.os.ParcelFileDescriptor? = null
    private var mediaStoreUri: android.net.Uri? = null
    private var contentResolver: android.content.ContentResolver? = null

    private var bytesWritten: Long = 0
    private var sampleRate: Int = 0
    private var channels: Int = 0
    private var bitsPerSample: Int = 0
    private var started: Boolean = false

    /** Opens a new WAV file. Safe to call multiple times — re-opens fresh. */
    @Synchronized
    fun start(
        context: Context,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        fileNamePrefix: String = "raw"
    ) {
        stop() // close any prior session

        this.sampleRate = sampleRate
        this.channels = channels
        this.bitsPerSample = bitsPerSample
        this.bytesWritten = 0

        val displayName = "${System.currentTimeMillis()}_${fileNamePrefix}.wav"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openViaMediaStore(context, displayName)
            } else {
                openLegacy(displayName)
            }

            writePlaceholderHeader()
            started = true
            Log.i(TAG, "started: $displayName ($sampleRate Hz, $channels ch, $bitsPerSample bit)")
        } catch (t: Throwable) {
            Log.e(TAG, "start failed; debug capture disabled", t)
            cleanupHandles()
            started = false
        }
    }

    /** Appends [length] int16 samples to the WAV body. No-op if not started. */
    @Synchronized
    fun append(samples: ShortArray, length: Int) {
        if (!started || length <= 0) return
        val ch = channel ?: return
        try {
            val bb = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until length) bb.putShort(samples[i])
            bb.flip()
            while (bb.hasRemaining()) ch.write(bb)
            bytesWritten += length * 2L
        } catch (t: Throwable) {
            Log.w(TAG, "append failed; disabling capture", t)
            stop()
        }
    }

    /** Patches the WAV header with final sizes and closes the file. Idempotent. */
    @Synchronized
    fun stop() {
        if (!started && fos == null && raf == null) return
        try {
            val ch = channel
            if (ch != null && bytesWritten > 0) {
                // RIFF chunk size at offset 4 = totalFileSize - 8 = 36 + dataSize
                val riffSize = (36 + bytesWritten).toInt()
                ch.position(4)
                val b1 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                b1.putInt(riffSize); b1.flip()
                while (b1.hasRemaining()) ch.write(b1)

                // data chunk size at offset 40
                ch.position(40)
                val b2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                b2.putInt(bytesWritten.toInt()); b2.flip()
                while (b2.hasRemaining()) ch.write(b2)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stop: header patch failed", t)
        } finally {
            cleanupHandles()
            // Mark MediaStore entry as no longer pending so it shows up.
            try {
                val uri = mediaStoreUri
                val cr = contentResolver
                if (uri != null && cr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    cr.update(uri, cv, null, null)
                    Log.i(TAG, "saved $uri ($bytesWritten bytes)")
                }
            } catch (_: Throwable) {}
            mediaStoreUri = null
            contentResolver = null
            started = false
        }
    }

    // ─── internals ───────────────────────────────────────────────────────────

    @android.annotation.SuppressLint("NewApi", "InlinedApi")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun openViaMediaStore(context: Context, displayName: String) {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        @android.annotation.SuppressLint("NewApi")
        val downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = cr.insert(downloadsUri, values)
            ?: error("MediaStore insert returned null")

        // "rw" mode is required for FileChannel.position()/write to seek backwards
        // when patching the header on stop().
        val pfd = cr.openFileDescriptor(uri, "rw")
            ?: error("openFileDescriptor returned null")

        val fos = FileOutputStream(pfd.fileDescriptor)
        this.contentResolver = cr
        this.mediaStoreUri = uri
        this.pfd = pfd
        this.fos = fos
        this.channel = fos.channel
    }

    @Suppress("DEPRECATION")
    private fun openLegacy(displayName: String) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = java.io.File(downloads, SUBDIR).also { it.mkdirs() }
        val file = java.io.File(dir, displayName)
        val raf = RandomAccessFile(file, "rw")
        this.raf = raf
        this.channel = raf.channel
    }

    private fun writePlaceholderHeader() {
        val ch = channel ?: return
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(0) // RIFF size — patched in stop()
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)             // PCM fmt chunk size
            putShort(1)            // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(0) // data size — patched in stop()
        }
        header.flip()
        ch.position(0)
        while (header.hasRemaining()) ch.write(header)
    }

    private fun cleanupHandles() {
        try { channel?.close() } catch (_: Throwable) {}
        try { fos?.close() } catch (_: Throwable) {}
        try { raf?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        channel = null
        fos = null
        raf = null
        pfd = null
    }
}

