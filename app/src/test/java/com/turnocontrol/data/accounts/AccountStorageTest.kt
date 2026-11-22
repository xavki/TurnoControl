package com.turnocontrol.data.accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

/** Account-isolated storage on synthetic database files inside Robolectric's sandbox; never the real app. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AccountStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val a = AccountId.parse("00000000-0000-4000-8000-00000000000a")!!
    private val b = AccountId.parse("00000000-0000-4000-8000-00000000000b")!!
    private val nsA = StorageNamespace.forAccount(a)
    private val nsB = StorageNamespace.forAccount(b)
    private val files get() = File(temporary.root, "files").also { it.mkdirs() }
    private val layout get() = StorageLayout(files)
    private val gate = OperationGate()
    private val opened = mutableListOf<TurnoDatabase>()
    private var blocker: String? = null

    private fun open(location: StorageLocation, guard: SessionGuard = SessionGuard()) =
        StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it }
    private fun coordinator() = AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nobackup")), SwitchJournal(File(temporary.root, "nobackup")),
        gate, ::open, blockers = { blocker }, clock = { Instant.parse("2026-12-01T10:00:00Z") })
    private val names get() = listOf(StorageNamespace.Guest, nsA, nsB).map { layout.locate(it).databaseName }
    @After fun cleanup() { opened.forEach { runCatching { it.close() } }; names.forEach { context.deleteDatabase(it) } }

    private fun day(date: String, text: String) = TimeCellParser.parse(text).let { PlannedDay(LocalDate.parse(date), it.kind, it.intervals) }
    private suspend fun StorageSession.put(vararg days: PlannedDay) = ScheduleRepository(database, gate).let { it.save(it.snapshot(), days.toList()) }
    private suspend fun StorageSession.dates() = ScheduleRepository(database).snapshot().map { it.day.date }
    private fun SwitchOutcome.session() = (this as SwitchOutcome.Switched).session

    // Naming and layout

    @Test fun namespacesAreOpaqueValidatedAndConfinedToPrivateStorage() {
        val guest = layout.locate(StorageNamespace.Guest)
        assertEquals("turnocontrol.db", guest.databaseName) // Historical guest name, unchanged.
        assertEquals(File(files, "backup-recovery").canonicalFile, guest.recoveryDirectory)
        assertTrue(nsA.key.matches(Regex("[0-9a-f]{32}")))
        assertEquals(nsA, StorageNamespace.forAccount(AccountId.parse(a.value)!!)) // Deterministic.
        assertNotEquals(nsA, nsB)
        assertEquals(3, names.distinct().size) // No collision between guest, A and B.
        assertFalse(layout.locate(nsA).databaseName.contains(a.value)) // The raw id never reaches the file system.
        assertTrue(layout.locate(nsA).recoveryDirectory.toPath().startsWith(files.canonicalFile.toPath()))
        listOf("../../databases", "ABCDEF0123456789ABCDEF0123456789", "0123", "").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { StorageNamespace.Account(key) }
        }
        listOf("account-../../x", "account-", "guest/..", "cuenta").forEach { assertNull(it, StorageNamespace.fromTag(it)) }
        assertEquals(nsA, StorageNamespace.fromTag(nsA.tag))
    }

    // Isolation

    @Test fun guestAndAccountsNeverSeeEachOthersCalendarsOrSyncState() = runTest {
        val storage = coordinator()
        val guest = storage.start()
        guest.put(day("2026-12-01", "08:00-16:00"))
        val sessionA = storage.switchTo(nsA, a).session()
        assertTrue(sessionA.dates().isEmpty()) // Guest data is not moved or merged into the account.
        sessionA.put(day("2026-12-02", "09:00-17:00"))
        val deviceA = sessionA.database.sync().state()!!
        assertEquals(a.value, deviceA.accountId)
        assertEquals(1, sessionA.database.sync().outbox().size) // Account edits are captured for that account only.
        sessionA.database.sync().insertConflicts(listOf(SyncConflictRow("SCHEDULED_DAY", "2026-12-09", "VERSION", null, 1, 1, false, 1, null, "x", null)))
        sessionA.database.sync().insertQuarantine(listOf(SyncQuarantine("SCHEDULED_DAY", "2026-12-08", 1, 1, false, 9, "9|x", "y", "z", 1, 5)))
        val sessionB = storage.switchTo(nsB, b).session()
        assertTrue(sessionB.dates().isEmpty()) // B cannot read A.
        val deviceB = sessionB.database.sync().state()!!
        assertNotEquals(deviceA.deviceId, deviceB.deviceId)
        assertEquals(0L, deviceB.cursor)
        assertTrue(sessionB.database.sync().outbox().isEmpty() && sessionB.database.sync().conflicts().isEmpty() && sessionB.database.sync().quarantine().isEmpty())
        sessionB.put(day("2026-12-03", "10:00-14:00"))
        // Sign-out: back to the guest data only; A's and B's calendars are closed and unreachable.
        val back = storage.signOut().session()
        assertEquals(listOf("2026-12-01"), back.dates())
        assertNull(back.database.sync().state()?.accountId)
        assertTrue(back.database.sync().outbox().isEmpty())
        // Signing in again recovers A exactly as it was, pending change included.
        val again = storage.switchTo(nsA, a).session()
        assertEquals(listOf("2026-12-02"), again.dates())
        assertEquals(1, again.database.sync().outbox().size)
        assertEquals(1, again.database.sync().conflicts().size)
        assertEquals(deviceA.deviceId, again.database.sync().state()!!.deviceId)
        // Nothing was ever deleted.
        names.forEach { assertTrue(it, context.getDatabasePath(it).exists()) }
    }

    @Test fun staleRepositoriesFailInsteadOfReadingTheNextNamespace() = runTest {
        val storage = coordinator()
        storage.start().put(day("2026-12-01", "08:00-16:00"))
        val sessionA = storage.switchTo(nsA, a).session()
        val staleRepo = ScheduleRepository(sessionA.database)
        val staleSync = SyncStore(sessionA.database)
        storage.signOut()
        assertFalse(sessionA.open)
        assertStale { staleRepo.snapshot() }
        assertStale { staleSync.load() }
        assertStale { sessionA.database.sync().state() }
        // Direct (non-coroutine) reopen of the closed database is stopped by the SessionGuard itself.
        assertThrows(StorageSessionClosedException::class.java) { sessionA.database.openHelper.readableDatabase }
        assertTrue(storage.current!!.open) // The guest session is unaffected.
    }

    @Test fun accountDataSurvivesRestartAndTheCommittedNamespaceReopens() = runTest {
        val first = coordinator()
        first.start().put(day("2026-12-01", "08:00-16:00"))
        first.switchTo(nsA, a).session().put(day("2026-12-02", "09:00-17:00"))
        opened.forEach { runCatching { it.close() } } // Process ends.
        val restarted = coordinator().start()
        assertEquals(nsA, restarted.location.namespace)
        assertEquals(listOf("2026-12-02"), restarted.dates())
    }

    // Coordination with running operations

    @Test fun switchingIsRefusedWhileAnythingCriticalIsInProgress() = runTest {
        val storage = coordinator()
        val guest = storage.start()
        // A save in progress.
        val release = CompletableDeferred<Unit>()
        val saving = launch { gate.operation("calendar-save") { release.await() } }
        yield()
        assertTrue(storage.switchTo(nsA, a) is SwitchOutcome.Busy)
        release.complete(Unit); saving.join()
        // An uncertain write reported by the UI.
        blocker = "Hay un guardado con resultado incierto."
        assertEquals(SwitchOutcome.Busy("Hay un guardado con resultado incierto."), storage.switchTo(nsA, a))
        blocker = null
        // An interrupted restore of the current data.
        val journal = File(layout.locate(StorageNamespace.Guest).recoveryDirectory, "restore.journal").also { it.parentFile!!.mkdirs(); it.writeText("restore-started x namespace=guest") }
        assertTrue(storage.switchTo(nsA, a) is SwitchOutcome.Busy)
        journal.delete()
        assertTrue(guest.open) // Refusals change nothing.
        guest.put(day("2026-12-01", "08:00-16:00"))
        // While a switch runs, saves and restores are refused rather than written to a closing database.
        val outcome = storage.switchTo(nsA, a, beforeCommit = {
            val db = storage.current!!.database
            assertThrows(StorageBusyException::class.java) { runBlocking { ScheduleRepository(db, gate).let { it.save(it.snapshot(), listOf(day("2026-12-05", "08:00-12:00"))) } } }
            val backup = BackupRepository(db, { false }, {}, File(temporary.root, "r"), AppVersion("t", 1), gate = gate)
            assertThrows(StorageBusyException::class.java) { runBlocking { backup.restore(BackupFile(BackupHeader(1, "t", 1, 5, 2, Instant.EPOCH), backup.snapshot())) } }
        })
        assertTrue(outcome is SwitchOutcome.Switched)
        assertTrue(outcome.session().dates().isEmpty())
    }

    @Test fun interruptedSwitchLeavesTheCommittedNamespaceAndNoDataIsLost() = runTest {
        val storage = coordinator()
        storage.start().put(day("2026-12-01", "08:00-16:00"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { storage.switchTo(nsA, a, beforeCommit = { throw IllegalStateException("proceso terminado") }) }
        }
        assertTrue(SwitchJournal(File(temporary.root, "nobackup")).pending())
        opened.forEach { runCatching { it.close() } } // The process died mid-switch.
        val restarted = coordinator().start()
        assertEquals(StorageNamespace.Guest, restarted.location.namespace)
        assertEquals(listOf("2026-12-01"), restarted.dates())
        assertFalse(SwitchJournal(File(temporary.root, "nobackup")).pending())
        assertTrue(context.getDatabasePath(layout.locate(nsA).databaseName).exists()) // Partially opened target kept, not deleted.
    }

    @Test fun wrongBindingsAreRefusedAndThePreviousDataReopens() = runTest {
        val storage = coordinator()
        storage.start()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { storage.switchTo(nsA, b) } } // Id does not derive the namespace.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { storage.switchTo(StorageNamespace.Guest, a) } }
        // A's database already bound to a different account (e.g. tampering): refused, guest reopened.
        open(layout.locate(nsA)).also { db ->
            db.sync().insertState(SyncState(deviceId = "d", accountId = b.value, cursor = 0, nextOperation = 1, restoreReviewRequired = false, linkedAt = null)); db.close()
        }
        val failed = storage.switchTo(nsA, a)
        assertTrue(failed is SwitchOutcome.Failed)
        assertEquals(StorageNamespace.Guest, (failed as SwitchOutcome.Failed).session.location.namespace)
        // A guest database bound to an account is never opened as guest data.
        val sessionB = storage.switchTo(nsB, b).session()
        open(layout.locate(StorageNamespace.Guest)).also { db -> SyncStore(db).link(a.value, Instant.EPOCH); db.close() }
        val refused = storage.signOut()
        assertTrue(refused is SwitchOutcome.Failed)
        assertEquals(nsB, (refused as SwitchOutcome.Failed).session.location.namespace)
        assertFalse(sessionB.open) // The old handle stays closed; the reopened session is a new one.
    }

    // Recovery journals and backups

    @Test fun recoveryJournalsAreNeverAppliedToAnotherNamespace() = runTest {
        val storage = coordinator()
        storage.start()
        val sessionA = storage.switchTo(nsA, a).session()
        sessionA.put(day("2026-12-02", "09:00-17:00"))
        val dirA = layout.locate(nsA).recoveryDirectory
        val repoA = BackupRepository(sessionA.database, { false }, {}, dirA, AppVersion("t", 1), namespace = nsA)
        val backup = BackupFile(BackupHeader(1, "t", 1, 5, 2, Instant.EPOCH), repoA.snapshot().copy(days = emptyList(), intervals = emptyList()))
        assertThrows(IllegalStateException::class.java) { runBlocking { repoA.restore(backup, afterRoom = { throw IllegalStateException("interrumpido") }) } }
        assertTrue(File(dirA, "restore.journal").readText().contains("namespace=${nsA.tag}"))
        // Switching away from data with an interrupted restore is refused, so the journal stays with its own data.
        assertTrue(storage.switchTo(nsB, b) is SwitchOutcome.Busy)
        // Even if A's journal files end up in B's directory, B never evaluates or deletes them.
        val dbB = open(layout.locate(nsB))
        val dirB = layout.locate(nsB).recoveryDirectory.also { it.mkdirs() }
        dirA.listFiles()!!.forEach { it.copyTo(File(dirB, it.name), overwrite = true) }
        val repoB = BackupRepository(dbB, { false }, {}, dirB, AppVersion("t", 1), namespace = nsB)
        assertEquals(RecoveryOutcome.None, repoB.recoverInterrupted())
        assertTrue(File(dirB, "restore.journal").exists())
        // A legacy (untagged) guest journal is not evaluated by an account either.
        File(dirB, "restore.journal").writeText("restore-started 2026-11-01T00:00:00Z")
        assertEquals(RecoveryOutcome.None, repoB.recoverInterrupted())
        assertTrue(File(dirB, "restore.journal").exists())
        // A's own repository does act on its journal.
        assertNotEquals(RecoveryOutcome.None, repoA.recoverInterrupted())
    }

    @Test fun guestBackupsNeverContainAccountData() = runTest {
        val storage = coordinator()
        storage.start().put(day("2026-12-01", "08:00-16:00"))
        storage.switchTo(nsA, a).session().put(day("2026-12-02", "09:00-17:00"))
        val guest = storage.signOut().session()
        val snapshot = BackupRepository(guest.database, { false }, {}, layout.locate(StorageNamespace.Guest).recoveryDirectory, AppVersion("t", 1)).snapshot()
        assertEquals(listOf("2026-12-01"), snapshot.days.map { it.date })
    }
}

/** A stale handle must fail without returning data: Room cancels suspend calls on a closed database (its coroutine
 *  scope is closed), and a direct reopen hits the SessionGuard. Both are accepted; any result is a failure. */
internal fun assertStale(block: suspend () -> Any?) {
    val error = runCatching { kotlinx.coroutines.runBlocking { block() } }.exceptionOrNull()
    org.junit.Assert.assertTrue("Se esperaba un fallo y se obtuvo: $error",
        error is StorageSessionClosedException || error is kotlinx.coroutines.CancellationException)
}
