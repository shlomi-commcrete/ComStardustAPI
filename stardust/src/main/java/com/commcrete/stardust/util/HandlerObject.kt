package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper

class HandlerObject (val timeout : Long, val function :() -> Unit ) {
    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = Runnable {
        function()
        resetTimer()
    }

    fun resetTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, timeout)
    }

    fun removeTimer() {
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }
}