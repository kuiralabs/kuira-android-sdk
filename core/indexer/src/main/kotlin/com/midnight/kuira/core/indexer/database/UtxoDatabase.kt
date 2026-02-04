package com.midnight.kuira.core.indexer.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for UTXO storage.
 *
 * Stores unshielded UTXOs and dust tokens for local balance calculation.
 * Eventually will also store shielded UTXOs (future).
 */
@Database(
    entities = [
        UnshieldedUtxoEntity::class,
        DustTokenEntity::class
    ],
    version = 7,  // Bumped to 7: Added spent_by_local_tx column for self-healing state
    exportSchema = false
)
abstract class UtxoDatabase : RoomDatabase() {
    /**
     * DAO for unshielded UTXO operations.
     */
    abstract fun unshieldedUtxoDao(): UnshieldedUtxoDao

    /**
     * DAO for dust token operations.
     */
    abstract fun dustDao(): DustDao

    companion object {
        private const val DATABASE_NAME = "kuira-utxo-database"

        @Volatile
        private var INSTANCE: UtxoDatabase? = null

        /**
         * Migration from version 6 to 7: Add spent_by_local_tx column.
         *
         * This column enables self-healing UTXO state:
         * - If spent_by_local_tx=1: Our transaction spent it, don't restore during sync
         * - If spent_by_local_tx=0: Can be restored if indexer shows it's available
         *
         * Default is 0 (false) for existing UTXOs, which means they CAN be healed
         * during the next sync if their state was incorrect.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE unshielded_utxos ADD COLUMN spent_by_local_tx INTEGER NOT NULL DEFAULT 0"
                )
                android.util.Log.i("UtxoDatabase", "Migration 6->7: Added spent_by_local_tx column for self-healing")
            }
        }

        /**
         * Get database instance (singleton).
         *
         * Thread-safe lazy initialization.
         */
        fun getInstance(context: Context): UtxoDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): UtxoDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                UtxoDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_6_7)  // Proper migration for self-healing column
                .fallbackToDestructiveMigration() // Fallback for older versions
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // CRITICAL: When database is wiped by destructive migration,
                        // we must also clear the sync state. Otherwise the subscription
                        // will resume from the last saved transaction ID instead of
                        // replaying all history to repopulate the UTXO table.
                        //
                        // This is done asynchronously via a coroutine since we can't
                        // call suspend functions directly from the callback.
                        android.util.Log.w("UtxoDatabase", "Destructive migration detected - sync state needs to be cleared!")
                        // Note: SyncStateManager clearing must be done by the caller
                        // who has access to the Context for DataStore.
                        // Set a flag that the app can check on startup.
                        context.getSharedPreferences("utxo_db_flags", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("needs_full_resync", true)
                            .apply()
                    }
                })
                .enableMultiInstanceInvalidation() // Allow multiple processes to share database
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // Enable WAL for better concurrency
                .build()
        }

        /**
         * Create in-memory database for testing.
         *
         * Data is lost when database is closed.
         */
        fun createInMemoryDatabase(context: Context): UtxoDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                UtxoDatabase::class.java
            )
                .allowMainThreadQueries() // Only for testing!
                .build()
        }
    }
}
