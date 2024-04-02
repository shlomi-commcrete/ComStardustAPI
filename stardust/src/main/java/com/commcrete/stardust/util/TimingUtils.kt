package com.commcrete.stardust.util

import android.os.Handler
import android.os.Looper

class TimingUtils(private val delay : Long,private val runnable: () -> Unit = {} ) {

    private val handler : Handler = Handler(Looper.getMainLooper())
    private val mRunnable : Runnable = kotlinx.coroutines.Runnable { runnable() }

    fun resetTimer(){
        handler.removeCallbacks(mRunnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(mRunnable, delay)
    }

}