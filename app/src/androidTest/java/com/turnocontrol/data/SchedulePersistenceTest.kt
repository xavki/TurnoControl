package com.turnocontrol.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.domain.*
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SchedulePersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnoDatabase::class.java)
    @Test fun migration2To3PreservesEntireConfiguration() = migrate(2)
    @Test fun migration1To3PreservesEntireConfiguration() = migrate(1)
    @Test fun migration3To4PreservesEntireConfigurationAndShifts() = migrate(3)
    private fun migrate(version: Int) {
        val name = "schedule-migration-$version.db"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String) = db.query(query).use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) } }
        val queries = listOf("SELECT * FROM Profile", "SELECT * FROM Contract", "SELECT id, profileId, type, amountDecimal, mode, accumulation, validFrom, validUntil FROM PayRule ORDER BY id")
        val old = migrations.createDatabase(name, version)
        val before = try {
            old.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', 'ALFA', 'UTC')")
            old.execSQL("INSERT INTO Contract VALUES (1, 1, 'PARTIAL', 3600, 'MONTH', '2026-01-01', '2026-12-31')")
            listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT").forEachIndexed { i, type ->
                val sql = if (version == 1) "INSERT INTO PayRule VALUES (?, 1, ?, ?, 'BASE', 'UNDEFINED', '2026-01-01', '2026-12-31')" else "INSERT INTO PayRule VALUES (?, 1, ?, ?, 'BASE', 'UNDEFINED', '2026-01-01', '2026-12-31', 'EUR')"
                old.execSQL(sql, arrayOf<Any>(i + 1, type, "${i + 1}.1234"))
            }
            if (version == 3) {
                old.execSQL("INSERT INTO ScheduledDay VALUES (7, 1, '2026-01-31', 'WORK', 'UTC', 'MANUAL', 5)")
                old.execSQL("INSERT INTO ScheduledInterval VALUES (11, 7, 0, 1080, 0, 0, 1)")
                old.execSQL("INSERT INTO ScheduledInterval VALUES (12, 7, 1, 0, 60, 1, 1)")
            }
            queries.map { rows(old, it) }
        } finally { old.close() }
        val migrated = migrations.runMigrationsAndValidate(name, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        try {
            assertEquals(before, queries.map { rows(migrated, it) })
            assertEquals(List(5) { listOf("EUR") }, rows(migrated, "SELECT currency FROM PayRule ORDER BY id"))
            if (version < 3) {
                assertTrue(rows(migrated, "SELECT * FROM ScheduledDay").isEmpty())
                assertTrue(rows(migrated, "SELECT * FROM ScheduledInterval").isEmpty())
            } else {
                assertEquals(listOf(listOf("7", "1", "2026-01-31", "WORK", "UTC", "MANUAL", "5")), rows(migrated, "SELECT * FROM ScheduledDay"))
                assertEquals(listOf(listOf("11", "7", "0", "1080", "0", "0", "1", "0"), listOf("12", "7", "1", "0", "60", "1", "1", "0")), rows(migrated, "SELECT * FROM ScheduledInterval ORDER BY position"))
            }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun scheduleReopensEditsDeletesAndRejectsStaleImport() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "schedule-instrumented.db"
        context.deleteDatabase(name)
        val date = LocalDate.parse("2026-01-31")
        val day = PlannedDay(date, DayKind.WORK, TimeCellParser.parse("08:00-12:00 / 22:00-02:00").intervals)
        fun open() = Room.databaseBuilder(context, TurnoDatabase::class.java, name).build()
        try {
            open().let { db -> try { val repo = ScheduleRepository(db); repo.save(repo.snapshot(), listOf(day)) } finally { db.close() } }
            open().let { db -> try {
                val repo = ScheduleRepository(db)
                assertEquals(day, repo.snapshot().single().planned())
                val before = repo.snapshot()
                repo.save(before, listOf(day.copy(kind = DayKind.FREE, intervals = emptyList())))
                assertTrue(runCatching { repo.save(before, listOf(day), origin = "OCR_REVIEWED") }.exceptionOrNull() is ScheduleChangedException)
                assertEquals("FREE", repo.snapshot().single().day.kind)
                repo.save(repo.snapshot(), emptyList(), setOf(date))
                assertTrue(repo.snapshot().isEmpty())
                assertNull(db.configuration().contract())
                assertTrue(db.configuration().rates().isEmpty())
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
}
