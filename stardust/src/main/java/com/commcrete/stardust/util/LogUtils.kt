package com.commcrete.stardust.util

import android.content.Context
import android.location.Location
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.request_objects.Details
import com.commcrete.stardust.request_objects.DetailsData
import com.commcrete.stardust.request_objects.LogEntry
import com.commcrete.stardust.request_objects.Logs
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.request_objects.toJson
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.stardust.model.StardustLogPackage
import com.commcrete.stardust.stardust.model.StardustLogParser
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.util.Date

object LogUtils {

    val mutableLogList : MutableLiveData<MutableList<StardustLogPackage>> = MutableLiveData(
        mutableListOf()
    )
    var index = 0


    private fun shareLogsFile () {

    }

    fun pullBittelLogs (numOfLogs : Int = 4096, context: Context) {
        mutableLogList.value = mutableListOf()
        index = 0
        val clientConnection: ClientConnection = DataManager.getClientConnection(context)
        SharedPreferencesUtil.getAppUser(context)?.let {
            val src = it.appId
            val dst = it.bittelId
            if(src != null && dst != null) {
                val logToBytes = numOfLogs.intToByteArray().reversedArray()
                val logSizeData = StardustPackageUtils.byteArrayToIntArray(logToBytes)
                val logPackage = StardustPackageUtils.getStardustPackage(
                    context = context,
                    source = src,
                    destenation = dst,
                    stardustOpCode = StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS,
                    data = logSizeData)
                clientConnection.addMessageToQueue(logPackage)
            }
        }
    }

    fun appendToList (bittelLogPackage: StardustLogPackage) {
        index ++
        Scopes.getMainCoroutine().launch {
            val list = mutableLogList.value
            list?.add(bittelLogPackage)
            list?.let {
                mutableLogList.value = it
            }
        }
    }

    fun uploadLogs (context: Context) {
        SharedPreferencesUtil.getAppUser(context)?.appId?.let {appId ->
            val logList : MutableList<LogEntry> = mutableListOf()
            mutableLogList.value?.let {
                for (log in it) {
                    val ts = log.gpsTime
                    val logEntry = LogEntry(
                        from = "Bittel",
                        user = User(bittelId = "", appId = appId),
                        logLevel = log.type?.name ?: StardustLogParser.PARSE_DATA_TYPE.INFO.name,
                        event = "default",
                        message = "",
                        details = Details(
                            location = listOf(0.0, 0.0, 0.0),
                            data = DetailsData(dstChannel = "", bytes = log.data.toHex())
                        ),
                        ts = 1709192258
                    )
                    logList.add(logEntry)
                }
            }



            val logs = Logs(logs = logList)

            val json = logs.toJson()
        }
    }
}