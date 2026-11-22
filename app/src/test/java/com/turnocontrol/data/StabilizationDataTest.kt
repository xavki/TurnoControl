package com.turnocontrol.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import com.turnocontrol.domain.sync.*
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
import java.time.LocalDate

/** Phase 3.1: the real DAO queries added in schema 5 and the shared profile-name limit, on synthetic database files. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class StabilizationDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<TurnoDatabase>()
    private var stores = 0

    private fun open(name: String): TurnoDatabase {
        if (name !in names) { context.deleteDatabase(name); names += name }
        return Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { opened += it }
    }
    private fun TurnoDatabase.reopen(name: String): TurnoDatabase { close(); opened -= this; return open(name) }
    @After fun cleanup() { opened.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }
    private fun sql(db: TurnoDatabase, statement: String) = db.openHelper.writableDatabase.execSQL(statement)
    private fun configuration(db: TurnoDatabase) = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "p${stores++}.preferences_pb") }))
    private val draft = ConfigurationDraft(name = "Perfil ficticio", zone = "Europe/Madrid", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15"))

    /** Profile 1 is the database's own data; profile 2 stands for rows outside its namespace, which must never be touched. */
    private fun seed(db: TurnoDatabase) {
        sql(db, "INSERT INTO Profile VALUES (1, 'Perfil ficticio', '', 'UTC')")
        sql(db, "INSERT INTO Profile VALUES (2, 'Otro espacio', '', 'UTC')")
        sql(db, "INSERT INTO Contract VALUES (1, 1, 'FULL', 2400, 'WEEK', '2026-01-01', NULL)")
        sql(db, "INSERT INTO Contract VALUES (2, 2, 'PARTIAL', 1200, 'WEEK', '2026-01-01', NULL)")
        sql(db, "INSERT INTO PayRule VALUES (1, 1, 'HOURLY_BASE', '10', 'BASE', 'UNDEFINED', '2026-01-01', NULL, 'EUR')")
        sql(db, "INSERT INTO PayRule VALUES (6, 1, 'EXTRA_HOUR', '15', 'BASE', 'UNDEFINED', '2026-01-01', NULL, 'EUR')")
        sql(db, "INSERT INTO PayRule VALUES (40, 1, 'WEEKEND_FUTURE', '3', 'PLUS', 'UNDEFINED', '2026-01-01', NULL, 'EUR')")
        sql(db, "INSERT INTO PayRule VALUES (99, 2, 'HOURLY_BASE', '20', 'BASE', 'UNDEFINED', '2026-01-01', NULL, 'EUR')")
        sql(db, "INSERT INTO ScheduledDay VALUES (7, 1, '2026-12-10', 'SICK_LEAVE', 'UTC', 'MANUAL', 1)")
        sql(db, "INSERT INTO ScheduledDay VALUES (8, 2, '2026-12-10', 'WORK', 'UTC', 'MANUAL', 1)")
    }
    private fun ids(db: TurnoDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT id FROM $table ORDER BY id").use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    // Correction A: the real DAO queries.

    @Test fun deleteRateByIdRemovesOnlyThatPeriodOfTheLocalProfile() = runTest {
        var db = open("dao-rate.db"); seed(db)
        val dao = db.configuration()
        dao.deleteRateById(6)
        assertEquals(listOf(1L, 40L, 99L), ids(db, "PayRule")) // Unknown types and the other namespace are kept.
        dao.deleteRateById(99) // Belongs to profile 2: refused by the query itself.
        dao.deleteRateById(12345); dao.deleteRateById(-1); dao.deleteRateById(6) // Missing, invalid or repeated: no-op.
        assertEquals(listOf(1L, 40L, 99L), ids(db, "PayRule"))
        assertEquals(2, db.backup().contracts().size) // No side effect on other tables.
        db = db.reopen("dao-rate.db")
        assertEquals(listOf(1L, 40L, 99L), ids(db, "PayRule"))
    }

    @Test fun deleteContractRemovesOnlyTheLocalContractWithoutCascading() = runTest {
        var db = open("dao-contract.db"); seed(db)
        db.configuration().deleteContract()
        assertNull(db.configuration().contract())
        assertEquals(listOf(2L), ids(db, "Contract")) // Other namespace untouched.
        assertEquals(listOf(1L, 2L), ids(db, "Profile"))
        assertEquals(4, ids(db, "PayRule").size)
        assertEquals(2, db.backup().days().size)
        db.configuration().deleteContract() // Already absent: no-op.
        db = db.reopen("dao-contract.db")
        assertEquals(listOf(2L), ids(db, "Contract"))
        val empty = open("dao-contract-empty.db")
        empty.configuration().deleteContract() // Empty table.
        assertTrue(ids(empty, "Contract").isEmpty())
    }

    @Test fun dayQueryReturnsOnlyTheLocalProfileRow() = runTest {
        var db = open("dao-day.db"); seed(db)
        val day = db.schedule().day("2026-12-10")!!
        assertEquals(7L to "SICK_LEAVE", day.id to day.kind) // Profile 2 has the same date; never returned.
        assertNull(db.schedule().day("2026-12-11"))
        assertNull(db.schedule().day(""))
        assertNull(db.schedule().day("10/12/2026"))
        db = db.reopen("dao-day.db")
        assertEquals(7L, db.schedule().day("2026-12-10")!!.id)
    }

    @Test fun remoteDeletionsReachTheQueriesThroughSynchronization() = runTest {
        val server = FakeSyncServer { LocalDate.parse("2026-12-01") }
        val a = open("dao-sync-a.db").also { SyncStore(it).link("ana", Instant.EPOCH) }
        var b = open("dao-sync-b.db").also { SyncStore(it).link("ana", Instant.EPOCH) }
        val ta = server.signIn("ana"); val tb = server.signIn("ana")
        configuration(a).save(draft) // After linking: profile, contract and two rates are queued.
        SyncStore(a).synchronize(server.transport(ta)); SyncStore(b).synchronize(server.transport(tb))
        assertEquals(setOf("HOURLY_BASE", "EXTRA_HOUR"), b.configuration().rates().map { it.type }.toSet())
        configuration(a).save(draft.copy(extraRates = emptyMap())) // Rate removed on A: a tombstone.
        server.injectRaw("ana", SyncCodecs.contractKey, null, 1, deleted = true) // Contract deleted elsewhere.
        SyncStore(a).synchronize(server.transport(ta)); SyncStore(b).synchronize(server.transport(tb))
        b = b.reopen("dao-sync-b.db")
        assertEquals(listOf("HOURLY_BASE"), b.configuration().rates().map { it.type })
        assertNull(b.configuration().contract())
        assertEquals("Perfil ficticio", b.schedule().profile()!!.displayName)
    }

    // Correction B: one shared limit, legacy names preserved, nothing truncated.

    @Test fun nameLimitIsEnforcedOfflineWithoutLosingData() = runTest {
        val db = open("name-offline.db")
        val config = configuration(db)
        config.save(draft.copy(name = "Àngels Núñez i Colomer, l·l"))
        config.save(draft.copy(name = "n".repeat(200)))
        assertEquals(200, db.schedule().profile()!!.displayName.length)
        val before = db.backup().let { listOf(it.profiles(), it.contracts(), it.payRules()) }
        val error = assertThrows(ConfigurationValidationException::class.java) { runBlocking { config.save(draft.copy(name = "n".repeat(201), hourlyRate = "99")) } }
        assertEquals("El nombre visible no puede superar 200 caracteres.", error.message)
        assertEquals(before, db.backup().let { listOf(it.profiles(), it.contracts(), it.payRules()) }) // Nothing written.
        assertNull(db.sync().state()) // Offline use never creates synchronization state.
    }

    @Test fun legacyNameLongerThanTheLimitIsKeptWhenUnchanged() = runTest {
        val db = open("name-legacy.db")
        val legacy = "Nombre de una versión antigua ".repeat(10).trim()
        assertTrue(ProfileLimits.length(legacy) > ProfileLimits.MAX_TEXT_LENGTH)
        sql(db, "INSERT INTO Profile VALUES (1, '$legacy', '', 'Europe/Madrid')")
        val config = configuration(db)
        config.save(draft.copy(name = legacy, hourlyRate = "11")) // Other settings still save offline.
        assertEquals(legacy, db.schedule().profile()!!.displayName) // Never truncated or replaced.
        assertEquals("11", db.configuration().rates().first { it.type == "HOURLY_BASE" }.amountDecimal)
        assertThrows(ConfigurationValidationException::class.java) { runBlocking { config.save(draft.copy(name = "$legacy y más")) } }
        assertEquals(legacy, db.schedule().profile()!!.displayName)
    }

    @Test fun linkedSavesAcceptTheLimitAndExplainLegacyNamesThatCannotSync() = runTest {
        val db = open("name-linked.db")
        val config = configuration(db)
        config.save(draft)
        SyncStore(db).link("ana", Instant.EPOCH)
        config.save(draft.copy(name = "ñ".repeat(200)))
        assertEquals("1|${"ñ".repeat(200)}||Europe/Madrid", db.sync().outbox().single { it.entity == "PROFILE" }.payload)
        // A legacy over-limit name stays usable offline, but a linked save explains why it cannot be synchronized.
        val legacy = "x".repeat(250)
        sql(db, "UPDATE Profile SET displayName = '$legacy' WHERE id = 1")
        val queued = db.sync().outbox()
        val error = assertThrows(ConfigurationValidationException::class.java) { runBlocking { config.save(draft.copy(name = legacy, hourlyRate = "12")) } }
        assertTrue(error.message!!.contains("no se pueden sincronizar"))
        assertEquals(queued, db.sync().outbox())
        assertEquals("10", db.configuration().rates().first { it.type == "HOURLY_BASE" }.amountDecimal) // Rolled back as a whole.
        assertEquals(legacy, db.schedule().profile()!!.displayName)
    }
}
