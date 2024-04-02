package com.commcrete.stardust.room.logs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LogsDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addLog(chatContact: LogObject)

    @Query("SELECT * FROM logs_table ORDER BY createdAt ASC")
    fun getAlllogs() : List<LogObject>

    @Query("DELETE FROM logs_table")
    fun clearData()
}