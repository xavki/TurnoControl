package com.turnocontrol.data

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.domain.ConfigurationDraft
import com.turnocontrol.domain.PlannedDay
import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.TimeCellParser
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Host-driven seed/verify modes test a real process restart in the isolated variant. */
class RestartPersistenceTest {
    private val fixture = ConfigurationDraft(
        name = "Perfil ficticio de reinicio", alias = "alias ficticio", zone = "UTC",
        type = "FULL", hours = "37.5", unit = "WEEK", from = "2026-01-01",
        hourlyRate = "10.1234",
        extraRates = mapOf("EXTRA_PAY" to "2.00", "COMPLEMENTARY" to "11.50", "NIGHT" to "0.50", "SPECIAL_NIGHT" to "1.50")
    )

    @Test fun repositoryConfigurationSurvivesRestart() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.turnocontrol.verification") { "Restart tests must use the isolated verification package" }
        val mode = InstrumentationRegistry.getArguments().getString("restartPhase")
        require(mode == null || mode in listOf("seed", "verify"))
        // Normal suite uses its own DB; host phases exercise the app's actual DB.
        val name = if (mode == null) "restart-suite.db" else "turnocontrol.db"
        fun open() = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        if (mode != "verify") {
            val db = open()
            try {
                ConfigurationRepository(db, context).save(fixture)
                val schedule = ScheduleRepository(db)
                // Only isolated synthetic data; real user's app is never targeted.
                val previous = schedule.snapshot()
                schedule.save(previous, listOf(
                    PlannedDay(LocalDate.parse("2026-01-30"), DayKind.WORK, TimeCellParser.parse("08:00-12:00 / 16:00-20:00").intervals),
                    PlannedDay(LocalDate.parse("2026-01-31"), DayKind.WORK, TimeCellParser.parse("22:00-24:00 / 00:00-01:00").intervals.mapIndexed { index, interval -> interval.copy(isExtra = index == 1) })
                ), deletions = previous.map { LocalDate.parse(it.day.date) }.filter { it.toString() !in listOf("2026-01-30", "2026-01-31") }.toSet())
            } finally { db.close() }
            if (mode == "seed") {
                context.getSharedPreferences("restart-test-proof", 0).edit().putInt("seedPid", Process.myPid()).commit()
                return@runTest
            }
        }
        val db = open()
        try {
            val repository = ConfigurationRepository(db, context)
            val savedProfile = repository.profile.first()
            assertNotNull(savedProfile)
            assertEquals(fixture, repository.load(savedProfile!!))
            assertEquals(5, db.configuration().rates().size)
            val planned = ScheduleRepository(db).snapshot().map { it.planned() }
            assertEquals(2, planned.size)
            assertEquals(2, planned[0].intervals.size)
            assertEquals(LocalDate.parse("2026-01-31"), planned[1].date)
            assertEquals(1, planned[1].intervals.first().endDayOffset)
            assertTrue(planned[1].intervals.last().isExtra)
            assertFalse(planned[1].intervals.first().isExtra)
            if (mode == "verify") {
                val oldPid = context.getSharedPreferences("restart-test-proof", 0).getInt("seedPid", -1)
                assertTrue("Seed process must have run", oldPid > 0)
                assertNotEquals("Verification must run in a new process", oldPid, Process.myPid())
            }
        } finally {
            db.close()
            if (mode == null) context.deleteDatabase(name)
        }
    }
}
