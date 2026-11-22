package com.turnocontrol.data.accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
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
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** First-time linking on synthetic guest/account databases; the fake server stands in for the account's remote data. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AccountLinkingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val testName = org.junit.rules.TestName()
    // Ids unique to each test: a database path is never reused right after deletion (intermittent SQLITE_CANTOPEN on
    // Windows when a WAL file of a just-deleted path is still being released).
    private fun idFor(suffix: String): AccountId {
        val hex = java.security.MessageDigest.getInstance("SHA-256").digest((testName.methodName + suffix).toByteArray()).joinToString("") { "%02x".format(it) }
        return AccountId.parse("${hex.take(8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")!!
    }
    private val a by lazy { idFor("a") }
    private val b by lazy { idFor("b") }
    private val layout get() = StorageLayout(File(temporary.root, "files"))
    private val gate = OperationGate()
    private val server = FakeSyncServer { LocalDate.parse("2026-12-01") }
    private val opened = mutableListOf<TurnoDatabase>()
    private val linker = AccountLinker(gate)

    @After fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        listOf(StorageNamespace.Guest, StorageNamespace.forAccount(a), StorageNamespace.forAccount(b)).forEach { context.deleteDatabase(layout.locate(it).databaseName) }
    }
    private fun build(location: StorageLocation, guard: SessionGuard = SessionGuard()) =
        StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it }
    private fun coordinator() = AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")), SwitchJournal(File(temporary.root, "nb")), gate, ::build)
    private fun work(date: String, text: String) = TimeCellParser.parse(text).let { PlannedDay(LocalDate.parse(date), it.kind, it.intervals) }
    private fun kind(date: String, kind: DayKind) = PlannedDay(LocalDate.parse(date), kind, emptyList())
    private suspend fun TurnoDatabase.put(vararg days: PlannedDay) = ScheduleRepository(this, gate).let { it.save(it.snapshot(), days.toList()) }
    private suspend fun TurnoDatabase.days() = ScheduleRepository(this).snapshot().map { it.planned() }
    private val dayKey = { d: String -> SyncCodecs.dayKey(LocalDate.parse(d)) }

    /** Guest data written as a guest, then the account session opened (guest closed), plus a read-only guest handle. */
    private suspend fun setup(account: AccountId = a, guestDays: List<PlannedDay>, remoteDays: List<PlannedDay> = emptyList(), guestRate: String? = null, remoteRate: String? = null):
        Triple<AccountStorageCoordinator, StorageSession, TurnoDatabase> {
        val storage = coordinator()
        val guest = storage.start()
        if (guestDays.isNotEmpty()) guest.database.put(*guestDays.toTypedArray())
        guestRate?.let { ConfigurationRepository(guest.database, context, gate).save(ConfigurationDraft(name = "Perfil", zone = "Europe/Madrid", hours = "40", unit = "WEEK", from = "2026-01-01", hourlyRate = it)) }
        // Another device of the account already uploaded data.
        if (remoteDays.isNotEmpty() || remoteRate != null) {
            var other = Replica(account.value, "otro-dispositivo")
            remoteDays.forEach { d -> other = SyncEngine.edit(other, SyncCodecs.dayKey(d.date), SyncCodecs.encodeDay(DayRecord(d))).replica }
            remoteRate?.let { other = SyncEngine.edit(other, SyncCodecs.rateKey("HOURLY_BASE", LocalDate.parse("2026-01-01")),
                SyncCodecs.encodeRate(RateRecord("HOURLY_BASE", LocalDate.parse("2026-01-01"), null, BigDecimal(it)))).replica }
            SyncEngine.sync(other, server.transport(server.signIn(account.value)))
        }
        val session = when (val outcome = storage.switchTo(StorageNamespace.forAccount(account), account)) {
            is SwitchOutcome.Switched -> outcome.session
            else -> throw AssertionError("No se pudo abrir la cuenta: $outcome")
        }
        SyncStore(session.database, gate).synchronize(server.transport(server.signIn(account.value))) // Account brought up to date.
        val guestReader = build(layout.locate(StorageNamespace.Guest))
        return Triple(storage, session, guestReader)
    }

    @Test fun emptyDatasetsAndGuestOnlyAndAccountOnlyDataCombineWithoutDecisions() = runTest {
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "22:00-06:00"), kind("2026-12-03", DayKind.VACATION), kind("2026-12-04", DayKind.SICK_LEAVE)),
            remoteDays = listOf(work("2026-12-10", "08:00-16:00")))
        val guestBefore = guest.days()
        val plan = linker.preview(guest, session)
        // 3 days + the guest's own profile (the placeholder its first save created; the account's local placeholder is ignored).
        assertEquals(4, plan.count(LinkKind.IMPORT)); assertEquals(1, plan.count(LinkKind.KEEP_ACCOUNT)); assertTrue(plan.decisionsNeeded.isEmpty())
        assertEquals(LinkOutcome.Applied(4, 0, 0), linker.apply(guest, session, a, plan.id, emptyMap(), false))
        assertEquals(listOf(DayKind.WORK, DayKind.VACATION, DayKind.SICK_LEAVE, DayKind.WORK), session.database.days().map { it.kind })
        assertEquals(1, session.database.days().first().intervals.single().endDayOffset) // Overnight shift intact.
        assertEquals(guestBefore, guest.days()) // The guest data is never modified.
        assertEquals(4, session.database.sync().outbox().size)
        SyncStore(session.database, gate).synchronize(server.transport(server.signIn(a.value)))
        assertEquals(5, server.records(a.value).size)
        assertEquals("1|2026-12-04|SICK_LEAVE|MANUAL|", server.records(a.value).getValue(dayKey("2026-12-04")).payload!!.text)
    }

    @Test fun emptyGuestAndEmptyAccountLinkWithoutChanges() = runTest {
        val (_, session, guest) = setup(guestDays = emptyList())
        val plan = linker.preview(guest, session)
        assertTrue(plan.entries.isEmpty())
        assertEquals(LinkOutcome.Applied(0, 0, 0), linker.apply(guest, session, a, plan.id, emptyMap(), false))
        assertTrue(session.database.days().isEmpty() && session.database.sync().outbox().isEmpty())
    }

    @Test fun identicalDaysNeedNothingAndConflictsNeedDecisionsAndAcknowledgement() = runTest {
        val overtime = PlannedDay(LocalDate.parse("2026-12-02"), DayKind.WORK, listOf(ProvisionalInterval(LocalTime.of(8, 0), LocalTime.of(16, 0), 0, 0), ProvisionalInterval(LocalTime.of(16, 0), LocalTime.of(18, 0), 0, 0, true)))
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "08:00-16:00"), overtime), guestRate = "10",
            remoteDays = listOf(work("2026-12-01", "08:00-16:00"), work("2026-12-02", "08:00-17:00")), remoteRate = "11")
        val plan = linker.preview(guest, session)
        assertEquals(LinkKind.ALREADY_IDENTICAL, plan.entries.first { it.key == dayKey("2026-12-01") }.kind)
        assertEquals(setOf(dayKey("2026-12-02"), SyncCodecs.rateKey("HOURLY_BASE", LocalDate.parse("2026-01-01"))), plan.decisionsNeeded.toSet())
        assertTrue(linker.apply(guest, session, a, plan.id, emptyMap(), false) is LinkOutcome.NeedsDecisions)
        val keepGuest = plan.decisionsNeeded.associateWith { Resolution.KEEP_LOCAL }
        assertTrue(linker.apply(guest, session, a, plan.id, keepGuest, acknowledgeReplacements = false) is LinkOutcome.NeedsAcknowledgement)
        val before = session.database.days()
        assertEquals(before, session.database.days()) // Nothing written while decisions are pending.
        val outcome = linker.apply(guest, session, a, plan.id, mapOf(dayKey("2026-12-02") to Resolution.KEEP_LOCAL,
            SyncCodecs.rateKey("HOURLY_BASE", LocalDate.parse("2026-01-01")) to Resolution.KEEP_REMOTE), acknowledgeReplacements = true)
        assertTrue(outcome is LinkOutcome.Applied)
        assertEquals(120, session.database.days().first { it.date == LocalDate.parse("2026-12-02") }.extraMinutes()) // Guest overtime kept.
        assertEquals("11", session.database.configuration().rates().first { it.type == "HOURLY_BASE" }.amountDecimal) // Account rate kept.
        val outbox = session.database.sync().outbox()
        assertEquals(1L, outbox.first { it.recordKey == "2026-12-02" }.baseVersion) // Replacement based on the account's version.
        // Recovery copies of both sides exist.
        val dir = File(layout.locate(StorageNamespace.forAccount(a)).recoveryDirectory.parentFile, "linking")
        assertTrue(File(dir, "pre-link-guest.json").exists() && File(dir, "pre-link-account.json").exists())
    }

    @Test fun overnightCollisionsAreInvalidUnlessSkippedAndDeletionsNeedADecision() = runTest {
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "22:00-06:00"), work("2026-12-05", "08:00-12:00")),
            remoteDays = listOf(work("2026-12-02", "05:00-13:00"), work("2026-12-05", "08:00-12:00")))
        // The account deletes 12-05 on another device before linking.
        val other = Replica(a.value, "otro").let { r -> SyncEngine.sync(r, server.transport(server.signIn(a.value))).replica }
        SyncEngine.sync(SyncEngine.edit(other, dayKey("2026-12-05"), null).replica, server.transport(server.signIn(a.value)))
        SyncStore(session.database, gate).synchronize(server.transport(server.signIn(a.value)))
        val plan = linker.preview(guest, session)
        assertEquals(LinkKind.DELETED_IN_ACCOUNT, plan.entries.first { it.key == dayKey("2026-12-05") }.kind)
        val keepDeleted = mapOf(dayKey("2026-12-05") to Resolution.KEEP_REMOTE)
        assertTrue(linker.apply(guest, session, a, plan.id, keepDeleted, false) is LinkOutcome.Invalid) // 12-01 overnight overlaps 12-02.
        assertEquals(LinkOutcome.Applied(1, 0, 2), linker.apply(guest, session, a, plan.id, keepDeleted + (dayKey("2026-12-01") to Resolution.KEEP_REMOTE), false)) // Only the profile.
        assertEquals(listOf(LocalDate.parse("2026-12-02")), session.database.days().map { it.date })
    }

    @Test fun ownershipCancellationAndStalePreviewsAreRefusedWithoutChanges() = runTest {
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "08:00-16:00")))
        val plan = linker.preview(guest, session)
        assertTrue(linker.apply(guest, session, b, plan.id, emptyMap(), false) is LinkOutcome.Refused) // B's identity on A's data.
        assertEquals(LinkOutcome.Cancelled, linker.apply(guest, session, a, plan.id, mapOf(dayKey("2026-12-01") to Resolution.CANCEL), false))
        assertEquals(LinkOutcome.PlanChanged, linker.apply(guest, session, a, "otro-plan", emptyMap(), false))
        assertTrue(session.database.days().isEmpty())
        assertTrue(session.database.sync().outbox().isEmpty())
        // An open sync conflict in the account must be resolved first.
        session.database.sync().insertConflicts(listOf(SyncConflictRow("SCHEDULED_DAY", "2026-12-09", "VERSION", null, 1, 1, false, 1, null, "x", null)))
        assertTrue(linker.apply(guest, session, a, plan.id, emptyMap(), false) is LinkOutcome.Refused)
    }

    @Test fun interruptedTransactionIsDiscardedAndRetriedAndRepeatedRequestsDoNotDuplicate() = runTest {
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "08:00-16:00"), kind("2026-12-02", DayKind.SICK_LEAVE)))
        val plan = linker.preview(guest, session)
        linker.beforeCommit = { throw IllegalStateException("proceso terminado") }
        assertThrows(IllegalStateException::class.java) { runBlocking { linker.apply(guest, session, a, plan.id, emptyMap(), false) } }
        linker.beforeCommit = {}
        assertTrue(session.database.days().isEmpty()) // Rolled back as a whole.
        assertTrue(linker.apply(guest, session, a, plan.id, emptyMap(), false) is LinkOutcome.Refused) // Must recover first.
        assertEquals(LinkRecovery.DISCARDED, linker.recover(session))
        assertEquals(LinkOutcome.Applied(3, 0, 0), linker.apply(guest, session, a, plan.id, emptyMap(), false)) // 2 days + profile.
        assertEquals(LinkOutcome.AlreadyLinked, linker.apply(guest, session, a, plan.id, emptyMap(), false))
        val again = linker.preview(guest, session)
        assertEquals(3, again.count(LinkKind.ALREADY_IDENTICAL))
        assertEquals(LinkOutcome.Applied(0, 0, 0), linker.apply(guest, session, a, again.id, emptyMap(), false))
        assertEquals(3, session.database.sync().outbox().size) // No duplicate records or operations.
        assertEquals(2, session.database.days().size)
    }

    @Test fun crashAfterCommitIsCompletedOnRecoveryAndJournalsStayWithTheirAccount() = runTest {
        val (_, session, guest) = setup(guestDays = listOf(work("2026-12-01", "08:00-16:00")))
        val plan = linker.preview(guest, session)
        linker.apply(guest, session, a, plan.id, emptyMap(), false)
        val journal = File(File(layout.locate(StorageNamespace.forAccount(a)).recoveryDirectory.parentFile, "linking"), "link.journal")
        // Simulate a crash between the commit and the completion mark.
        journal.writeText("APPLYING ${plan.id} ${StorageNamespace.forAccount(a).tag}\n${dayKey("2026-12-01")}\t" +
            java.security.MessageDigest.getInstance("SHA-256").digest("1|2026-12-01|WORK|MANUAL|08:00+0-16:00+0:N".toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals(LinkRecovery.COMPLETED, linker.recover(session))
        assertTrue(journal.readText().startsWith("COMPLETE"))
        // A's journal copied into B's directory is never acted on by B.
        val locationB = layout.locate(StorageNamespace.forAccount(b))
        val sessionB = StorageSession(locationB, build(locationB), 99, SessionGuard())
        val dirB = File(layout.locate(StorageNamespace.forAccount(b)).recoveryDirectory.parentFile, "linking").also { it.mkdirs() }
        File(dirB, "link.journal").writeText("APPLYING ${plan.id} ${StorageNamespace.forAccount(a).tag}\nx\ty")
        assertEquals(LinkRecovery.NONE, linker.recover(sessionB))
        assertTrue(File(dirB, "link.journal").exists())
    }

    @Test fun backupFailureAbortsAndLinkingBlocksAccountSwitching() = runTest {
        val (storage, session, guest) = setup(guestDays = listOf(work("2026-12-01", "08:00-16:00")))
        val plan = linker.preview(guest, session)
        val dir = File(layout.locate(StorageNamespace.forAccount(a)).recoveryDirectory.parentFile, "linking")
        dir.parentFile!!.mkdirs(); dir.writeText("no soy un directorio") // Backups cannot be written.
        assertTrue(linker.apply(guest, session, a, plan.id, emptyMap(), false) is LinkOutcome.BackupFailed)
        assertTrue(session.database.days().isEmpty())
        dir.delete()
        linker.beforeCommit = { assertTrue(storage.switchTo(StorageNamespace.Guest, null) is SwitchOutcome.Busy) } // No switch mid-linking.
        assertTrue(linker.apply(guest, session, a, plan.id, emptyMap(), false) is LinkOutcome.Applied)
    }
}
