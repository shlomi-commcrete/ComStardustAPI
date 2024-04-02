package com.commcrete.stardust.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.Scopes
import com.commcrete.stardust.util.SharedPreferencesUtil
import com.commcrete.stardust.stardust.model.StardustPackage
import kotlinx.coroutines.launch

internal class PollingUtils (private val context: Context){

    var interval : Int = 0
    var isRunning = false

    private var responseResults : MutableMap<String, PollingResponse?> = mutableMapOf()
    val publishResults : MutableLiveData<MutableMap<String, PollingResponse?>> = MutableLiveData()
    val nextUser : MutableLiveData<String> = MutableLiveData()
    val timeToPullSeconds : MutableLiveData<Int> = MutableLiveData()

    private val handler : Handler = Handler(Looper.getMainLooper())
    private val runnable : Runnable = kotlinx.coroutines.Runnable {

        val nextUser = getNexUserId()
        if(nextUser == null) {
            clearFailTimer()
            resetLoopTimer()
            resetTimerLoopTimer()
        }else {
            responseResults[nextUser] = PollingResponse.FAILURE
            resetFailTimer()
            pullUserLocation(context)
            clearTimerLoopTimer()
        }
        syncDataToPublish()
    }

    private val timerHandler : Handler = Handler(Looper.getMainLooper())
    private val timerRunnable : Runnable = kotlinx.coroutines.Runnable {
        Scopes.getMainCoroutine().launch {
            val time = timeToPullSeconds.value?.minus(1)
            if(time != null && time >= 0) {
                timeToPullSeconds.value = time
            }

        }
        resetTimerLoopTimer()
    }

    private val loopHandler : Handler = Handler(Looper.getMainLooper())
    private val loopRunnable : Runnable = kotlinx.coroutines.Runnable {

        resetUserList()
        pullUserLocation(context)
        resetFailTimer()
        Scopes.getMainCoroutine().launch {
            timeToPullSeconds.value = interval
        }
    }


    fun startPolling(interval :Int, userIds : List<String>, context: Context) {

        this.interval = interval
        Scopes.getMainCoroutine().launch {
            timeToPullSeconds.value = interval
        }
        isRunning = true
        for (id in userIds) {
            responseResults[id] = null
        }
        pullUserLocation(context)
        resetFailTimer()
    }

    fun stopPolling() {

        isRunning = false
        responseResults.clear()
        clearFailTimer()
        clearLoopTimer()
        clearTimerLoopTimer()
    }

    fun handleResponse(message: StardustPackage) {
        if(responseResults.containsKey(message.getSourceAsString())){
            responseResults[message.getSourceAsString()] = PollingResponse.SUCCESS
        }
        syncDataToPublish()
        resetFailTimer()
    }

    private fun pullUserLocation (context: Context) {
        val nextUser = getNexUserId()
        DataManager.getClientConnection(context)?.let {client ->
            SharedPreferencesUtil.getAppUser(context)?.appId?.let { myId ->
                nextUser?.let { nextUser ->
                    client.sendMessage(
                        StardustPackageUtils.getStardustPackage(source = myId, destenation = nextUser , stardustOpCode = StardustPackageUtils.StardustOpCode.REQUEST_LOCATION)
                    )
                }
            }
        }
    }

    private fun getNexUserId () : String? {
        for (users in responseResults) {
            if(users.value == null) {
                Scopes.getMainCoroutine().launch {
                    nextUser.value = users.key
                }
                return users.key
            }
        }
        return null
    }

    //reset Timers

    private fun resetLoopTimer() {
        loopHandler.removeCallbacks(loopRunnable)
        loopHandler.removeCallbacksAndMessages(null)
        loopHandler.postDelayed(loopRunnable, interval.times(1000).toLong())
    }



    private fun resetTimerLoopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.removeCallbacksAndMessages(null)
        timerHandler.postDelayed(timerRunnable, 1000)
    }

    private fun resetFailTimer() {
        handler.removeCallbacks(runnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, Companion.POLLING_TIMEOUT)
    }

    //clear Timers

    private fun clearTimerLoopTimer(){
        try {
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun clearLoopTimer(){
        try {
            loopHandler.removeCallbacks(loopRunnable)
            loopHandler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun clearFailTimer(){
        try {
            handler.removeCallbacks(runnable)
            handler.removeCallbacksAndMessages(null)
        }catch (e : Exception) {
            e.printStackTrace()
        }
    }

    private fun resetUserList () {
        for (users in responseResults) {
            responseResults[users.key] = null
        }
    }

    private fun syncDataToPublish () {
        Scopes.getMainCoroutine().launch {
            publishResults.value = responseResults
        }
    }

    enum class PollingResponse {
        SUCCESS,
        FAILURE
    }

    companion object {
        const val POLLING_TIMEOUT = 3000L
        const val TAG = "PollingUtils"
    }
}