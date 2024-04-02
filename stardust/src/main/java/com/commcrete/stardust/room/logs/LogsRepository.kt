package com.commcrete.stardust.room.logs

import com.commcrete.stardust.room.logs.LogObject
import com.commcrete.stardust.room.logs.LogsDao

class LogsRepository (private val logsDao: LogsDao) {

    fun getAllLogs () = logsDao.getAlllogs()

    suspend fun addLog (logObject: LogObject) {
        logsDao.addLog(logObject)
    }

}