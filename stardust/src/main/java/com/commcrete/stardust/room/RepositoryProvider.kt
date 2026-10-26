package com.commcrete.stardust.room

import com.commcrete.stardust.room.new_db.AppDatabase
import com.commcrete.stardust.room.new_db.AppRepository
import com.commcrete.stardust.util.DataManager
import com.commcrete.stardust.util.StardustLog
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
     * re-points any message row whose media [StardustStorage] has moved, and
     * settles any transfer the previous run of the app left in flight.
     */
    fun appRepository(): AppRepository {
        return appRepository ?: synchronized(this) {
            appRepository ?: run {
                // Read before anything else this process can write, and passed to the sweep
                // below: every in-flight row older than this was left behind by a previous
                // run, however long the work in between ends up taking.
                val builtAtMs = System.currentTimeMillis()
                val db = AppDatabase.getDatabase()
                AppRepository(
                    chatsDao = db.appChatsDao(),
                    contactsDao = db.appContactsDao(),
                    messagesDao = db.appMessagesDao(),
                    identityLogDao = db.appContactIdentityLogDao(),
                    database = db,
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
                        // Repairs what an older build's contact insert left: one contact with
                        // two private chats. Idempotent; a failure leaves the duplicate as it
                        // was and must not stop the sweep after it.
                        runCatching { repo.mergeDuplicatePrivateChats() }
                            .onFailure { StardustLog.w("RepositoryProvider", "Chat merge failed: ${it.javaClass.simpleName}") }
                        // Repairs what a swap before the applyOperations fix left: contacts with
                        // no id, invisible with their chats. Idempotent, and on its own like the
                        // merge so a failure stops nothing after it.
                        runCatching { repo.restoreContactsLeftWithoutIds() }
                            .onFailure { StardustLog.w("RepositoryProvider", "Contact id repair failed: ${it.javaClass.simpleName}") }
                        // Last, so it sees the migrated rows too: settles the
                        // transfers the previous run died in the middle of, which
                        // nothing else can finish for them.
                        repo.sweepStaleInFlight(builtAtMs)
                    }
                }
            }
        }
    }
}