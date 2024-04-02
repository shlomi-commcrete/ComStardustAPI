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
import com.commcrete.stardust.room.logs.LOG_LEVEL
import com.commcrete.stardust.room.logs.LogObject
import com.commcrete.stardust.room.logs.LogsDatabase
import com.commcrete.stardust.room.logs.LogsRepository
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

    fun exportLogs(context: Context) {
        Scopes.getDefaultCoroutine().launch {
            getLogsFromDB(context)
        }
    }

    private fun getLogFile (context: Context) {
        val logList = getLogsFromDB(context)

    }

    private fun getLogsFromDB (context: Context) : List<LogObject> {
        return LogsRepository(LogsDatabase.getDatabase(context).logsDao()).getAllLogs()
    }

    private fun shareLogsFile () {

    }

//    private fun saveLogToFile () : File {
//
//    }

    private fun getLogsStringFromList (logList : List<LogObject>) :String {
        val gson = Gson()
        // Use TypeToken to get the correct Type for List<LogObject>
        val type = object : TypeToken<List<LogObject>>() {}.type
        return gson.toJson(logList, type)
    }

    fun saveLog (logObject: LogObject, context: Context) {
        Scopes.getDefaultCoroutine().launch {
            LogsRepository(LogsDatabase.getDatabase(context).logsDao()).addLog(logObject)
        }
    }

    fun getLogObject (src : String, dst : String, event : String, location : Location) : LogObject {
        val userObj = JsonObject()
        userObj.addProperty("appId", src)
        userObj.addProperty("bittelId", "")

        val detailsObj = JsonObject()
        val dataObj = JsonObject()
        val locationObj = JsonObject()
        dataObj.addProperty("src", src)
        dataObj.addProperty("dst", dst)

        detailsObj.add("data", dataObj)
        locationObj.addProperty("latitude", location.latitude)
        locationObj.addProperty("longitude", location.longitude)
        locationObj.addProperty("altitude", location.altitude)

        detailsObj.add("location", locationObj)

        val ts = Date().time
        val logObject = LogObject (user = userObj.toString(), logLevel = LOG_LEVEL.INFO.type,
            event = event, details = detailsObj.toString(), createdAt = ts, updatedAt = ts)

        return logObject
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
                    source = src , destenation = dst, stardustOpCode = StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS,
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