package com.turnocontrol.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private inline fun <T> TurnoDatabase.use(block: (TurnoDatabase) -> T): T = try { block(this) } finally { close() }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profile = Profile(displayName = "Perfil ficticio", alias = "", zoneId = "UTC")
    private val contract = Contract(type = "PARTIAL", agreedMinutes = 3600, unit = "MONTH", validFrom = "2026-01-01")
    private val rate = PayRule(type = "HOURLY_BASE", amountDecimal = "7.17", mode = "BASE", accumulation = "UNDEFINED", validFrom = "2026-01-01")
    private fun open(name: String) = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()

    @Test fun configurationSurvivesReopeningAndClearsRates() = runTest {
        val name = "roundtrip.db"
        try {
            open(name).use { it.configuration().saveConfiguration(profile, contract, listOf(rate)) }
            open(name).use {
                assertEquals(profile, it.configuration().observeProfile().first())
                assertEquals(contract, it.configuration().contract())
                assertEquals(listOf(rate), it.configuration().rates())
                it.configuration().saveConfiguration(profile, contract, emptyList())
                assertTrue(it.configuration().rates().isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromExportedV1PreservesAmounts() = runTest {
        val name = "migration.db"
        val schema = JSONObject(File("schemas/com.turnocontrol.data.TurnoDatabase/1.json").readText()).getJSONObject("database")
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                old.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', '', 'UTC')")
                old.execSQL("INSERT INTO PayRule VALUES (1, 1, 'HOURLY_BASE', '7.17', 'BASE', 'UNDEFINED', '2026-01-01', NULL)")
                old.version = 1
            }
            open(name).use {
                // Opening triggers Room's structural validation after migration.
                assertEquals("EUR", it.configuration().rate()!!.currency)
                assertEquals("7.17", it.configuration().rate()!!.amountDecimal)
                assertEquals(profile, it.configuration().observeProfile().first())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedContractWriteRollsBackEntireConfiguration() = runTest {
        val name = "rollback.db"
        try {
            open(name).use { db ->
                db.configuration().saveConfiguration(profile, contract, listOf(rate))
                val result = runCatching { db.configuration().saveConfiguration(profile.copy(displayName = "Cambio ficticio"), contract.copy(profileId = 99), emptyList()) }
                assertTrue(result.isFailure)
                assertEquals(profile, db.configuration().observeProfile().first())
                assertEquals(listOf(rate), db.configuration().rates())
            }
        } finally { context.deleteDatabase(name) }
    }
}
