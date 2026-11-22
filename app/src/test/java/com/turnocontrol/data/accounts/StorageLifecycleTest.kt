package com.turnocontrol.data.accounts

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.ui.ConfigurationViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Production storage lifecycle (StorageSessionHolder + StorageScope) on synthetic databases. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class StorageLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val a = AccountId.parse("00000000-0000-4000-8000-00000000000a")!!
    private val b = AccountId.parse("00000000-0000-4000-8000-00000000000b")!!
    private val nsA = StorageNamespace.forAccount(a)
    private val nsB = StorageNamespace.forAccount(b)
    private val files get() = File(temporary.root, "files")
    private val layout get() = StorageLayout(files)
    private val gate = OperationGate()
    private val opened = mutableListOf<TurnoDatabase>()
    private val store = ViewModelStore()

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        store.clear(); Dispatchers.resetMain(); opened.forEach { runCatching { it.close() } }
        listOf(StorageNamespace.Guest, nsA, nsB).forEach { context.deleteDatabase(layout.locate(it).databaseName) }
    }
    /** A new holder = a new process (Hilt singletons rebuilt), over the same synthetic files. */
    private fun holder() = StorageSessionHolder(context, gate, AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")),
        SwitchJournal(File(temporary.root, "nb")), gate, { location, guard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it } }))
    private fun day(date: String, kind: DayKind = DayKind.FREE) = PlannedDay(LocalDate.parse(date), kind, emptyList())
    private suspend fun ScheduleRepository.put(vararg days: PlannedDay) = save(snapshot(), days.toList())
    private suspend fun ScheduleRepository.dates() = snapshot().map { it.day.date }

    @Test fun guestIsTheDefaultAndKeepsItsHistoricalFiles() = runTest {
        val storage = holder()
        val scope = storage.scope()
        assertEquals(StorageNamespace.Guest, scope.session.location.namespace)
        assertEquals("turnocontrol.db", scope.session.location.databaseName)
        assertEquals(File(files, "backup-recovery").canonicalFile, scope.backup.safetyCopy.parentFile)
        assertNull(storage.verifyActive())
        assertSame(scope.schedule, storage.scope().schedule) // One instance per session.
        scope.schedule.put(day("2026-12-01"))
        assertEquals(listOf("2026-12-01"), storage.scope().schedule.dates())
    }

    @Test fun switchingGivesFreshRepositoriesAndOldOnesFailWithoutTouchingOtherData() = runTest {
        val storage = holder()
        val guest = storage.scope()
        guest.schedule.put(day("2026-12-01"))
        val oldConfigModel = ConfigurationViewModel(guest.configuration).also { store.put("old", it) }
        oldConfigModel.state.first { !it.loading }

        val switched = storage.switchTo(nsA, a)
        assertTrue(switched is SwitchOutcome.Switched)
        val scopeA = storage.scope()
        assertNotSame(guest.schedule, scopeA.schedule)
        assertEquals(File(File(File(files, "accounts"), nsA.key), "backup-recovery").canonicalFile, scopeA.backup.safetyCopy.parentFile)
        assertTrue(scopeA.schedule.dates().isEmpty()) // Guest data is not merged into the account.
        scopeA.schedule.put(day("2026-12-02", DayKind.VACATION))
        assertEquals(1, scopeA.database.sync().outbox().size) // Account edits queue for that account.

        // Old repositories and ViewModels reach the closed session: recoverable error, nothing read or written.
        assertStale { guest.schedule.snapshot() }
        assertStale { guest.sync.load() }
        assertThrows(StorageSessionClosedException::class.java) { guest.database.openHelper.writableDatabase }
        oldConfigModel.update(oldConfigModel.state.value.draft.copy(name = "Escrito desde un ViewModel antiguo", zone = "UTC", hours = "40"))
        oldConfigModel.save()
        assertEquals("No se pudo guardar. Reintenta.", oldConfigModel.state.first { !it.saving && it.message != null }.message)
        assertEquals("Mi calendario", scopeA.database.schedule().profile()?.displayName) // A's own placeholder; the old screen wrote nothing.

        val scopeB = (storage.switchTo(nsB, b) as SwitchOutcome.Switched).let { storage.scope() }
        assertTrue(scopeB.schedule.dates().isEmpty()) // B cannot read A.
        val back = (storage.switchTo(StorageNamespace.Guest, null) as SwitchOutcome.Switched).let { storage.scope() }
        assertEquals(listOf("2026-12-01"), back.schedule.dates()) // Guest never sees account records.
        val againA = (storage.switchTo(nsA, a) as SwitchOutcome.Switched).let { storage.scope() }
        assertEquals(listOf("2026-12-02"), againA.schedule.dates())
        assertEquals(1, againA.database.sync().outbox().size) // Pending operations survive.
        listOf(StorageNamespace.Guest, nsA, nsB).forEach { assertTrue(context.getDatabasePath(layout.locate(it).databaseName).exists()) } // Nothing deleted.
    }

    @Test fun switchingIsRefusedDuringSavesRestoresAndUncertainWrites() = runTest {
        val storage = holder()
        val guest = storage.scope()
        val release = CompletableDeferred<Unit>()
        val saving = launch { gate.operation("calendar-save") { release.await() } }
        yield()
        assertTrue(storage.switchTo(nsA, a) is SwitchOutcome.Busy)
        release.complete(Unit); saving.join()
        assertEquals(SwitchOutcome.Busy("Hay un guardado con resultado incierto."), storage.switchTo(nsA, a) { "Hay un guardado con resultado incierto." })
        File(guest.session.location.recoveryDirectory, "restore.journal").also { it.parentFile!!.mkdirs() }.writeText("restore-started x namespace=guest")
        assertTrue(storage.switchTo(nsA, a) is SwitchOutcome.Busy)
        assertSame(guest, storage.scope()) // Every refusal keeps the same, still open session.
        assertTrue(guest.session.open)
    }

    @Test fun restartAfterSwitchingOrAnInterruptedSwitchOpensTheCommittedNamespace() = runTest {
        val first = holder()
        first.scope().schedule.put(day("2026-12-01"))
        first.switchTo(nsA, a)
        first.scope().schedule.put(day("2026-12-02"))
        opened.forEach { runCatching { it.close() } } // Process ends.
        val second = holder()
        assertEquals(nsA, second.scope().session.location.namespace)
        assertNull(second.verifyActive())
        assertEquals(listOf("2026-12-02"), second.scope().schedule.dates())
        // An interrupted switch (journal left behind) is discarded at the next start; the committed namespace stays.
        SwitchJournal(File(temporary.root, "nb")).begin(nsA, nsB, java.time.Instant.EPOCH)
        opened.forEach { runCatching { it.close() } }
        val third = holder()
        assertEquals(nsA, third.scope().session.location.namespace)
        assertFalse(SwitchJournal(File(temporary.root, "nb")).pending())
    }

    @Test fun theStartupGateRefusesGuestDataBoundToAnAccount() = runTest {
        val storage = holder()
        SyncStore(storage.scope().database).link(a.value, java.time.Instant.EPOCH)
        assertEquals("Los datos locales están vinculados a una cuenta y no se muestran como datos sin cuenta.", storage.verifyActive())
    }
}
