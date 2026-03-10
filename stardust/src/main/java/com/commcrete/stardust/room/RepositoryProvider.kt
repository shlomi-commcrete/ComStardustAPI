package com.commcrete.stardust.room

import android.content.Context
import com.commcrete.stardust.room.messages.MessagesDatabase
import com.commcrete.stardust.room.messages.MessagesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object RepositoryProvider {

    @Volatile
    private var messagesRepository: MessagesRepository? = null

    object AppScopes {
        val applicationScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    fun messagesRepository(context: Context): MessagesRepository {
        return messagesRepository ?: synchronized(this) {
            messagesRepository ?: MessagesRepository(
                MessagesDatabase.getDatabase(context).messagesDao(),
                AppScopes.applicationScope
            ).also { messagesRepository = it }
        }
    }
}