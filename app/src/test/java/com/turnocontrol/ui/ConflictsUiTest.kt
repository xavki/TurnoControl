package com.turnocontrol.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** Conflict decisions over a real SyncStore and the in-memory fake server (synthetic accounts, no network). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class ConflictsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.parse("2026-12-01")
    private val server = FakeSyncServer { today }
    private val store = ViewModelStore()
    private val opened = mutableListOf<TurnoDatabase>()
    private val key = SyncCodecs.dayKey(LocalDate.parse("2026-12-02"))

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { store.clear(); opened.forEach { it.close() }; listOf("conf-a.db", "conf-b.db").forEach(context::deleteDatabase); Dispatchers.resetMain() }

    private fun open(name: String) = Room.databaseBuilder(context, TurnoDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build().also { opened += it }
    private fun day(text: String) = TimeCellParser.parse(text).let { PlannedDay(LocalDate.parse("2026-12-02"), it.kind, it.intervals) }
    private suspend fun TurnoDatabase.put(day: PlannedDay) = ScheduleRepository(this).let { it.save(it.snapshot(), listOf(day)) }
    private suspend fun TurnoDatabase.days() = ScheduleRepository(this).snapshot().map { it.planned() }

    /** Device B ends with one VERSION conflict on 2 December: local sick leave against the other device's 08:00–17:00. */
    private suspend fun conflicted(): TurnoDatabase {
        context.deleteDatabase("conf-a.db"); context.deleteDatabase("conf-b.db")
        val a = open("conf-a.db"); val b = open("conf-b.db")
        listOf(a, b).forEach { SyncStore(it).link("ana", Instant.parse("2026-12-01T08:00:00Z")) }
        val ta = server.signIn("ana"); val tb = server.signIn("ana")
        a.put(day("08:00-16:00")); SyncStore(a).synchronize(server.transport(ta)); SyncStore(b).synchronize(server.transport(tb))
        a.put(day("08:00-17:00")); SyncStore(a).synchronize(server.transport(ta))
        b.put(PlannedDay(LocalDate.parse("2026-12-02"), DayKind.SICK_LEAVE, emptyList()))
        assertTrue(SyncStore(b).synchronize(server.transport(tb)).requiresUserDecision)
        return b
    }

    @Test fun conflictIsShownWithBothSidesAndKeepRemoteWritesTheCalendar() = runTest {
        val b = conflicted()
        val model = ConflictsViewModel(SyncStoreConflictBackend(SyncStore(b)) { today }).also { store.put("c", it) }
        model.state.first { !it.loading && it.conflicts.isNotEmpty() }
        compose.setContent { TurnoTheme(false) { ConflictsScreen(model) {} } }
        compose.onNodeWithText("Día 2026-12-02").assertExists()
        compose.onNodeWithText("Este dispositivo: BAJA MÉDICA").assertExists()
        compose.onNodeWithText("Otro dispositivo: 08:00–17:00").assertExists()
        assertEquals(DayKind.SICK_LEAVE, b.days().single().kind) // Nothing decided automatically.
        compose.onNodeWithText("Conservar el otro dispositivo").performScrollTo().performClick()
        compose.waitUntil(5000) { model.state.value.conflicts.isEmpty() && !model.state.value.busy }
        compose.onNodeWithTag("conflicts-empty").assertExists()
        compose.onNodeWithTag("conflicts-message").assertTextContains("otro dispositivo", substring = true)
        assertEquals(day("08:00-17:00"), b.days().single())
        assertTrue(b.sync().conflicts().isEmpty())
    }

    @Test fun keepLocalKeepsTheSickLeaveAndQueuesItWithoutMedicalDetails() = runTest {
        val b = conflicted()
        val model = ConflictsViewModel(SyncStoreConflictBackend(SyncStore(b)) { today }).also { store.put("c", it) }
        model.state.first { !it.loading && it.conflicts.isNotEmpty() }
        model.resolve(key, Resolution.KEEP_LOCAL)
        model.state.first { !it.busy && it.conflicts.isEmpty() }
        assertEquals(DayKind.SICK_LEAVE, b.days().single().kind)
        val queued = b.sync().outbox().single { it.recordKey == "2026-12-02" }
        assertEquals("1|2026-12-02|SICK_LEAVE|MANUAL|", queued.payload) // Date and state only.
    }

    private class Gated(private val conflict: SyncConflict) : ConflictBackend {
        val gate = CompletableDeferred<Unit>()
        var resolutions = 0
        var failRead = false
        override suspend fun conflicts(): List<SyncConflict> { if (failRead) error("disco"); return if (resolutions == 0) listOf(conflict) else emptyList() }
        override suspend fun resolve(key: RecordKey, resolution: Resolution): ResolutionResult {
            resolutions++; gate.await()
            return ResolutionResult(Replica("ana", "d"), ResolutionOutcome.KEPT_LOCAL)
        }
    }

    @Test fun aSecondTapWhileADecisionIsAppliedIsIgnoredAndCancelNeverResolves() = runTest {
        val backend = Gated(SyncConflict(key, ConflictKind.VERSION, null, null))
        val model = ConflictsViewModel(backend).also { store.put("g", it) }
        model.state.first { !it.loading }
        model.resolve(key, Resolution.CANCEL)
        assertEquals(0, backend.resolutions)
        model.resolve(key, Resolution.KEEP_LOCAL)
        model.resolve(key, Resolution.KEEP_REMOTE)
        assertEquals(1, backend.resolutions)
        backend.gate.complete(Unit)
        model.state.first { !it.busy }
        assertTrue(model.state.value.conflicts.isEmpty())
    }

    @Test fun unreadableConflictsAreReportedWithoutChangingAnything() = runTest {
        val backend = Gated(SyncConflict(key, ConflictKind.VERSION, null, null)).apply { failRead = true }
        val model = ConflictsViewModel(backend).also { store.put("f", it) }
        assertEquals("No se pudieron leer los conflictos. No se ha modificado nada.", model.state.first { !it.loading }.message)
        assertEquals(0, backend.resolutions)
    }
}
