package com.turnocontrol.data

import androidx.room.*

/*
 * Local synchronization state (Room schema 5). These tables are deliberately separate from the calendar tables:
 *  - no foreign keys to user data, so restore (delete + reinsert of every user table) never cascades into them;
 *  - never part of BackupSnapshot: device id, cursor, outbox and conflicts are not exported as user data;
 *  - empty until an account is linked in a future phase. Upgrading never enqueues historical records.
 * Payload columns hold the canonical synchronized text (see domain/sync/SyncCodecs): calendar state only, no
 * medical or free-text fields.
 */

/** One row (id = 1). [deviceId] is a random UUID created once and kept across restarts and restores. */
@Entity data class SyncState(
    @PrimaryKey val id: Long = 1, val deviceId: String, val accountId: String?, val cursor: Long, val nextOperation: Long,
    /** Set when a restore replaced the data of a linked database: synchronization waits for an explicit review. */
    val restoreReviewRequired: Boolean, val linkedAt: String?,
)

/** Server version last known for a record (a deleted one is a tombstone). */
@Entity(primaryKeys = ["entity", "recordKey"])
data class SyncRecordState(val entity: String, val recordKey: String, val serverVersion: Long, val deleted: Boolean, val payload: String?)

/** Outgoing change. [position] keeps the queue order; [operationId] (UUID) is unique and never reused. */
@Entity(indices = [Index(value = ["operationId"], unique = true), Index(value = ["entity", "recordKey"])])
data class SyncOutbox(
    @PrimaryKey(autoGenerate = true) val position: Long = 0, val operationId: String, val entity: String, val recordKey: String,
    val op: String, val payload: String?, val baseVersion: Long?, val afterPredecessor: Boolean, val state: String, val attempts: Int,
)

/** Unresolved conflict with both candidates. */
@Entity(tableName = "SyncConflict", primaryKeys = ["entity", "recordKey"])
data class SyncConflictRow(
    val entity: String, val recordKey: String, val kind: String, val localPayload: String?,
    val remoteVersion: Long?, val remoteSeq: Long?, val remoteDeleted: Boolean?, val remoteDataFormat: Int?, val remotePayload: String?, val remoteOperationId: String?,
    val detail: String?,
)

/**
 * A remote record that is not applied. [evaluatedFormat] / [evaluatedSchema] record which build last judged it, so it
 * is re-evaluated once after an update instead of on every run.
 */
@Entity(primaryKeys = ["entity", "recordKey"])
data class SyncQuarantine(
    val entity: String, val recordKey: String, val version: Long, val seq: Long, val deleted: Boolean, val dataFormat: Int,
    val payload: String?, val operationId: String, val reason: String, val evaluatedFormat: Int, val evaluatedSchema: Int,
)

/** Losing side of a resolved conflict, kept for recovery (retention policy: 90 days). */
@Entity data class SyncRecovered(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val entity: String, val recordKey: String, val source: String, val payload: String?, val discardedOn: String,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM SyncState WHERE id = 1") suspend fun state(): SyncState?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertState(state: SyncState)
    @Update suspend fun updateState(state: SyncState)

    @Query("SELECT * FROM SyncRecordState ORDER BY entity, recordKey") suspend fun recordStates(): List<SyncRecordState>
    @Query("SELECT * FROM SyncRecordState WHERE entity = :entity AND recordKey = :key") suspend fun recordState(entity: String, key: String): SyncRecordState?
    @Query("DELETE FROM SyncRecordState") suspend fun deleteRecordStates()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRecordStates(rows: List<SyncRecordState>)

    @Query("SELECT * FROM SyncOutbox ORDER BY position") suspend fun outbox(): List<SyncOutbox>
    @Query("SELECT * FROM SyncOutbox WHERE entity = :entity AND recordKey = :key ORDER BY position") suspend fun outboxFor(entity: String, key: String): List<SyncOutbox>
    @Query("DELETE FROM SyncOutbox WHERE entity = :entity AND recordKey = :key AND state = 'PENDING'") suspend fun deletePendingFor(entity: String, key: String)
    @Query("DELETE FROM SyncOutbox") suspend fun deleteOutbox()
    /** ABORT: a duplicated operation id fails the whole transaction instead of creating a second operation. */
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOutbox(rows: List<SyncOutbox>)

    @Query("SELECT * FROM SyncConflict ORDER BY entity, recordKey") suspend fun conflicts(): List<SyncConflictRow>
    @Query("SELECT count(*) FROM SyncConflict WHERE entity = :entity AND recordKey = :key") suspend fun conflictCount(entity: String, key: String): Int
    @Query("DELETE FROM SyncConflict") suspend fun deleteConflicts()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertConflicts(rows: List<SyncConflictRow>)

    @Query("SELECT * FROM SyncQuarantine ORDER BY entity, recordKey") suspend fun quarantine(): List<SyncQuarantine>
    @Query("DELETE FROM SyncQuarantine") suspend fun deleteQuarantine()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertQuarantine(rows: List<SyncQuarantine>)

    @Query("SELECT * FROM SyncRecovered ORDER BY id") suspend fun recovered(): List<SyncRecovered>
    @Query("DELETE FROM SyncRecovered") suspend fun deleteRecovered()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRecovered(rows: List<SyncRecovered>)

    /**
     * Called inside the restore transaction. The restored user data was never seen by the server in this form, so
     * pending operations (describing data that no longer exists) and conflicts are dropped, and synchronization of a
     * linked database waits for an explicit review: a restore never uploads or overwrites the remote account on its
     * own. The device id, cursor and server knowledge are kept, so the backup cannot impersonate another device.
     */
    @Transaction suspend fun onUserDataReplaced() {
        val state = state() ?: return
        deleteOutbox()
        deleteConflicts()
        if (state.accountId != null) updateState(state.copy(restoreReviewRequired = true))
    }
}

/** Every migration, in order. Production and tests use the same list; there is no destructive fallback. */
val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

/** 4 -> 5: only creates the empty synchronization tables; no user row is read, changed or enqueued. */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        MIGRATION_4_5_SQL.forEach(db::execSQL)
    }
}
private val MIGRATION_4_5_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `SyncState` (`id` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, `accountId` TEXT, `cursor` INTEGER NOT NULL, `nextOperation` INTEGER NOT NULL, `restoreReviewRequired` INTEGER NOT NULL, `linkedAt` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `SyncRecordState` (`entity` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `serverVersion` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `payload` TEXT, PRIMARY KEY(`entity`, `recordKey`))",
    "CREATE TABLE IF NOT EXISTS `SyncOutbox` (`position` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operationId` TEXT NOT NULL, `entity` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `op` TEXT NOT NULL, `payload` TEXT, `baseVersion` INTEGER, `afterPredecessor` INTEGER NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_SyncOutbox_operationId` ON `SyncOutbox` (`operationId`)",
    "CREATE INDEX IF NOT EXISTS `index_SyncOutbox_entity_recordKey` ON `SyncOutbox` (`entity`, `recordKey`)",
    "CREATE TABLE IF NOT EXISTS `SyncConflict` (`entity` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `localPayload` TEXT, `remoteVersion` INTEGER, `remoteSeq` INTEGER, `remoteDeleted` INTEGER, `remoteDataFormat` INTEGER, `remotePayload` TEXT, `remoteOperationId` TEXT, `detail` TEXT, PRIMARY KEY(`entity`, `recordKey`))",
    "CREATE TABLE IF NOT EXISTS `SyncQuarantine` (`entity` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `version` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `dataFormat` INTEGER NOT NULL, `payload` TEXT, `operationId` TEXT NOT NULL, `reason` TEXT NOT NULL, `evaluatedFormat` INTEGER NOT NULL, `evaluatedSchema` INTEGER NOT NULL, PRIMARY KEY(`entity`, `recordKey`))",
    "CREATE TABLE IF NOT EXISTS `SyncRecovered` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `entity` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `source` TEXT NOT NULL, `payload` TEXT, `discardedOn` TEXT NOT NULL)",
)
