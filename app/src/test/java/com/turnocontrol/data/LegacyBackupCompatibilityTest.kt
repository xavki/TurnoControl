package com.turnocontrol.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.domain.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

/**
 * Backups written by TurnoControl 0.2.0 (format 1, Room schema 4) must stay restorable whatever schema this build
 * uses. Synthetic fixture only; restored into an isolated in-memory database.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class LegacyBackupCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: TurnoDatabase
    private var theme = false

    /** Every kind of record 0.2.0 could store: split, overnight and extra shifts, vacation, sick leave, free days, 6 rates. */
    private val legacy = BackupSnapshot(
        listOf(Profile(1, "Perfil ficticio", "ALFA", "Europe/Madrid")),
        listOf(Contract(1, 1, "PARTIAL", 4800, "MONTH", "2026-01-01")),
        listOf("HOURLY_BASE", "EXTRA_PAY", "COMPLEMENTARY", "NIGHT", "SPECIAL_NIGHT", "EXTRA_HOUR").mapIndexed { i, type ->
            PayRule(i + 1L, 1, type, "${i + 7}.25", if (type in setOf("NIGHT", "SPECIAL_NIGHT", "EXTRA_PAY")) "PLUS" else "BASE", "UNDEFINED", "2026-01-01") },
        listOf(ScheduledDay(10, 1, "2026-03-02", "WORK", "Europe/Madrid", "MANUAL", 2), ScheduledDay(11, 1, "2026-03-03", "WORK", "Europe/Madrid", "OCR_REVIEWED"),
            ScheduledDay(12, 1, "2026-03-05", "VACATION", "Europe/Madrid", "MANUAL"), ScheduledDay(13, 1, "2026-03-06", "SICK_LEAVE", "Europe/Madrid", "MANUAL"),
            ScheduledDay(14, 1, "2026-03-07", "FREE", "Europe/Madrid", "OCR_REVIEWED")),
        listOf(ScheduledInterval(100, 10, 0, 480, 720, 0, 0), ScheduledInterval(101, 10, 1, 960, 1200, 0, 0, isExtra = true),
            ScheduledInterval(102, 11, 0, 1320, 360, 0, 1)), darkTheme = true)

    private fun legacyText(schema: Int = 4, formatVersion: Int = 1) =
        BackupCodec.encode(BackupFile(BackupHeader(formatVersion, "0.2.0", 2, schema, 2, Instant.parse("2026-11-21T20:00:00Z")), legacy))

    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).allowMainThreadQueries().build() }
    @After fun cleanup() { db.close() }
    private fun repository() = BackupRepository(db, { theme }, { theme = it }, File(temporary.root, "recovery"), AppVersion("test", 1), clock = { Instant.parse("2026-11-22T10:00:00Z") })
    private fun read(text: String) = repository().readFrom { ByteArrayInputStream(text.toByteArray()) }

    @Test fun schema4BackupIsRestoredCompletelyIntoTheCurrentSchema() = runTest {
        val text = legacyText()
        assertEquals(4, JSONObject(text).getInt("schemaVersion"))
        val file = read(text)
        assertEquals(4, file.header.schemaVersion)
        val repository = repository()
        val restored = repository.restore(file)
        assertEquals(legacy.normalized(), restored)
        assertTrue(theme)
        val days = ScheduleRepository(db).snapshot().map { it.planned() }
        assertEquals(listOf(DayKind.WORK, DayKind.WORK, DayKind.VACATION, DayKind.SICK_LEAVE, DayKind.FREE), days.map { it.kind })
        assertEquals(240, days.first().extraMinutes()) // 16:00-20:00 declared extra.
        assertEquals(1, days[1].intervals.single().endDayOffset)
        assertEquals(6, db.configuration().rates().size)
        // Restoring the same file again replaces, never duplicates.
        repository.restore(read(text))
        assertEquals(legacy.normalized(), repository.snapshot())
        assertEquals(5, db.backup().days().size)
        assertEquals(3, db.backup().intervals().size)
    }

    @Test fun restoreNeverReplaysStaleOperationsOrImpersonatesTheDevice() = runTest {
        // Unlinked database: restore creates no synchronization state at all.
        repository().restore(read(legacyText()))
        assertNull(db.sync().state())
        // Linked database with pending work: the restore keeps identity and server knowledge, drops the stale queue,
        // and holds synchronization for an explicit review instead of uploading the restored data.
        val state = SyncState(deviceId = "00000000-0000-4000-8000-000000000001", accountId = "cuenta-sintetica", cursor = 9, nextOperation = 4, restoreReviewRequired = false, linkedAt = "2026-11-22T09:00:00Z")
        db.sync().insertState(state)
        db.sync().insertRecordStates(listOf(SyncRecordState("SCHEDULED_DAY", "2026-03-02", 3, false, "1|2026-03-02|FREE|MANUAL|")))
        db.sync().insertOutbox(listOf(SyncOutbox(operationId = "a3c4b9e2-0000-4000-8000-000000000002", entity = "SCHEDULED_DAY", recordKey = "2026-03-02", op = "UPSERT",
            payload = "1|2026-03-02|VACATION|MANUAL|", baseVersion = 3, afterPredecessor = false, state = "PENDING", attempts = 0)))
        db.sync().insertConflicts(listOf(SyncConflictRow("SCHEDULED_DAY", "2026-03-03", "VERSION", null, 2, 5, false, 1, "1|2026-03-03|FREE|MANUAL|", "x", null)))
        repository().restore(read(legacyText()))
        assertEquals(state.copy(restoreReviewRequired = true), db.sync().state())
        assertTrue(db.sync().outbox().isEmpty())
        assertTrue(db.sync().conflicts().isEmpty())
        assertEquals(1, db.sync().recordStates().size)
        assertEquals(legacy.normalized(), repository().snapshot()) // The backup itself never carries synchronization state.
    }

    @Test fun legacyBackupsKeepEveryIntegrityCheck() = runTest {
        val repository = repository()
        repository.restore(read(legacyText()))
        val before = repository.snapshot()
        fun rejected(text: String, fragment: String) {
            val error = assertThrows(BackupException::class.java) { read(text) }
            assertTrue("«${error.message}» debería mencionar «$fragment»", error.message!!.contains(fragment, ignoreCase = true))
        }
        rejected(legacyText().replace("\"Perfil ficticio\"", "\"Perfil alterado\""), "dañada")
        rejected(legacyText(schema = DataFormat.ROOM_SCHEMA_VERSION + 1), "más nueva")
        rejected(legacyText(schema = 3), "no admitida")
        rejected(legacyText(formatVersion = BackupCodec.FORMAT_VERSION + 1), "más nueva")
        rejected(legacyText().substring(0, 200), "incompleto")
        assertEquals(before, repository.snapshot()) // Nothing was written by any rejected file.
    }
}
