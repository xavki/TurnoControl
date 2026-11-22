package com.turnocontrol.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CompatibilityCheckerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<TurnoDatabase>()
    @After fun cleanup() { opened.forEach { it.close() } }
    private fun open(name: String) = Room.databaseBuilder(context, TurnoDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { opened += it }
    private fun fresh(name: String): TurnoDatabase { context.deleteDatabase(name); return open(name) }
    private fun raw(name: String) = context.openOrCreateDatabase(name, 0, null)
    private suspend fun seed(db: TurnoDatabase) {
        ScheduleRepository(db).save(emptyList(), listOf(
            PlannedDay(LocalDate.parse("2026-03-02"), DayKind.WORK, TimeCellParser.parse("08:00-16:00").intervals),
            PlannedDay(LocalDate.parse("2026-03-03"), DayKind.VACATION, emptyList()),
            PlannedDay(LocalDate.parse("2026-03-04"), DayKind.SICK_LEAVE, emptyList())))
    }
    private fun applicationId(db: TurnoDatabase) = db.openHelper.readableDatabase.query("PRAGMA application_id").use { it.moveToFirst(); it.getInt(0) }

    @Test fun knownValuesAreCompatibleAndStampTheDataFormat() = runTest {
        val db = fresh("compat-known.db")
        seed(db)
        assertEquals(0, applicationId(db))
        assertEquals(CompatibilityReport.Compatible, CompatibilityChecker(db).check())
        assertEquals(DataFormat.applicationId(), applicationId(db))
        assertEquals(CompatibilityReport.Compatible, CompatibilityChecker(db).check())
    }

    @Test fun unknownDayKindIsRejectedWithoutTouchingTheRows() = runTest {
        val db = fresh("compat-unknown-kind.db")
        seed(db)
        db.openHelper.writableDatabase.execSQL("UPDATE ScheduledDay SET kind = 'COMPENSATORY_REST' WHERE date = '2026-03-03'")
        val before = db.openHelper.readableDatabase.query("SELECT id, date, kind, revision FROM ScheduledDay ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) } }
        val report = CompatibilityChecker(db).check()
        assertTrue(report is CompatibilityReport.Incompatible)
        assertTrue((report as CompatibilityReport.Incompatible).reasons.single().contains("COMPENSATORY_REST"))
        val after = db.openHelper.readableDatabase.query("SELECT id, date, kind, revision FROM ScheduledDay ORDER BY id").use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) } }
        assertEquals(before, after)
        assertEquals("A rejected database is never stamped", 0, applicationId(db))
        // Interpreting it directly fails with a typed error, never a silent WORK/FREE replacement.
        val error = runCatching { db.schedule().all().map { it.planned() } }.exceptionOrNull()
        assertTrue(error is UnsupportedDataException)
    }

    @Test fun dataStampedByANewerFormatIsRejected() = runTest {
        val db = fresh("compat-future-format.db")
        seed(db)
        db.openHelper.writableDatabase.execSQL("PRAGMA application_id = ${DataFormat.applicationId(DataFormat.CURRENT + 1)}")
        val report = CompatibilityChecker(db).check() as CompatibilityReport.Incompatible
        assertTrue(report.reasons.single().contains("más nueva"))
        assertEquals(DataFormat.applicationId(DataFormat.CURRENT + 1), applicationId(db))
    }

    @Test fun foreignApplicationIdIsRejected() = runTest {
        val db = fresh("compat-foreign.db")
        db.openHelper.writableDatabase.execSQL("PRAGMA application_id = 12345")
        assertTrue(CompatibilityChecker(db).check() is CompatibilityReport.Incompatible)
    }

    @Test fun newerRoomSchemaVersionIsReportedNotCrashedAndFileIsPreserved() {
        val name = "compat-room-future.db"
        context.deleteDatabase(name)
        fresh(name).also { it.openHelper.writableDatabase; it.close(); opened.remove(it) }
        raw(name).use { it.version = DataFormat.ROOM_SCHEMA_VERSION + 1; it.execSQL("CREATE TABLE FutureOnly (id INTEGER PRIMARY KEY)") }
        val report = CompatibilityChecker(open(name)).check()
        assertTrue(report is CompatibilityReport.Incompatible)
        raw(name).use { db ->
            assertEquals(DataFormat.ROOM_SCHEMA_VERSION + 1, db.version)
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name = 'FutureOnly'", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun sameVersionWithUnknownSchemaHashIsReported() {
        val name = "compat-identity.db"
        context.deleteDatabase(name)
        fresh(name).also { it.openHelper.writableDatabase; it.close(); opened.remove(it) }
        raw(name).use { it.execSQL("UPDATE room_master_table SET identity_hash = 'from-a-different-build'") }
        assertTrue(CompatibilityChecker(open(name)).check() is CompatibilityReport.Incompatible)
    }

    @Test fun savingSettingsPreservesUnknownRateTypesAndTheirIds() = runTest {
        val db = fresh("compat-rates.db")
        val repository = ConfigurationRepository(db, PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(temporary.root, "p.preferences_pb") }))
        val draft = ConfigurationDraft(name = "Perfil ficticio", zone = "UTC", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15"))
        repository.save(draft)
        // A future version adds a rate type, with an id that a naive renumbering would overwrite.
        db.configuration().putRate(PayRule(id = 2, type = "WEEKEND_FUTURE", amountDecimal = "3.5", mode = "PLUS", accumulation = "UNDEFINED", validFrom = "2026-01-01"))
        assertEquals(listOf("WEEKEND_FUTURE"), CompatibilityChecker(db).unknownRateTypes())
        repository.save(draft.copy(hourlyRate = "11", extraRates = mapOf("EXTRA_PAY" to "1.25", "EXTRA_HOUR" to "16")))
        val rules = db.configuration().rates().associateBy { it.type }
        assertEquals("3.5", rules.getValue("WEEKEND_FUTURE").amountDecimal)
        assertEquals(2L, rules.getValue("WEEKEND_FUTURE").id)
        assertEquals(1L, rules.getValue("HOURLY_BASE").id) // ConfigurationDao.rate() still reads id 1.
        assertEquals("11", db.configuration().rate()!!.amountDecimal)
        assertEquals(6L, rules.getValue("EXTRA_HOUR").id)
        assertEquals("1.25", rules.getValue("EXTRA_PAY").amountDecimal)
        assertNotEquals(2L, rules.getValue("EXTRA_PAY").id)
        assertEquals(rules.size, rules.values.map { it.id }.distinct().size)
    }
}
