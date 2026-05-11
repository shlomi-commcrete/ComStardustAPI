package com.commcrete.stardust.util

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.commcrete.stardust.ble.ClientConnection
import com.commcrete.stardust.request_objects.Details
import com.commcrete.stardust.request_objects.DetailsData
import com.commcrete.stardust.request_objects.LogEntry
import com.commcrete.stardust.request_objects.Logs
import com.commcrete.stardust.request_objects.User
import com.commcrete.stardust.request_objects.toJson
import com.commcrete.stardust.stardust.StardustInitConnectionHandler.requireSrcDst
import com.commcrete.stardust.stardust.StardustPackageUtils
import com.commcrete.stardust.stardust.model.intToByteArray
import com.commcrete.stardust.stardust.model.toHex
import com.commcrete.stardust.stardust.model.StardustLogPackage
import com.commcrete.stardust.stardust.model.StardustLogParser
import kotlinx.coroutines.launch

object LogUtils {

    val mutableLogList : MutableLiveData<MutableList<StardustLogPackage>> = MutableLiveData(
        mutableListOf()
    )
    var index = 0


    fun pullBittelLogs (numOfLogs : Int = 4096) {
        mutableLogList.value = mutableListOf()
        index = 0
        val clientConnection: ClientConnection = DataManager.getClientConnection()

        val (src, dst) = requireSrcDst() ?: return

        val logToBytes = numOfLogs.intToByteArray().reversedArray()
        val logSizeData = StardustPackageUtils.byteArrayToIntArray(logToBytes)
        val logPackage = StardustPackageUtils.getStardustPackage(
            source = src,
            destination = dst,
            stardustOpCode = StardustPackageUtils.StardustOpCode.GET_BITTEL_LOGS,
            data = logSizeData)
        clientConnection.addMessageToQueue(logPackage)
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
        SharedPreferencesUtil.getAppUser()?.appId?.let { appId ->

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