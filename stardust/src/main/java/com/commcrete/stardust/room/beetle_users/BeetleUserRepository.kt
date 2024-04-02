package com.commcrete.stardust.room.beetle_users

import com.commcrete.stardust.room.beetle_users.BittelUser
import com.commcrete.stardust.room.beetle_users.BittelUserDoa

class BittelUserRepository (private val bittelUserDoa: BittelUserDoa) {
    suspend fun readBittelUsers() : List<BittelUser> {
        return bittelUserDoa.getAllBittelUsers()
    }

    suspend fun addBittelUser(bittelUser: BittelUser) {
        bittelUserDoa.addBittelUser(bittelUser)
    }

    suspend fun addAllBittelUsers(bittelUsers: List<BittelUser>) {
        bittelUserDoa.addAllBittelUsers(bittelUsers)
    }

    suspend fun clearData () : Boolean {
        bittelUserDoa.clearData()
        return true
    }
}