package com.commcrete.stardust.room.beetle_users

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.commcrete.stardust.room.beetle_users.BittelUser

@Dao
interface BittelUserDoa {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addBittelUser(bittelUser: BittelUser)

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addAllBittelUsers(bittelUsers: List<BittelUser>)

    @Query("SELECT * FROM bittel_user_table ORDER BY id ASC")
    fun getAllBittelUsers() : List<BittelUser>
    @Query("DELETE FROM bittel_user_table")
    fun clearData()
}