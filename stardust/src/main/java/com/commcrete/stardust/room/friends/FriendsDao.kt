package com.commcrete.stardust.room.friends

import androidx.room.*
import com.commcrete.stardust.request_objects.model.user_list.User

@Dao
interface FriendsDao {

    @Insert(onConflict =  OnConflictStrategy.REPLACE)
    suspend fun addAllFriends(chatContact: List<User>)

    @Query("SELECT * FROM friends_table")
    fun getAllFriends() : List<User>

    @Query("DELETE FROM friends_table")
    fun clearData()


}