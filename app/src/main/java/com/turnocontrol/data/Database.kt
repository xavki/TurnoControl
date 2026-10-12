package com.turnocontrol.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    @Upsert suspend fun putProfile(profile: Profile)
    @Upsert suspend fun putContract(contract: Contract)
    @Upsert suspend fun putRate(rate: PayRule)
    @Transaction suspend fun save(profile: Profile, contract: Contract, rate: PayRule?) {
        putProfile(profile)
        putContract(contract)
        if (rate == null) deleteRate() else putRate(rate)
    }
    @Query("DELETE FROM PayRule WHERE id = 1") suspend fun deleteRate()
    @Query("DELETE FROM PayRule WHERE profileId = 1") suspend fun deleteRates()
    @Transaction suspend fun saveConfiguration(profile: Profile, contract: Contract, rates: List<PayRule>) {
        putProfile(profile)
        putContract(contract)
        deleteRates()
        rates.forEach { putRate(it) }
    }
}

// Baseline v1 -> explicit currency in v2; monetary values stay decimal strings.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE PayRule ADD COLUMN currency TEXT NOT NULL DEFAULT 'EUR'") }
}
@Database(entities = [Profile::class, Contract::class, PayRule::class], version = 2, exportSchema = true)
abstract class TurnoDatabase : RoomDatabase() { abstract fun configuration(): ConfigurationDao }
