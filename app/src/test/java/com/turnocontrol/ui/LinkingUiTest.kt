package com.turnocontrol.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.data.accounts.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.domain.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
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

/** Linking UI with synthetic data: a scripted backend for UI states, the real phase 5B engine for end-to-end cases. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class LinkingUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val store = ViewModelStore()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<TurnoDatabase>()
    private val account = AccountId.parse("00000000-0000-4000-8000-0000000005c1")!!
    private val layout get() = StorageLayout(File(temporary.root, "files"))

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() {
        store.clear(); Dispatchers.resetMain(); opened.forEach { runCatching { it.close() } }
        listOf(StorageNamespace.Guest, StorageNamespace.forAccount(account)).forEach { context.deleteDatabase(layout.locate(it).databaseName) }
    }

    private fun shared(date: String, kind: DayKind, text: String? = null) = SyncCodecs.dayKey(LocalDate.parse(date)) to SyncCodecs.encodeDay(DayRecord(
        if (text == null) PlannedDay(LocalDate.parse(date), kind, emptyList()) else TimeCellParser.parse(text).let { PlannedDay(LocalDate.parse(date), it.kind, it.intervals) }))

    private class ScriptedBackend(var plan: LinkPlan, var outcome: suspend () -> LinkOutcome = { LinkOutcome.Applied(0, 0, 0) }, var recovery: LinkRecovery = LinkRecovery.NONE) : LinkingBackend {
        var applyCalls = 0
        var lastDecisions: Map<RecordKey, Resolution> = emptyMap()
        override suspend fun recover() = recovery
        override suspend fun preview() = plan
        override suspend fun apply(planId: String, decisions: Map<RecordKey, Resolution>, acknowledgeReplacements: Boolean): LinkOutcome { applyCalls++; lastDecisions = decisions; return outcome() }
    }
    private fun model(backend: LinkingBackend) = LinkingViewModel(backend).also { store.put("l${System.nanoTime()}", it) }
    private fun conflictPlan(): LinkPlan {
        val (key, guest) = shared("2026-12-10", DayKind.SICK_LEAVE)
        val (_, accountValue) = shared("2026-12-10", DayKind.WORK, "22:00-06:00")
        val (importKey, importValue) = shared("2026-12-11", DayKind.VACATION)
        return LinkMergePlanner.plan(mapOf(key to guest, importKey to importValue), mapOf(key to accountValue),
            mapOf(key to KnownVersion(1, false, (accountValue as LocalRecord.Shared).payload)))
    }

    // State machine (scripted backend)

    @Test fun emptyDataLinksDirectlyAndConflictsNeedDecisionsAndReplacementConfirmation() = runTest {
        val empty = model(ScriptedBackend(LinkMergePlanner.plan(emptyMap(), emptyMap(), emptyMap())))
        assertTrue(empty.state.first { it.phase == LinkingPhase.READY }.canConfirm)
        val backend = ScriptedBackend(conflictPlan(), { LinkOutcome.Applied(2, 1, 0) })
        val vm = model(backend)
        val ready = vm.state.first { it.phase == LinkingPhase.READY }
        assertEquals(1, ready.pending.size)
        assertFalse(ready.canConfirm)
        vm.decide(ready.pending.single(), Resolution.KEEP_LOCAL)
        assertFalse(vm.state.value.canConfirm) // Replacing account data needs the extra confirmation.
        vm.confirmReplacement(true)
        assertTrue(vm.state.value.canConfirm)
        vm.confirm(); vm.confirm() // Duplicate confirmation.
        assertEquals(LinkingPhase.COMPLETED, vm.state.first { it.phase == LinkingPhase.COMPLETED }.phase)
        assertEquals(1, backend.applyCalls)
    }

    @Test fun cancellingAtAnyPointAppliesNothing() = runTest {
        val backend = ScriptedBackend(conflictPlan())
        val beforeConfirm = model(backend)
        beforeConfirm.state.first { it.phase == LinkingPhase.READY }
        beforeConfirm.cancel()
        assertEquals(LinkingPhase.CANCELLED, beforeConfirm.state.value.phase)
        val duringConflict = model(backend)
        val key = duringConflict.state.first { it.phase == LinkingPhase.READY }.pending.single()
        duringConflict.decide(key, Resolution.CANCEL)
        assertEquals(LinkingPhase.CANCELLED, duringConflict.state.value.phase)
        assertEquals(0, backend.applyCalls)
    }

    @Test fun stalePreviewsInvalidCombinationsAndInterruptionsAreHandledSafely() = runTest {
        val backend = ScriptedBackend(LinkMergePlanner.plan(mapOf(shared("2026-12-11", DayKind.FREE)), emptyMap(), emptyMap()), { LinkOutcome.PlanChanged })
        val vm = model(backend)
        vm.state.first { it.phase == LinkingPhase.READY }
        vm.confirm()
        assertEquals(LinkingPhase.STALE, vm.state.first { it.phase == LinkingPhase.STALE }.phase)
        backend.outcome = { LinkOutcome.Invalid("Hay turnos solapados entre días.") }
        vm.load(); vm.state.first { it.phase == LinkingPhase.READY }
        vm.confirm()
        val invalid = vm.state.first { it.phase == LinkingPhase.READY && it.message != null }
        assertTrue(invalid.message!!.contains("solapados"))
        vm.include(SyncCodecs.dayKey(LocalDate.parse("2026-12-11")), false) // Skip the problematic day.
        assertEquals(Resolution.KEEP_REMOTE, vm.state.value.decisions.values.single())
        backend.outcome = { throw IllegalStateException("proceso terminado") }
        vm.confirm()
        assertEquals(LinkingPhase.RECOVERY, vm.state.first { it.phase == LinkingPhase.RECOVERY }.phase)
        backend.recovery = LinkRecovery.COMPLETED
        vm.load()
        assertEquals("Se completó una vinculación que se había interrumpido.", vm.state.first { it.phase == LinkingPhase.READY }.message)
    }

    @Test fun recordsAreDescribedWithoutIdentifiersOrMedicalDetails() {
        val (sickKey, sick) = shared("2026-12-10", DayKind.SICK_LEAVE)
        assertEquals("10/12/2026 · Baja médica", describeRecord(sickKey, sick))
        val overtime = SyncCodecs.encodeDay(DayRecord(PlannedDay(LocalDate.parse("2026-12-12"), DayKind.WORK, listOf(
            ProvisionalInterval(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(6, 0), 0, 1, isExtra = true)))))
        val text = describeRecord(SyncCodecs.dayKey(LocalDate.parse("2026-12-12")), overtime)
        assertTrue(text.contains("(+1 día)") && text.contains("8 h 0 min de extra declarada"))
        assertFalse(text.contains("SCHEDULED_DAY"))
        assertEquals("Sin datos (borrado)", describeRecord(sickKey, null))
    }

    // Rendering

    @Test fun conflictScreenShowsBothSidesAndKeepsChoicesAcrossRecreation() = runTest {
        val vm = model(ScriptedBackend(conflictPlan()))
        vm.state.first { it.phase == LinkingPhase.READY }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TurnoTheme(true) { LinkingScreen(vm, onClose = {}) } }
        compose.onNodeWithText("Este dispositivo: 10/12/2026 · Baja médica").assertExists()
        compose.onNodeWithText("Cuenta: 10/12/2026 · Trabajo · 22:00–06:00 (+1 día)", substring = true).assertExists()
        compose.onNodeWithTag("linking-confirm").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Conservar datos de la cuenta").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, vm.state.value.decisions.size)
        restoration.emulateSavedInstanceStateRestore() // Recreated screen, same ViewModel.
        compose.onNodeWithTag("linking-confirm").assertIsEnabled().assertTextEquals("Combinar")
        compose.onNodeWithTag("linking-cancel").assertHeightIsAtLeast(48.dp)
    }

    // Real engine (phase 5B) end to end

    @Test fun realEngineLinksPreservesGuestDataAndBlocksAccountSwitchingMeanwhile() = runTest {
        val gate = OperationGate()
        val build = { location: StorageLocation, guard: SessionGuard -> StorageDatabases.build(context, location, guard) { it.allowMainThreadQueries() }.also { opened += it } }
        val storage = AccountStorageCoordinator(layout, ActiveNamespaceStore(File(temporary.root, "nb")), SwitchJournal(File(temporary.root, "nb")), gate, build)
        val guest = storage.start()
        ScheduleRepository(guest.database, gate).let { it.save(it.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-12-01"), DayKind.SICK_LEAVE, emptyList()))) }
        val session = (storage.switchTo(StorageNamespace.forAccount(account), account) as SwitchOutcome.Switched).session
        val guestReader = build(layout.locate(StorageNamespace.Guest), SessionGuard())
        val before = ScheduleRepository(guestReader).snapshot().map { it.planned() }
        val linker = AccountLinker(gate)
        var switchDuringLinking: SwitchOutcome? = null
        linker.beforeCommit = { switchDuringLinking = storage.switchTo(StorageNamespace.Guest, null) }
        val vm = model(AccountLinkingBackend(guestReader, session, account, linker))
        vm.state.first { it.phase == LinkingPhase.READY }
        vm.confirm()
        val done = vm.state.first { it.phase == LinkingPhase.COMPLETED || it.phase == LinkingPhase.FAILED || it.phase == LinkingPhase.RECOVERY }
        assertEquals(done.message, LinkingPhase.COMPLETED, done.phase)
        assertTrue(switchDuringLinking is SwitchOutcome.Busy)
        assertEquals(listOf(DayKind.SICK_LEAVE), ScheduleRepository(session.database).snapshot().map { it.planned().kind })
        assertEquals(before, ScheduleRepository(guestReader).snapshot().map { it.planned() }) // Guest data intact.
        // Repeating from a recreated screen never replays: a new preview shows everything already identical.
        val again = model(AccountLinkingBackend(guestReader, session, account, AccountLinker(gate)))
        assertTrue(again.state.first { it.phase == LinkingPhase.READY }.plan!!.entries.all { it.kind == LinkKind.ALREADY_IDENTICAL })
    }

    @Test fun linkingIsNotReachableFromProductionWithoutRealAuthentication() {
        assertNull(LinkingViewModel::class.java.getAnnotation(dagger.hilt.android.lifecycle.HiltViewModel::class.java)) // Not injectable.
        val app = File("src/main/java/com/turnocontrol/MainActivity.kt").readText()
        assertFalse(app.contains("Linking")) // No route or shortcut.
        assertFalse(File("src/main/java/com/turnocontrol/ui/SettingsScreen.kt").readText().contains("Linking"))
    }
}
