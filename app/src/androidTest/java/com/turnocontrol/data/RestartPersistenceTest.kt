package com.turnocontrol.data

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.domain.ConfigurationDraft
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
        fun open() = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        if (mode != "verify") {
            val db = open()
            try { ConfigurationRepository(db, context).save(fixture) } finally { db.close() }
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
