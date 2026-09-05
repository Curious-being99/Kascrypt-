package com.example.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vault_entries")
data class VaultEntryEntity(
    @PrimaryKey val id: String,
    val ciphertext: ByteArray,
    val signature: ByteArray
)

@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "kfs_broadcast_records")
data class KfsBroadcastRecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val manifestTxId: String,
    val merkleRoot: String,
    val chunkTxIdsJson: String,
    val totalChunks: Int,
    val totalBytes: Int,
    val totalFeeSompis: Long,
    val timestamp: Long,
    val status: String,
    val errorMessage: String? = null
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_entries")
    suspend fun getAllEntries(): List<VaultEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: VaultEntryEntity)

    @Query("DELETE FROM vault_entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM vault_entries")
    suspend fun clearAllEntries()

    @Query("SELECT value FROM app_config WHERE key = :key LIMIT 1")
    suspend fun getConfig(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: AppConfigEntity)

    @Query("DELETE FROM app_config WHERE key = :key")
    suspend fun deleteConfig(key: String)
}

@Dao
interface KfsDao {
    @Query("SELECT * FROM kfs_broadcast_records WHERE status IN ('CONFIRMED', 'RESTORED', 'ON_CHAIN_SYNCED', 'SAVED') ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<KfsBroadcastRecordEntity>>

    @Query("SELECT * FROM kfs_broadcast_records WHERE id = :id LIMIT 1")
    suspend fun getRecordById(id: String): KfsBroadcastRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: KfsBroadcastRecordEntity)

    @Query("DELETE FROM kfs_broadcast_records WHERE id = :id")
    suspend fun deleteRecord(id: String)

    @Query("DELETE FROM kfs_broadcast_records WHERE status NOT IN ('CONFIRMED', 'RESTORED', 'ON_CHAIN_SYNCED', 'SAVED')")
    suspend fun purgeFailedRecords()

    @Query("DELETE FROM kfs_broadcast_records")
    suspend fun clearAllRecords()
}

@Database(
    entities = [VaultEntryEntity::class, AppConfigEntity::class, KfsBroadcastRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun kfsDao(): KfsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `kfs_broadcast_records` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `manifestTxId` TEXT NOT NULL,
                        `merkleRoot` TEXT NOT NULL,
                        `chunkTxIdsJson` TEXT NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `totalFeeSompis` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

