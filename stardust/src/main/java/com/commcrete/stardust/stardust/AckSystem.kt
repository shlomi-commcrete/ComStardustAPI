package com.commcrete.stardust.stardust

import android.os.Handler
import android.os.Looper
import com.commcrete.stardust.stardust.model.StardustPackage

class AckSystem (val stardustPackage: StardustPackage, private val ackSystemNotify: AckSystemNotify? = null) {

    var isAwaitingAck : Boolean = false
    var retryCounter = 0
    var delayTS = 1500L

    companion object{
        const val MAX_RETRY_COUNTER = 3
        const val DELAY_TS_HR = 1500L
        const val DELAY_TS_LR = 5000L
    }


    fun start () {
        resetTimer()
    }

    private val ackTimeoutHandler : Handler = Handler(Looper.getMainLooper())
    private val ackTimeoutRunnable : Runnable = kotlinx.coroutines.Runnable {
        retryCounter++
        if(retryCounter >= MAX_RETRY_COUNTER){
            clearTimer()
            notifyFailure()
        }else {
            resetTimer()
        }

    }

    private fun notifyFailure () {
        ackSystemNotify?.onFailure()
    }

    fun notifySuccess () {
        clearTimer()
        ackSystemNotify?.onSuccess()
    }

    private fun resetTimer() {
        ackTimeoutHandler.removeCallbacks(ackTimeoutRunnable)
        ackTimeoutHandler.removeCallbacksAndMessages(null)
        ackTimeoutHandler.postDelayed(ackTimeoutRunnable, delayTS)
    }

    private fun clearTimer(){
        try {
            ackTimeoutHandler.removeCallbacks(ackTimeoutRunnable)
            ackTimeoutHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()

        }
    }

    interface AckSystemNotify {
        fun onFailure ()
        fun onSuccess ()
    }
}