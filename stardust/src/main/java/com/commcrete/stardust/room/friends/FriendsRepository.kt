package com.commcrete.stardust.room.friends

import com.commcrete.stardust.request_objects.model.user_list.User
import com.commcrete.stardust.room.friends.FriendsDao

class FriendsRepository (private val friendsDao: FriendsDao) {

    val readAllChats : List<User> = friendsDao.getAllFriends()

    suspend fun addAllContacts(users: List<User>) {
        friendsDao.addAllFriends(users)
    }

    suspend fun clearData () : Boolean {
        friendsDao.clearData()
        return true
    }
}