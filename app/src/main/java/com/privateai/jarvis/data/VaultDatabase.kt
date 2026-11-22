package com.privateai.jarvis.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.privateai.jarvis.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Dao
interface VaultDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertNote(note: NoteEntity)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeChat(): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertChat(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearChat()

    @Query("SELECT * FROM action_logs ORDER BY timestamp DESC")
    fun observeLogs(): Flow<List<ActionLogEntity>>

    @Insert
    suspend fun insertLog(log: ActionLogEntity)

    @Query("SELECT * FROM pinned_apps ORDER BY pinnedAt ASC")
    fun observePinnedApps(): Flow<List<PinnedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun pinApp(app: PinnedAppEntity)

    @Query("DELETE FROM pinned_apps WHERE packageName = :pkg")
    suspend fun unpinApp(pkg: String)
}

@Database(
    entities = [
        NoteEntity::class,
        ChatMessageEntity::class,
        ActionLogEntity::class,
        PinnedAppEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getInstance(context: Context, securityManager: SecurityManager): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, securityManager).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context, securityManager: SecurityManager): VaultDatabase {
            val passphrase = securityManager.getOrCreateDatabasePassphrase()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                VaultDatabase::class.java,
                "jarvis_vault.db"
            )
                .openHelperFactory(factory)
                .build()
        }
    }
}
