package com.turnocontrol.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ScheduleRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val date = LocalDate.parse("2026-01-31")
    private fun work(text: String, d: LocalDate = date) = PlannedDay(d, DayKind.WORK, TimeCellParser.parse(text).intervals)
    @Test fun calendarNeedsNoContractAndPersistsEditsDeletionAndFreeDays() = runTest {
        val name = "schedule-roundtrip.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TurnoDatabase::class.java, name).build()
        try {
            open().let { db -> try {
                val repo = ScheduleRepository(db)
                repo.save(repo.snapshot(), listOf(work("08:00-12:00 / 16:00-20:00")))
                assertNotNull(db.schedule().profile())
                assertNull(db.configuration().contract())
                assertTrue(db.configuration().rates().isEmpty())
            } finally { db.close() } }
            open().let { db -> try {
                val repo = ScheduleRepository(db)
                assertEquals(2, repo.snapshot().single().intervals.size)
                repo.save(repo.snapshot(), listOf(work("22:00-02:00")))
                assertEquals(1, repo.snapshot().single().intervals.single().endDayOffset)
                repo.save(repo.snapshot(), listOf(PlannedDay(date, DayKind.FREE, emptyList())))
                assertTrue(repo.snapshot().single().intervals.isEmpty())
                repo.save(repo.snapshot(), emptyList(), setOf(date))
                assertTrue(repo.snapshot().isEmpty())
                assertTrue(db.openHelper.readableDatabase.query("SELECT * FROM ScheduledInterval").use { !it.moveToFirst() })
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun staleSnapshotAndCrossDayConflictRollBackWholeBatch() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, TurnoDatabase::class.java).build()
        try {
            val repo = ScheduleRepository(db)
            val empty = repo.snapshot()
            repo.save(empty, listOf(work("22:00-02:00")))
            assertTrue(runCatching { repo.save(empty, listOf(work("08:00-16:00")), origin = "OCR_REVIEWED") }.exceptionOrNull() is ScheduleChangedException)
            val before = repo.snapshot()
            assertTrue(runCatching { repo.save(before, listOf(work("01:00-03:00", date.plusDays(1)), work("08:00-16:00", date.plusDays(2))), origin = "OCR_REVIEWED") }.isFailure)
            assertEquals(before, repo.snapshot())
            // Explicit replacement uses a fresh snapshot, never INSERT OR REPLACE.
            repo.save(before, listOf(work("09:00-17:00")), origin = "OCR_REVIEWED")
            val after = repo.snapshot()
            assertEquals("OCR_REVIEWED", after.single().day.origin)
            assertEquals(before.single().day.id, after.single().day.id)
            assertEquals(2, after.single().day.revision)
            repo.save(after, listOf(work("09:00-17:00")), origin = "OCR_REVIEWED")
            assertEquals(after, repo.snapshot())
        } finally { db.close() }
    }
    @Test fun calendarWritesAndConfigurationUpdatesPreserveEachOther() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, TurnoDatabase::class.java).build()
        try {
            val profile = Profile(displayName = "Perfil ficticio", alias = "ALFA", zoneId = "UTC")
            val contract = Contract(type = "FULL", agreedMinutes = 2250, unit = "WEEK", validFrom = "2026-01-01")
            val rates = listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT").mapIndexed { i, type -> PayRule(id = i.toLong() + 1, type = type, amountDecimal = "${i + 1}.1234", mode = "PLUS", accumulation = "UNDEFINED", validFrom = "2026-01-01") }
            db.configuration().saveConfiguration(profile, contract, rates)
            val repo = ScheduleRepository(db)
            repo.save(repo.snapshot(), listOf(work("08:00-12:00 / 16:00-20:00")))
            val before = repo.snapshot()
            db.configuration().saveConfiguration(profile.copy(alias = "BETA"), contract, rates)
            assertEquals(before, repo.snapshot())
            repo.save(repo.snapshot(), listOf(work("22:00-02:00")))
            repo.save(repo.snapshot(), emptyList(), setOf(date))
            assertEquals(profile.copy(alias = "BETA"), db.schedule().profile())
            assertEquals(contract, db.configuration().contract())
            assertEquals(rates, db.configuration().rates())
        } finally { db.close() }
    }
    @Test fun extraFlagPersistsAndChangingOnlyClassificationInvalidatesSnapshots() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, TurnoDatabase::class.java).build()
        try {
            val repo = ScheduleRepository(db)
            val plain = work("18:00-24:00 / 00:00-01:00")
            repo.save(repo.snapshot(), listOf(plain))
            val before = repo.snapshot()
            val extra = plain.copy(intervals = plain.intervals.mapIndexed { index, interval -> interval.copy(isExtra = index == 1) })
            repo.save(before, listOf(extra))
            assertEquals(60, repo.snapshot().single().planned().extraMinutes())
            assertEquals(2, repo.snapshot().single().day.revision)
            assertTrue(runCatching { repo.save(before, listOf(plain), origin = "OCR_REVIEWED") }.exceptionOrNull() is ScheduleChangedException)
            assertTrue(repo.snapshot().single().intervals.last().isExtra)
        } finally { db.close() }
    }
}
