package com.commcrete.stardust.util.audio

import com.commcrete.stardust.util.StardustLog
import com.commcrete.stardust.ai.codec.filter.PyTorchInitGate
import com.ustadmobile.codec2.Codec2

/**
 * Whether a PTT codec can run in this process. Both are native libraries, and Android binds a native
 * library to the first classloader that loads it: when the plugin is reinstalled into a running ATAK,
 * the new copy can be refused either one until ATAK restarts.
 */
object CodecAvailability {

    private const val TAG = "CodecAvailability"

    @Volatile private var codec2Available: Boolean? = null

    /** A null [codeType] is sent as AI, so it is checked as AI. */
    fun isAvailable(codeType: RecorderUtils.CODE_TYPE?): Boolean = when (codeType) {
        RecorderUtils.CODE_TYPE.CODEC2 -> isCodec2Available()
        RecorderUtils.CODE_TYPE.AI, null -> PyTorchInitGate.isPrimaryInitializer()
    }

    /** Forces Codec2's static initializer, which loads its native libraries; the answer cannot change. */
    private fun isCodec2Available(): Boolean = codec2Available ?: synchronized(this) {
        codec2Available ?: runCatching { Class.forName(Codec2::class.java.name, true, Codec2::class.java.classLoader) }
            .onFailure { StardustLog.w(TAG, "Classic codec native libraries unavailable in this process", it) }
            .isSuccess
            .also { codec2Available = it }
    }
}
