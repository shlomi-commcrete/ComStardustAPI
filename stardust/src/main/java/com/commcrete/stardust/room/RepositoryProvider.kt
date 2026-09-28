package com.commcrete.stardust.room

import com.commcrete.stardust.room.new_db.AppDatabase
import com.commcrete.stardust.room.new_db.AppRepository
import com.commcrete.stardust.util.DataManager
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
     * legacy chats / contacts / messages databases and removes those files,
     * then re-points any message row whose media [StardustStorage] has moved.
     */
    fun appRepository(): AppRepository {
        return appRepository ?: synchronized(this) {
            appRepository ?: run {
                val db = AppDatabase.getDatabase()
                AppRepository(
                    chatsDao = db.appChatsDao(),
                    contactsDao = db.appContactsDao(),
                    messagesDao = db.appMessagesDao(),
                    identityLogDao = db.appContactIdentityLogDao(),
                ).also { repo ->
                    appRepository = repo
                    // Run the one-time migration in the background.
                    // The flag inside the function guarantees it executes only once.
                    AppScopes.applicationScope.launch {
                        repo.migrateFromLegacyDatabases()
                        // After it, not beside it: the migration inserts rows
                        // carrying legacy paths, and this is what re-points any
                        // of them that StardustStorage has since moved.
                        repo.rewriteRelocatedMediaPaths()
                    }
                }
            }
        }
    }
}