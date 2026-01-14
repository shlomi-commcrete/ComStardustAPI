package com.commcrete.stardust.audio

import android.util.Log
import com.commcrete.aiaudio.codecs.WavTokenizerDecoder
import com.commcrete.stardust.ai.codec.PttReceiveManager
import com.commcrete.stardust.stardust.model.StardustPackage
import com.commcrete.stardust.util.GroupsUtils
import com.commcrete.stardust.util.audio.PlayerUtils

class AIDecoder {

    fun decode (stardustPackage: StardustPackage, onPcmReady: ((ShortArray) -> Unit)? = null) {
        var from = stardustPackage.getSourceAsString()
        if(GroupsUtils.isGroup(from)) {
            from = stardustPackage.getDestAsString()
        }
        stardustPackage.data?.let { dataArray -> //dataArray = Array<Int>
            val byteArray = intArrayToByteArray(dataArray.toMutableList())
            Log.d("PlayerUtils", "Received PTT AI data size: ${byteArray.size}")
            if (byteArray.size > 1) {
                val model = byteArray.copyOfRange(0, 1)
                val selectedModule = getModel(model[0].toInt())
                val withoutFirstByte = byteArray.copyOfRange(1, byteArray.size)
                Log.d("PlayerUtils", "Received PTT AI data size withoutFirstByte: ${withoutFirstByte.size}")
//                PttReceiveManager.addNewData(byteArray, from, stardustPackage.getSourceAsString(), selectedModule)
                PttReceiveManager.addNewData(
                    PttReceiveManager.AIDecodeData(
                        byteArray, from, stardustPackage.getSourceAsString(), selectedModule,onPcmReady))
            }
        }
    }

    private fun intArrayToByteArray(intArray: MutableList<Int>): ByteArray {
        val byteArray = ByteArray(intArray.size)
        for (i in intArray.indices) {
            byteArray[i] = intArray[i].toByte()
        }
        return byteArray
    }

    private fun getModel(modelValue: Int): WavTokenizerDecoder.ModelType {
        return WavTokenizerDecoder.ModelType.fromInt(modelValue)
            ?: WavTokenizerDecoder.ModelType.General
    }
}