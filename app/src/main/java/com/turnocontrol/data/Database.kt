package com.turnocontrol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.turnocontrol.domain.DataFormat

@Entity data class Profile(@PrimaryKey val id: Long = 1, val displayName: String, val alias: String, val zoneId: String)
@Entity(foreignKeys = [ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class Contract(@PrimaryKey val id: Long = 1, val profileId: Long = 1, val type: String, val agreedMinutes: Long, val unit: String, val validFrom: String, val validUntil: String? = null)
@Entity(foreignKeys = [ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class PayRule(@PrimaryKey val id: Long = 1, val profileId: Long = 1, val type: String, val amountDecimal: String, val mode: String, val accumulation: String, val validFrom: String, val validUntil: String? = null, @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR")

@Dao
interface ConfigurationDao {
    @Query("SELECT * FROM Profile WHERE id = 1") fun observeProfile(): Flow<Profile?>
    @Query("SELECT * FROM Contract WHERE id = 1") suspend fun contract(): Contract?
    @Query("SELECT * FROM PayRule WHERE id = 1") suspend fun rate(): PayRule?
    @Query("SELECT * FROM PayRule WHERE profileId = 1 ORDER BY id") suspend fun rates(): List<PayRule>
    @Query("SELECT * FROM PayRule WHERE profileId = 1 ORDER BY id") fun observeRates(): Flow<List<PayRule>>
    @Upsert suspend fun putProfile(profile: Profile)
    @Upsert suspend fun putContract(contract: Contract)
    @Upsert suspend fun putRate(rate: PayRule)
    @Transaction suspend fun save(profile: Profile, contract: Contract, rate: PayRule?) {
        putProfile(profile)
        putContract(contract)
        if (rate == null) deleteRate() else putRate(rate)
    }
    @Query("DELETE FROM PayRule WHERE id = 1") suspend fun deleteRate()
    @Query("DELETE FROM PayRule WHERE profileId = 1 AND type IN (:types)") suspend fun deleteRates(types: Collection<String>)
    /** Replaces only the rate types this version manages; rows written by a newer version are preserved. */
    @Transaction suspend fun saveConfiguration(profile: Profile, contract: Contract, rates: List<PayRule>, managedTypes: Collection<String> = DataFormat.knownRateTypes) {
        putProfile(profile)
        putContract(contract)
        deleteRates(managedTypes)
        rates.forEach { putRate(it) }
    }
}

// Baseline v1 -> explicit currency in v2; monetary values stay decimal strings.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE PayRule ADD COLUMN currency TEXT NOT NULL DEFAULT 'EUR'") }
}
/** Whole-database reads and replacement for backup/restore. Callers run these inside one transaction. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM Profile ORDER BY id") suspend fun profiles(): List<Profile>
    @Query("SELECT * FROM Contract ORDER BY id") suspend fun contracts(): List<Contract>
    @Query("SELECT * FROM PayRule ORDER BY id") suspend fun payRules(): List<PayRule>
    @Query("SELECT * FROM ScheduledDay ORDER BY id") suspend fun days(): List<ScheduledDay>
    @Query("SELECT * FROM ScheduledInterval ORDER BY id") suspend fun intervals(): List<ScheduledInterval>
    // Children before parents, without relying on cascades.
    @Query("DELETE FROM ScheduledInterval") suspend fun deleteIntervals()
    @Query("DELETE FROM ScheduledDay") suspend fun deleteDays()
    @Query("DELETE FROM PayRule") suspend fun deletePayRules()
    @Query("DELETE FROM Contract") suspend fun deleteContracts()
    @Query("DELETE FROM Profile") suspend fun deleteProfiles()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProfiles(rows: List<Profile>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertContracts(rows: List<Contract>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPayRules(rows: List<PayRule>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDays(rows: List<ScheduledDay>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertIntervals(rows: List<ScheduledInterval>)
}

@Database(entities = [Profile::class, Contract::class, PayRule::class, ScheduledDay::class, ScheduledInterval::class], version = 4, exportSchema = true)
abstract class TurnoDatabase : RoomDatabase() {
    abstract fun configuration(): ConfigurationDao
    abstract fun schedule(): ScheduleDao
    abstract fun backup(): BackupDao
}
