package com.turnocontrol.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TurnoDatabase::class.java)
    @Test fun migratesV1WithoutLosingRate() {
        val name = "migration-instrumented.db"
        fun rows(db: androidx.sqlite.db.SupportSQLiteDatabase, query: String): List<List<String?>> = db.query(query).use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }
        val queries = listOf(
            "SELECT * FROM Profile ORDER BY id",
            "SELECT * FROM Contract ORDER BY id",
            "SELECT id, profileId, type, amountDecimal, mode, accumulation, validFrom, validUntil FROM PayRule ORDER BY id"
        )
        val old = migrations.createDatabase(name, 1)
        val before = try {
            old.execSQL("INSERT INTO Profile VALUES (1, 'Perfil ficticio', 'alias ficticio', 'UTC')")
            old.execSQL("INSERT INTO Contract VALUES (1, 1, 'PARTIAL', 3600, 'MONTH', '2026-01-01', '2026-12-31')")
            listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT").forEachIndexed { index, type ->
                old.execSQL("INSERT INTO PayRule VALUES (?, 1, ?, ?, 'BASE', 'UNDEFINED', '2026-01-01', NULL)", arrayOf<Any>(index + 1, type, "${index + 1}.1234"))
            }
            queries.map { rows(old, it) }
        } finally { old.close() }
        val migrated = migrations.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)
        try {
            assertEquals(before, queries.map { rows(migrated, it) })
            assertEquals(List(5) { listOf("EUR") }, rows(migrated, "SELECT currency FROM PayRule ORDER BY id"))
        } finally {
            migrated.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)
        }
    }
    @Test fun persistsReopensAndRemovesOptionalRate() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TurnoDatabase::class.java, name).build()
        val p = Profile(displayName = "Perfil ficticio", alias = "", zoneId = "UTC")
        val c = Contract(type = "PARTIAL", agreedMinutes = 3600, unit = "MONTH", validFrom = "2026-01-01")
        val r = PayRule(type = "HOURLY_BASE", amountDecimal = "7.17", mode = "BASE", accumulation = "UNDEFINED", validFrom = "2026-01-01")
        try {
            open().let { db -> try { db.configuration().save(p, c, r) } finally { db.close() } }
            open().let { db -> try {
                assertEquals(p, db.configuration().observeProfile().first())
                assertEquals(c, db.configuration().contract())
                assertEquals(r, db.configuration().rate())
                db.configuration().save(p.copy(alias = "alias ficticio"), c, null)
                assertNull(db.configuration().rate())
            } finally { db.close() } }
        } finally { context.deleteDatabase(name) }
    }
}
