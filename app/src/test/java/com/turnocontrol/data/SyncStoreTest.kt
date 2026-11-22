package com.turnocontrol.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
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
import java.time.YearMonth
import java.util.UUID

/** Persistent synchronization on synthetic, disposable database files. The in-memory fake server stands in for Supabase. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SyncStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.parse("2026-12-01")
    private val server = FakeSyncServer { today }
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<TurnoDatabase>()
    private var stores = 0

    private fun open(name: String): TurnoDatabase {
        if (name !in names) { context.deleteDatabase(name); names += name }
        return Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { opened += it }
    }
    private fun TurnoDatabase.reopen(name: String): TurnoDatabase { close(); opened -= this; return open(name) }
    @After fun cleanup() { opened.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }

    private fun day(date: String, text: String) = TimeCellParser.parse(text).let { PlannedDay(LocalDate.parse(date), it.kind, it.intervals) }
    private fun absent(date: String, kind: DayKind) = PlannedDay(LocalDate.parse(date), kind, emptyList())
    private suspend fun TurnoDatabase.put(vararg days: PlannedDay, origin: String = "MANUAL") = ScheduleRepository(this).let { it.save(it.snapshot(), days.toList(), origin = origin) }
    private suspend fun TurnoDatabase.delete(date: String) = ScheduleRepository(this).let { it.save(it.snapshot(), emptyList(), setOf(LocalDate.parse(date))) }
    private suspend fun TurnoDatabase.days() = ScheduleRepository(this).snapshot().map { it.planned() }
    private suspend fun TurnoDatabase.linked(account: String = "ana") = also { SyncStore(it).link(account, Instant.parse("2026-12-01T08:00:00Z")) }
    private fun configuration(db: TurnoDatabase) = ConfigurationRepository(db, PreferenceDataStoreFactory.create(produceFile = { File(temporary.root, "p${stores++}.preferences_pb") }))
    private val draft = ConfigurationDraft(name = "Perfil ficticio", zone = "Europe/Madrid", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15"))
    private val dayKey = { date: String -> SyncCodecs.dayKey(LocalDate.parse(date)) }

    // Persistence

    @Test fun unlinkedEditsStayLocalAndLinkingNeverEnqueuesHistory() = runTest {
        val db = open("sync-unlinked.db")
        db.put(day("2026-12-02", "08:00-16:00"), absent("2026-12-03", DayKind.SICK_LEAVE))
        assertNull(db.sync().state())
        assertTrue(db.sync().outbox().isEmpty())
        db.linked()
        assertTrue(db.sync().outbox().isEmpty()) // Existing records wait for the explicit first-link flow.
        UUID.fromString(SyncStore(db).deviceId())
        db.put(day("2026-12-04", "09:00-13:00"))
        val op = db.sync().outbox().single()
        UUID.fromString(op.operationId)
        assertEquals("UPSERT" to "2026-12-04", op.op to op.recordKey)
    }

    @Test fun pendingOperationAndIdentifiersSurviveReopening() = runTest {
        var db = open("sync-reopen.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val device = SyncStore(db).deviceId()
        val queued = db.sync().outbox()
        db = db.reopen("sync-reopen.db")
        assertEquals(device, SyncStore(db).deviceId())
        assertEquals(queued, db.sync().outbox())
    }

    @Test fun noOpSavesCoalescingAndTombstones() = runTest {
        var db = open("sync-outbox.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        db.put(day("2026-12-02", "08:00-16:00")) // Unchanged content: nothing new.
        assertEquals(1, db.sync().outbox().size)
        db.put(day("2026-12-02", "09:00-17:00")) // Unsent edits of one record coalesce.
        assertEquals("1|2026-12-02|WORK|MANUAL|09:00+0-17:00+0:N", db.sync().outbox().single().payload)
        db.delete("2026-12-02") // Never reached the server: nothing to send at all.
        assertTrue(db.sync().outbox().isEmpty())
        db.put(day("2026-12-03", "08:00-16:00"))
        assertEquals(SyncStatus.COMPLETED, SyncStore(db).synchronize(server.transport(server.signIn("ana"))).status)
        db.delete("2026-12-03")
        db = db.reopen("sync-outbox.db")
        val tombstone = db.sync().outbox().single()
        assertEquals("DELETE" to null, tombstone.op to tombstone.payload)
        assertEquals(1L, tombstone.baseVersion)
    }

    @Test fun duplicateOperationIdsAreRejectedAtomically() = runTest {
        val db = open("sync-duplicate.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val existing = db.sync().outbox().single()
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.withTransaction { db.sync().insertOutbox(listOf(existing.copy(position = 0, recordKey = "2026-12-09"))) } }
        }
        assertEquals(listOf(existing), db.sync().outbox())
    }

    @Test fun sickLeaveIsQueuedWithOnlyDateAndState() = runTest {
        val db = open("sync-sick.db").linked()
        db.put(absent("2026-12-10", DayKind.SICK_LEAVE))
        assertEquals("1|2026-12-10|SICK_LEAVE|MANUAL|", db.sync().outbox().single().payload)
    }

    @Test fun configurationCaptureIsDiffBased() = runTest {
        val db = open("sync-config.db")
        val config = configuration(db)
        config.save(draft)
        db.linked()
        config.save(draft) // Rewriting the same settings is not a change.
        assertTrue(db.sync().outbox().isEmpty())
        config.save(draft.copy(extraRates = mapOf("EXTRA_HOUR" to "16")))
        assertEquals(listOf("PAY_RULE:EXTRA_HOUR@2026-01-01"), db.sync().outbox().map { "${it.entity}:${it.recordKey}" })
        config.save(draft.copy(name = "Otro nombre", extraRates = mapOf("EXTRA_HOUR" to "16")))
        assertEquals(setOf("PAY_RULE", "PROFILE"), db.sync().outbox().map { it.entity }.toSet())
    }

    // Transactions and recovery

    @Test fun editAndOutgoingChangeCommitOrRollBackTogether() = runTest {
        var db = open("sync-atomic.db").linked()
        // Termination before the commit: the outer transaction never commits, so neither the day nor its change exist.
        runCatching { db.withTransaction { db.put(day("2026-12-02", "08:00-16:00")); throw IllegalStateException("proceso terminado") } }
        assertTrue(db.days().isEmpty())
        assertTrue(db.sync().outbox().isEmpty())
        // Termination right after the commit: both are on disk.
        db.put(day("2026-12-02", "08:00-16:00"))
        db = db.reopen("sync-atomic.db")
        assertEquals(1, db.days().size)
        assertEquals(1, db.sync().outbox().size)
    }

    @Test fun interruptedPushIsResentWithTheSameIdsAfterRestart() = runTest {
        var db = open("sync-interrupted.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val id = db.sync().outbox().single().operationId
        val token = server.signIn("ana")
        server.failNext(FakeSyncServer.Failure.AFTER_APPLY)
        assertEquals(SyncStatus.INTERRUPTED, SyncStore(db).synchronize(server.transport(token)).status)
        db = db.reopen("sync-interrupted.db")
        assertEquals("IN_FLIGHT" to id, db.sync().outbox().single().let { it.state to it.operationId })
        assertEquals(SyncStatus.COMPLETED, SyncStore(db).synchronize(server.transport(token)).status)
        assertTrue(db.sync().outbox().isEmpty())
        assertEquals(1L, server.records("ana").getValue(dayKey("2026-12-02")).version)
        val requests = server.received.size
        SyncStore(db).synchronize(server.transport(token))
        assertEquals(requests, server.received.size) // Acknowledged operations are never resent.
    }

    @Test fun failedWriteAfterAPushIsRecoveredWithTheSameOperationId() = runTest {
        val db = open("sync-commit-failure.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val store = SyncStore(db)
        val id = db.sync().outbox().single().operationId
        var writes = 0
        // The second write (applying the push results) fails: the server applied the change, the device did not record it.
        store.beforeCommitWrites = { if (++writes == 2) throw IllegalStateException("fallo inyectado") }
        assertThrows(IllegalStateException::class.java) { runBlocking { store.synchronize(server.transport(server.signIn("ana"))) } }
        assertEquals("IN_FLIGHT" to id, db.sync().outbox().single().let { it.state to it.operationId }) // Marked before sending.
        assertEquals(0L, db.sync().state()!!.cursor)
        store.beforeCommitWrites = {}
        assertEquals(SyncStatus.COMPLETED, store.synchronize(server.transport(server.signIn("ana"))).status)
        assertTrue(db.sync().outbox().isEmpty())
        assertEquals(1L, server.records("ana").getValue(dayKey("2026-12-02")).version) // Replayed id, applied once.
    }

    @Test fun editOfTheSameDayAfterAnUnrecordedPushIsAFollowUpNotAConflict() = runTest {
        val db = open("sync-follow-up.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val store = SyncStore(db)
        var writes = 0
        store.beforeCommitWrites = { if (++writes == 2) throw IllegalStateException("proceso terminado tras el envío") }
        runCatching { store.synchronize(server.transport(server.signIn("ana"))) }
        store.beforeCommitWrites = {}
        db.put(day("2026-12-02", "09:00-18:00")) // The user edits the same day before the next run.
        val follow = db.sync().outbox().last()
        assertTrue(follow.afterPredecessor && follow.baseVersion == null)
        assertEquals(SyncStatus.COMPLETED, store.synchronize(server.transport(server.signIn("ana"))).status)
        assertTrue(db.sync().conflicts().isEmpty())
        val remote = server.records("ana").getValue(dayKey("2026-12-02"))
        assertEquals(2L to "1|2026-12-02|WORK|MANUAL|09:00+0-18:00+0:N", remote.version to remote.payload!!.text)
    }

    @Test fun editOfTheSameDayDuringThePushIsSentAfterwardsWithoutConflict() = runTest {
        val db = open("sync-during-push.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val real = server.transport(server.signIn("ana"))
        var edited = false
        val editing = object : SyncTransport by real {
            override fun push(deviceId: String, changes: List<PushRequest>): List<PushResult> = real.push(deviceId, changes).also {
                if (!edited) { edited = true; runBlocking { db.put(day("2026-12-02", "10:00-14:00")) } }
            }
        }
        assertEquals(SyncStatus.COMPLETED, SyncStore(db).synchronize(editing).status)
        assertTrue(db.sync().conflicts().isEmpty() && db.sync().outbox().isEmpty())
        val remote = server.records("ana").getValue(dayKey("2026-12-02"))
        assertEquals(2L to "1|2026-12-02|WORK|MANUAL|10:00+0-14:00+0:N", remote.version to remote.payload!!.text)
        assertEquals(day("2026-12-02", "10:00-14:00"), db.days().single())
    }

    @Test fun localEditDuringAPullIsKeptAndSentLater() = runTest {
        val db = open("sync-concurrent.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        val real = server.transport(server.signIn("ana"))
        val racing = object : SyncTransport by real {
            override fun pull(since: Long, limit: Int): PullPage { runBlocking { db.put(day("2026-12-05", "10:00-14:00")) }; return real.pull(since, limit) }
        }
        // Each step reloads the state, so a concurrent edit is neither lost nor a reason to abort.
        assertEquals(SyncStatus.COMPLETED, SyncStore(db).synchronize(racing).status)
        assertEquals(2, db.days().size)
        assertEquals(listOf("2026-12-05"), db.sync().outbox().map { it.recordKey })
        assertEquals(SyncStatus.COMPLETED, SyncStore(db).synchronize(real).status)
        assertEquals(setOf(dayKey("2026-12-02"), dayKey("2026-12-05")), server.records("ana").keys)
    }

    @Test fun localEditOfTheDayAPullUpdatesIsAConflictAndTheLocalValueIsKept() = runTest {
        val a = open("sync-pull-race-a.db").linked(); val b = open("sync-pull-race-b.db").linked()
        val ta = server.signIn("ana"); val tb = server.signIn("ana")
        a.put(day("2026-12-02", "08:00-16:00")); SyncStore(a).synchronize(server.transport(ta)); SyncStore(b).synchronize(server.transport(tb))
        a.put(day("2026-12-02", "08:00-17:00")); SyncStore(a).synchronize(server.transport(ta))
        val real = server.transport(tb)
        val racing = object : SyncTransport by real {
            override fun pull(since: Long, limit: Int): PullPage { runBlocking { b.put(absent("2026-12-02", DayKind.VACATION)) }; return real.pull(since, limit) }
        }
        SyncStore(b).synchronize(racing)
        assertEquals("VERSION", b.sync().conflicts().single().kind)
        assertEquals(DayKind.VACATION, b.days().single().kind)
    }

    // Multi-device convergence over Room

    @Test fun twoDevicesConvergeIncludingSickLeaveConfigurationAndOvertime() = runTest {
        val a = open("sync-device-a.db").linked(); val b = open("sync-device-b.db").linked()
        configuration(a).save(draft)
        a.put(day("2026-12-02", "22:00-06:00"), day("2026-12-04", "08:00-12:00 / 16:00-20:00"), absent("2026-12-05", DayKind.VACATION), absent("2026-12-10", DayKind.SICK_LEAVE))
        a.put(PlannedDay(LocalDate.parse("2026-12-06"), DayKind.WORK, listOf(ProvisionalInterval(java.time.LocalTime.of(14, 0), java.time.LocalTime.of(16, 30), 0, 0, isExtra = true))))
        // History written before linking is not uploaded; edit configuration after linking so it is queued.
        configuration(a).save(draft.copy(name = "Perfil sincronizado"))
        val ta = server.signIn("ana"); val tb = server.signIn("ana")
        assertEquals(SyncStatus.COMPLETED, SyncStore(a).synchronize(server.transport(ta)).status)
        assertEquals(SyncStatus.COMPLETED, SyncStore(b).synchronize(server.transport(tb)).status)
        assertEquals(a.days(), b.days())
        assertEquals(150, b.days().first { it.date == LocalDate.parse("2026-12-06") }.extraMinutes())
        assertEquals(DayKind.SICK_LEAVE, b.days().first { it.date == LocalDate.parse("2026-12-10") }.kind)
        assertEquals("Perfil sincronizado", b.schedule().profile()!!.displayName)
        assertEquals(ScheduleSummary.month(a.days(), YearMonth.of(2026, 12)), ScheduleSummary.month(b.days(), YearMonth.of(2026, 12)))
        // Remote writes do not leave fake outgoing changes behind.
        assertTrue(b.sync().outbox().isEmpty())
    }

    @Test fun conflictsPersistBlockEditsAndResolveIntoTheCalendar() = runTest {
        var b = open("sync-conflict-b.db").linked(); val a = open("sync-conflict-a.db").linked()
        val ta = server.signIn("ana"); val tb = server.signIn("ana")
        a.put(day("2026-12-02", "08:00-16:00")); SyncStore(a).synchronize(server.transport(ta)); SyncStore(b).synchronize(server.transport(tb))
        a.put(day("2026-12-02", "08:00-17:00")); SyncStore(a).synchronize(server.transport(ta))
        b.put(absent("2026-12-02", DayKind.SICK_LEAVE))
        assertTrue(SyncStore(b).synchronize(server.transport(tb)).requiresUserDecision)
        b = b.reopen("sync-conflict-b.db")
        val row = b.sync().conflicts().single()
        assertEquals("VERSION" to "1|2026-12-02|SICK_LEAVE|MANUAL|", row.kind to row.localPayload)
        assertEquals(DayKind.SICK_LEAVE, b.days().single().kind) // Never silently replaced.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { b.put(day("2026-12-02", "06:00-10:00")) } }
        assertEquals(ResolutionOutcome.KEPT_REMOTE, SyncStore(b).resolve(dayKey("2026-12-02"), Resolution.KEEP_REMOTE, today).outcome)
        assertEquals(day("2026-12-02", "08:00-17:00"), b.days().single())
        assertEquals("LOCAL" to "1|2026-12-02|SICK_LEAVE|MANUAL|", b.sync().recovered().single().let { it.source to it.payload })
        assertTrue(b.sync().conflicts().isEmpty())
    }

    // Quarantine

    @Test fun quarantineSurvivesRestartAndIsReplayedOnlyAfterAnUpdate() = runTest {
        var db = open("sync-quarantine.db").linked()
        server.injectRaw("ana", dayKey("2026-12-02"), SyncPayload(SyncEntity.SCHEDULED_DAY, "2|2026-12-02|ON_CALL|MANUAL|"), 2)
        server.injectRaw("ana", dayKey("2026-12-03"), SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-12-03|FREE|MANUAL|"), 1)
        val token = server.signIn("ana")
        SyncStore(db).synchronize(server.transport(token))
        assertEquals(listOf(DayKind.FREE), db.days().map { it.kind })
        db = db.reopen("sync-quarantine.db")
        val held = db.sync().quarantine().single()
        assertEquals(SYNC_DATA_FORMAT to DataFormat.ROOM_SCHEMA_VERSION, held.evaluatedFormat to held.evaluatedSchema)
        SyncStore(db).replayQuarantine()
        assertEquals(listOf(held), db.sync().quarantine()) // Still unsupported: kept, not applied.
        // A record judged by an older build that this build understands is applied exactly once.
        db.sync().insertQuarantine(listOf(SyncQuarantine("SCHEDULED_DAY", "2026-12-04", 9, 9, false, 1, "1|2026-12-04|VACATION|MANUAL|", "remote-op", "antiguo", 0, 4)))
        SyncStore(db).replayQuarantine()
        assertEquals(listOf(DayKind.FREE, DayKind.VACATION), db.days().map { it.kind })
        assertEquals(listOf("2026-12-02"), db.sync().quarantine().map { it.recordKey })
        assertEquals(9L, db.sync().recordState("SCHEDULED_DAY", "2026-12-04")!!.serverVersion)
        SyncStore(db).replayQuarantine()
        assertEquals(2, db.days().size) // No duplicate application.
        // An old row that is still unsupported is re-judged once, stamped, and not retried afterwards.
        db.sync().insertQuarantine(listOf(SyncQuarantine("SCHEDULED_DAY", "2026-12-07", 3, 3, false, 7, "7|futuro", "remote-op2", "antiguo", 0, 4)))
        SyncStore(db).replayQuarantine()
        val stamped = db.sync().quarantine().first { it.recordKey == "2026-12-07" }
        assertEquals(SYNC_DATA_FORMAT to DataFormat.ROOM_SCHEMA_VERSION, stamped.evaluatedFormat to stamped.evaluatedSchema)
        assertEquals(2, db.days().size)
    }

    @Test fun recordsThisSchemaCannotStoreAreParkedNotLost() = runTest {
        val db = open("sync-parked.db").linked()
        val slip = SyncCodecs.encodePayslip(PayslipRecord(YearMonth.of(2026, 11), java.math.BigDecimal("1500"), null))
        server.injectRaw("ana", SyncCodecs.payslipKey(YearMonth.of(2026, 11)), slip.payload, 1)
        SyncStore(db).synchronize(server.transport(server.signIn("ana")))
        val parked = db.sync().quarantine().single()
        assertEquals(SyncStore.PARKED to slip.payload.text, parked.reason to parked.payload)
        assertEquals(1L, db.sync().recordState("PAYSLIP", "2026-11")!!.serverVersion)
        SyncStore(db).replayQuarantine()
        assertEquals(listOf(parked), db.sync().quarantine()) // Kept until a schema that stores payslips.
    }

    // Accounts and restore

    @Test fun ownershipIsEnforcedWhereItCanBeEstablished() = runTest {
        val db = open("sync-owner.db").linked("ana")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SyncStore(db).link("bob", Instant.EPOCH) } }
        db.put(day("2026-12-02", "08:00-16:00"))
        val queued = db.sync().outbox()
        assertEquals(SyncStatus.ACCOUNT_MISMATCH, SyncStore(db).synchronize(server.transport(server.signIn("bob"))).status)
        assertEquals(queued, db.sync().outbox())
        assertTrue(server.received.isEmpty())
        val unlinked = open("sync-owner-unlinked.db")
        assertEquals(SyncStatus.NOT_LINKED, SyncStore(unlinked).synchronize(server.transport(server.signIn("ana"))).status)
    }

    @Test fun restoreOfAnOldBackupAfterMigrationWaitsForReview() = runTest {
        val db = open("sync-restore.db").linked()
        db.put(day("2026-12-02", "08:00-16:00"))
        var theme = false
        val backup = BackupRepository(db, { theme }, { theme = it }, File(temporary.root, "rec"), AppVersion("test", 1))
        val legacy = BackupSnapshot(listOf(Profile(1, "Perfil ficticio", "", "UTC")), emptyList(), emptyList(),
            listOf(ScheduledDay(1, 1, "2026-11-30", "SICK_LEAVE", "UTC", "MANUAL")), emptyList(), false)
        backup.restore(BackupCodec.decode(BackupCodec.encode(BackupFile(BackupHeader(1, "0.2.0", 2, 4, 2, Instant.EPOCH), legacy))))
        assertEquals(listOf(absent("2026-11-30", DayKind.SICK_LEAVE)), db.days())
        assertTrue(db.sync().outbox().isEmpty())
        assertEquals(SyncStatus.REVIEW_REQUIRED, SyncStore(db).synchronize(server.transport(server.signIn("ana"))).status)
        assertTrue(server.received.isEmpty()) // Nothing was uploaded or overwritten remotely.
    }
}
