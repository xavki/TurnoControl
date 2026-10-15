package com.turnocontrol.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(foreignKeys = [ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["profileId", "date"], unique = true)])
data class ScheduledDay(@PrimaryKey(autoGenerate = true) val id: Long = 0, val profileId: Long = 1,
    val date: String, val kind: String, val zoneId: String, val origin: String, val revision: Long = 1)

@Entity(foreignKeys = [ForeignKey(entity = ScheduledDay::class, parentColumns = ["id"], childColumns = ["dayId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["dayId", "position"], unique = true)])
data class ScheduledInterval(@PrimaryKey(autoGenerate = true) val id: Long = 0, val dayId: Long,
    val position: Int, val startMinute: Int, val endMinute: Int, val startDayOffset: Int, val endDayOffset: Int,
    @ColumnInfo(defaultValue = "0") val isExtra: Boolean = false)

data class StoredScheduleDay(@Embedded val day: ScheduledDay, @Relation(parentColumn = "id", entityColumn = "dayId") val intervals: List<ScheduledInterval>)

@Dao
interface ScheduleDao {
    @Transaction @Query("SELECT * FROM ScheduledDay WHERE profileId = 1 ORDER BY date")
    fun observe(): Flow<List<StoredScheduleDay>>
    @Transaction @Query("SELECT * FROM ScheduledDay WHERE profileId = 1 ORDER BY date")
    suspend fun all(): List<StoredScheduleDay>
    @Query("SELECT * FROM Profile WHERE id = 1") suspend fun profile(): Profile?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertProfile(profile: Profile)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertDay(day: ScheduledDay): Long
    @Update suspend fun updateDay(day: ScheduledDay)
    @Query("DELETE FROM ScheduledInterval WHERE dayId = :dayId") suspend fun clearIntervals(dayId: Long)
    @Insert suspend fun insertIntervals(intervals: List<ScheduledInterval>)
    @Query("DELETE FROM ScheduledDay WHERE id = :id") suspend fun deleteDay(id: Long)
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `ScheduledDay` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `date` TEXT NOT NULL, `kind` TEXT NOT NULL, `zoneId` TEXT NOT NULL, `origin` TEXT NOT NULL, `revision` INTEGER NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `Profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ScheduledDay_profileId_date` ON `ScheduledDay` (`profileId`, `date`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `ScheduledInterval` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, `startDayOffset` INTEGER NOT NULL, `endDayOffset` INTEGER NOT NULL, FOREIGN KEY(`dayId`) REFERENCES `ScheduledDay`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ScheduledInterval_dayId_position` ON `ScheduledInterval` (`dayId`, `position`)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ScheduledInterval ADD COLUMN isExtra INTEGER NOT NULL DEFAULT 0")
    }
}
