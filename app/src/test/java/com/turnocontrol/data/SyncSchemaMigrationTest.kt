package com.turnocontrol.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration to schema 5 on synthetic databases built from the exported schema files (Robolectric SQLite on the JVM).
 * This complements, and does not replace, the instrumented MigrationTestHelper test on a device, which was not run.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SyncSchemaMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun create(name: String, version: Int, fill: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.turnocontrol.data.TurnoDatabase/$version.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            // Room's own identity row, so the file is exactly what that version of the app left behind.
            schema.getJSONArray("setupQueries").let { q -> for (i in 0 until q.length()) db.execSQL(q.getString(i)) }
            db.execSQL("PRAGMA application_id = ${DataFormat.applicationId()}")
            fill(db)
            db.version = version
        }
    }
    private inline fun <T> TurnoDatabase.closing(block: (TurnoDatabase) -> T): T = try { block(this) } finally { close() }
    private fun open(name: String) = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
    private fun rows(db: TurnoDatabase, sql: String): List<List<String?>> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }
    private val userTables = listOf("Profile", "Contract", "PayRule", "ScheduledDay", "ScheduledInterval")
    private val syncTables = listOf("SyncState", "SyncRecordState", "SyncOutbox", "SyncConflict", "SyncQuarantine", "SyncRecovered")

    private fun fillV4(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', 'ALFA', 'Europe/Madrid')")
        db.execSQL("INSERT INTO Contract VALUES (1, 1, 'PARTIAL', 4800, 'MONTH', '2026-01-01', NULL)")
        listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT", "EXTRA_HOUR").forEachIndexed { i, type ->
            db.execSQL("INSERT INTO PayRule VALUES (?, 1, ?, ?, 'BASE', 'UNDEFINED', '2026-01-01', NULL, 'EUR')", arrayOf<Any>(i + 1, type, "${i + 7}.25"))
        }
        db.execSQL("INSERT INTO PayRule VALUES (40, 1, 'WEEKEND_FUTURE', '3', 'PLUS', 'UNDEFINED', '2026-01-01', NULL, 'EUR')") // Unknown type: preserved.
        db.execSQL("INSERT INTO ScheduledDay VALUES (7, 1, '2026-01-31', 'WORK', 'Europe/Madrid', 'MANUAL', 5)")
        db.execSQL("INSERT INTO ScheduledInterval VALUES (11, 7, 0, 480, 720, 0, 0, 0)")
        db.execSQL("INSERT INTO ScheduledInterval VALUES (12, 7, 1, 1320, 120, 0, 1, 1)") // Overnight declared extra.
        db.execSQL("INSERT INTO ScheduledDay VALUES (8, 1, '2026-02-02', 'VACATION', 'Europe/Madrid', 'MANUAL', 1)")
        db.execSQL("INSERT INTO ScheduledDay VALUES (9, 1, '2026-02-03', 'SICK_LEAVE', 'Europe/Madrid', 'MANUAL', 1)")
        db.execSQL("INSERT INTO ScheduledDay VALUES (10, 1, '2026-02-04', 'FREE', 'Europe/Madrid', 'OCR_REVIEWED', 2)")
    }

    @Test fun v4ToV5PreservesEveryUserRowAndAddsOnlyEmptySyncTables() = runTest {
        val name = "sync-migration-v4.db"
        create(name, 4, ::fillV4)
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            userTables.map { t -> db.rawQuery("SELECT * FROM $t ORDER BY 1", null).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) } } }
        }
        try {
            open(name).closing { db ->
                assertEquals(5, db.openHelper.readableDatabase.version)
                assertEquals(before, userTables.map { rows(db, "SELECT * FROM $it ORDER BY 1") })
                syncTables.forEach { assertTrue("$it debe estar vacía", rows(db, "SELECT * FROM $it").isEmpty()) }
                assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
                assertEquals(listOf(listOf("ok")), rows(db, "PRAGMA integrity_check"))
                assertEquals(DataFormat.applicationId(), rows(db, "PRAGMA application_id").single().single()!!.toInt())
                val days = db.schedule().all().map { it.planned() }
                assertEquals(listOf(DayKind.WORK, DayKind.VACATION, DayKind.SICK_LEAVE, DayKind.FREE), days.map { it.kind })
                assertEquals(240, days.first().extraMinutes())
                assertEquals(7, db.configuration().rates().size)
                assertEquals(listOf("WEEKEND_FUTURE"), CompatibilityChecker(db).unknownRateTypes())
                assertTrue(CompatibilityChecker(db).check() is CompatibilityReport.Compatible)
            }
            // Reopening the migrated file runs no migration and changes nothing.
            open(name).closing { db -> assertEquals(before, userTables.map { rows(db, "SELECT * FROM $it ORDER BY 1") }) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun v1DatabaseMigratesThroughEveryStepToV5() = runTest {
        val name = "sync-migration-v1.db"
        create(name, 1) { db ->
            db.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', '', 'UTC')")
            db.execSQL("INSERT INTO PayRule VALUES (1, 1, 'HOURLY_BASE', '9.5', 'BASE', 'UNDEFINED', '2026-01-01', NULL)")
        }
        try {
            open(name).closing { db ->
                assertEquals(5, db.openHelper.readableDatabase.version)
                assertEquals(listOf(listOf("1", "1", "HOURLY_BASE", "9.5", "BASE", "UNDEFINED", "2026-01-01", null, "EUR")), rows(db, "SELECT * FROM PayRule"))
                assertTrue(db.schedule().all().isEmpty())
                syncTables.forEach { assertTrue(rows(db, "SELECT * FROM $it").isEmpty()) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun v3CalendarWithSplitOvernightAndFreeDaysReachesV5Intact() = runTest {
        val name = "sync-migration-v3.db"
        create(name, 3) { db ->
            db.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', '', 'Europe/Madrid')")
            db.execSQL("INSERT INTO ScheduledDay VALUES (1, 1, '2025-12-31', 'WORK', 'Europe/Madrid', 'MANUAL', 2)")
            db.execSQL("INSERT INTO ScheduledInterval VALUES (1, 1, 0, 1320, 360, 0, 1)") // 22:00 → 06:00 into the new year.
            db.execSQL("INSERT INTO ScheduledDay VALUES (2, 1, '2026-01-01', 'FREE', 'Europe/Madrid', 'MANUAL', 1)")
            db.execSQL("INSERT INTO ScheduledDay VALUES (3, 1, '2026-01-02', 'WORK', 'Europe/Madrid', 'OCR_REVIEWED', 1)")
            db.execSQL("INSERT INTO ScheduledInterval VALUES (2, 3, 0, 540, 780, 0, 0)") // Split shift 09:00–13:00 …
            db.execSQL("INSERT INTO ScheduledInterval VALUES (3, 3, 1, 1020, 1260, 0, 0)") // … and 17:00–21:00.
        }
        try {
            open(name).closing { db ->
                assertEquals(5, db.openHelper.readableDatabase.version)
                assertEquals(listOf(listOf("ok")), rows(db, "PRAGMA integrity_check"))
                assertTrue(rows(db, "PRAGMA foreign_key_check").isEmpty())
                assertEquals(listOf(listOf("1", "1", "0", "1320", "360", "0", "1", "0"), listOf("2", "3", "0", "540", "780", "0", "0", "0"),
                    listOf("3", "3", "1", "1020", "1260", "0", "0", "0")), rows(db, "SELECT * FROM ScheduledInterval ORDER BY id"))
                val days = db.schedule().all().map { it.planned() }
                assertNull(PlannedScheduleValidator.validate(days))
                assertEquals(listOf(DayKind.WORK, DayKind.FREE, DayKind.WORK), days.map { it.kind })
                // The overnight shift stays whole in 2025; extras did not exist before v4 and stay at zero.
                assertEquals(480, ScheduleSummary.year(days, 2025).totalMinutes)
                assertEquals(480, ScheduleSummary.year(days, 2026).totalMinutes)
                assertEquals(0, ScheduleSummary.year(days, 2026).extraMinutes)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migratedDatabaseExportsAndRestoresItsOwnBackup() = runTest {
        val name = "sync-migration-backup.db"
        create(name, 4, ::fillV4)
        try {
            open(name).closing { db ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM PayRule WHERE type = 'WEEKEND_FUTURE'") // Not exportable by design.
                var theme = false
                val repo = BackupRepository(db, { theme }, { theme = it }, File(context.filesDir, "migr-recovery"), AppVersion("test", 1))
                val snapshot = repo.snapshot()
                val text = BackupCodec.encode(BackupFile(BackupHeader(1, "test", 1, DataFormat.ROOM_SCHEMA_VERSION, DataFormat.CURRENT, java.time.Instant.EPOCH), snapshot))
                db.openHelper.writableDatabase.execSQL("DELETE FROM ScheduledDay")
                repo.restore(BackupCodec.decode(text))
                assertEquals(snapshot, repo.snapshot())
            }
        } finally { context.deleteDatabase(name) }
    }
}
