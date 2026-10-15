package com.turnocontrol.ui

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.time.LocalDate
import java.time.LocalTime
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TurnoDatabase
    private lateinit var repo: ScheduleRepository
    private lateinit var model: CalendarViewModel
    private val store = ViewModelStore()
    private val date = LocalDate.parse("2026-01-31")
    private fun draft(text: String = "22:00-02:00") = ReviewDayDraft(ProvisionalDay(date, "31/01/2026", text, DayKind.WORK, TimeCellParser.parse(text).intervals, InterpretationStatus.READY, emptyList()), date.toString(), text)
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build()
        repo = ScheduleRepository(db)
        model = CalendarViewModel(repo)
        store.put("calendar", model)
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }
    private suspend fun importReady() { model.state.first { it.importReady && !it.busy } }
    @Test fun ocrCannotSaveWithoutExplicitDecisionAndConfirmation() = runTest(dispatcher) {
        model.week(true)
        model.prepareImport(listOf(draft()))
        importReady()
        assertTrue(repo.snapshot().isEmpty())
        model.saveImport()
        model.state.first { !it.busy && it.message != null }
        assertTrue(repo.snapshot().isEmpty())
        model.editImport(0, choice = ImportChoice.REPLACE)
        model.editImport(0, confirmed = true)
        model.saveImport()
        model.state.first { it.importSaved && !it.busy }
        assertEquals("OCR_REVIEWED", repo.snapshot().single().day.origin)
        assertFalse(model.state.value.week)
        assertEquals(date, model.state.value.focused)
    }
    @Test fun keepDoesNotOverwriteAndEditRequiresRenewedConfirmation() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(PlannedDay(date, DayKind.FREE, emptyList())))
        model.prepareImport(listOf(draft()))
        importReady()
        assertEquals(DayKind.FREE, model.existingImport(date.toString())!!.kind)
        model.editImport(0, choice = ImportChoice.EDIT)
        model.editImport(0, confirmed = true)
        model.editImport(0, text = "08:00-16:00")
        assertFalse(model.state.value.imports.single().confirmed)
        model.editImport(0, choice = ImportChoice.KEEP)
        model.saveImport()
        model.state.first { !it.busy && it.message != null }
        assertEquals(DayKind.FREE, repo.snapshot().single().planned().kind)
    }
    @Test fun staleImportIsRejectedAndManualEditorCanReloadAndDelete() = runTest(dispatcher) {
        model.prepareImport(listOf(draft()))
        importReady()
        repo.save(repo.snapshot(), listOf(PlannedDay(date, DayKind.FREE, emptyList())))
        model.editImport(0, choice = ImportChoice.REPLACE)
        model.editImport(0, confirmed = true)
        model.saveImport()
        model.state.first { !it.busy && it.message != null }
        assertFalse(model.state.value.importSaved)
        assertEquals(DayKind.FREE, repo.snapshot().single().planned().kind)
        model.select(date)
        model.state.first { !it.busy && it.editorReady }
        model.openShift()
        model.split(true)
        model.slot(0, IntervalDraft(LocalTime.of(8,0), LocalTime.of(12,0)))
        model.slot(1, IntervalDraft(LocalTime.of(16,0), LocalTime.of(20,0), extra = true))
        model.saveShift()
        model.state.first { !it.busy && it.editor == null }
        assertEquals(2, repo.snapshot().single().intervals.size)
        assertTrue(repo.snapshot().single().intervals[1].isExtra)
        model.select(date)
        model.state.first { !it.busy && it.editorReady }
        model.deleteDay()
        model.state.first { !it.busy && it.days.isEmpty() }
        assertTrue(repo.snapshot().isEmpty())
    }
    @Test fun visualEditorKeepsInvalidDraftAndSupportsExplicitExtraAfterMidnight() = runTest(dispatcher) {
        model.select(date)
        model.state.first { !it.busy && it.editorReady }
        model.openShift()
        model.saveShift()
        assertNotNull(model.state.value.message)
        assertNotNull(model.state.value.editor)
        model.slot(0, IntervalDraft(LocalTime.of(18,0), LocalTime.MIDNIGHT, endOffset = 1))
        model.saveShift()
        model.state.first { !it.busy && it.editor == null }
        model.openShift()
        model.slot(0, IntervalDraft(LocalTime.MIDNIGHT, LocalTime.of(1,0), 1, 1, true))
        model.saveShift()
        model.state.first { !it.busy && it.editor == null }
        val saved = repo.snapshot().single().planned()
        assertEquals(420, saved.totalMinutes())
        assertEquals(60, saved.extraMinutes())
        model.openShift(1)
        model.slot(0, model.state.value.editor!!.slots.single().copy(extra = false))
        model.cancelShift()
        assertEquals(60, repo.snapshot().single().planned().extraMinutes())
        model.deleteInterval(0)
        model.state.first { !it.busy }
        // Removing the start-day anchor would orphan the +1 interval: reject and preserve both.
        assertEquals(2, repo.snapshot().single().intervals.size)
    }
    @Test fun weekAndMonthNavigationCrossYearsWithoutLosingSelection() = runTest(dispatcher) {
        model.select(LocalDate.parse("2026-12-31"))
        model.state.first { !it.busy && it.editorReady }
        model.closeEditor()
        model.moveMonth(1)
        assertEquals("2027-01", model.state.value.month.toString())
        model.week(true)
        model.move(-1)
        assertEquals(LocalDate.parse("2027-01-24"), model.state.value.focused)
        assertEquals(LocalDate.parse("2027-01-18"), model.weekStart())
    }
}
