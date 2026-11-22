package com.turnocontrol.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The stale-snapshot check covers the changed dates and their neighbours, and nothing else. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ScheduleSnapshotWindowTest {
    private lateinit var db: TurnoDatabase
    private lateinit var repo: ScheduleRepository
    private val d = LocalDate.parse("2026-12-10")
    private fun day(date: LocalDate, text: String) = TimeCellParser.parse(text).let { PlannedDay(date, it.kind, it.intervals) }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).allowMainThreadQueries().build()
        repo = ScheduleRepository(db)
    }
    @After fun cleanup() { db.close() }

    @Test fun changedNeighbourAfterTheSnapshotRejectsTheSave() = runTest {
        val stale = repo.snapshot()
        repo.save(repo.snapshot(), listOf(day(d.plusDays(1), "09:00-13:00")))
        assertThrows(ScheduleChangedException::class.java) { runBlocking { repo.save(stale, listOf(day(d, "08:00-16:00"))) } }
        assertEquals(1, repo.snapshot().size)
    }

    @Test fun overnightShiftAddedTheDayBeforeRejectsTheSave() = runTest {
        val stale = repo.snapshot()
        repo.save(repo.snapshot(), listOf(day(d.minusDays(1), "22:00-06:00")))
        assertThrows(ScheduleChangedException::class.java) { runBlocking { repo.save(stale, listOf(day(d, "05:00-12:00"))) } }
        assertEquals(listOf(d.minusDays(1)), repo.snapshot().map { LocalDate.parse(it.day.date) })
    }

    @Test fun unrelatedDayWrittenMeanwhileDoesNotRejectTheSave() = runTest {
        val stale = repo.snapshot()
        repo.save(repo.snapshot(), listOf(day(d.plusDays(5), "09:00-13:00")))
        repo.save(stale, listOf(day(d, "08:00-16:00")))
        assertEquals(listOf(d, d.plusDays(5)), repo.snapshot().map { LocalDate.parse(it.day.date) })
    }
}
