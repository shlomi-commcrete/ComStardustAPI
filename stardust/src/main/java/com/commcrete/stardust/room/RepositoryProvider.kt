package com.commcrete.stardust.room

import android.content.Context
import com.commcrete.stardust.room.messages.MessagesRepository
import com.commcrete.stardust.room.new_db.AppDatabase
import com.commcrete.stardust.room.new_db.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object RepositoryProvider {

    @Volatile
    private var appRepository: AppRepository? = null

    object AppScopes {
        val applicationScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    /**
     * Returns the singleton [AppRepository] backed by the unified [AppDatabase]
     * that combines chats, contacts and messages as tables.
     *
     * On first call the repository automatically migrates all data from the
     * legacy chats / contacts / messages databases and removes those files.
     */
    fun appRepository(context: Context): AppRepository {
        return appRepository ?: synchronized(this) {
            appRepository ?: run {
                val db = AppDatabase.getDatabase(context)
                AppRepository(
                    chatsDao = db.chatsDao(),
                    contactsDao = db.contactsDao(),
                    messagesDao = db.messagesDao(),
                    scope = AppScopes.applicationScope,
                ).also { repo ->
                    appRepository = repo
                    // Run the one-time migration in the background.
                    // The flag inside the function guarantees it executes only once.
                    AppScopes.applicationScope.launch {
                        repo.migrateFromLegacyDatabases(context)
                    }
                }
            }
        }
    }
}