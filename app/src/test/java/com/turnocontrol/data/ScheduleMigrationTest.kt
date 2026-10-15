package com.turnocontrol.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ScheduleMigrationTest {
    @Test fun exportedV1ToV3PreservesAllConfiguration() = verify(1)
    @Test fun exportedV2ToV3PreservesAllConfiguration() = verify(2)
    @Test fun exportedV3ToV4PreservesConfigurationAndShifts() = verify(3)
    private fun verify(version: Int) = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-schedule-migration-$version.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.turnocontrol.data.TurnoDatabase/$version.json").readText()).getJSONObject("database")
        val types = listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT")
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                old.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', 'ALFA', 'UTC')")
                old.execSQL("INSERT INTO Contract VALUES (1, 1, 'FULL', 2250, 'WEEK', '2026-01-01', '2026-12-31')")
                types.forEachIndexed { index, type ->
                    old.execSQL(if (version == 1) "INSERT INTO PayRule VALUES (?, 1, ?, ?, 'PLUS', 'UNDEFINED', '2026-01-01', '2026-12-31')" else "INSERT INTO PayRule VALUES (?, 1, ?, ?, 'PLUS', 'UNDEFINED', '2026-01-01', '2026-12-31', 'EUR')", arrayOf<Any>(index + 1, type, "${index + 1}.1234"))
                }
                if (version >= 3) {
                    old.execSQL("INSERT INTO ScheduledDay VALUES (7, 1, '2026-01-31', 'WORK', 'UTC', 'MANUAL', 5)")
                    old.execSQL("INSERT INTO ScheduledInterval VALUES (11, 7, 0, 1080, 0, 0, 1)")
                    old.execSQL("INSERT INTO ScheduledInterval VALUES (12, 7, 1, 0, 60, 1, 1)")
                }
                old.version = version
            }
            val db = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
            try {
                assertEquals(Profile(displayName = "Perfil ficticio", alias = "ALFA", zoneId = "UTC"), db.configuration().observeProfile().first())
                assertEquals(Contract(type = "FULL", agreedMinutes = 2250, unit = "WEEK", validFrom = "2026-01-01", validUntil = "2026-12-31"), db.configuration().contract())
                assertEquals(types.mapIndexed { index, type -> PayRule(id = index.toLong() + 1, type = type, amountDecimal = "${index + 1}.1234", mode = "PLUS", accumulation = "UNDEFINED", validFrom = "2026-01-01", validUntil = "2026-12-31", currency = "EUR") }, db.configuration().rates())
                if (version < 3) assertTrue(db.schedule().all().isEmpty()) else {
                    val saved = db.schedule().all().single()
                    assertEquals(ScheduledDay(7, 1, "2026-01-31", "WORK", "UTC", "MANUAL", 5), saved.day)
                    assertEquals(listOf(ScheduledInterval(11,7,0,1080,0,0,1), ScheduledInterval(12,7,1,0,60,1,1)), saved.intervals.sortedBy { it.position })
                    assertTrue(saved.intervals.none { it.isExtra })
                }
                assertEquals(4, db.openHelper.readableDatabase.version)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
