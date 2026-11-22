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
    private fun planned(text: String = "22:00-02:00", on: LocalDate = date) = TimeCellParser.parse(text).let { PlannedDay(on, it.kind, it.intervals) }
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build()
        repo = ScheduleRepository(db)
        model = CalendarViewModel(repo)
        store.put("calendar", model)
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }
    private suspend fun importReady() { model.state.first { it.importReady && !it.busy } }
    @Test fun reviewedImportIsWrittenOnlyBySaveAndPersistsAcrossViewModels() = runTest(dispatcher) {
        model.week(true)
        model.prepareImport(listOf(planned(), planned("08:00-12:00 / 16:00-20:00", date.plusDays(2)), planned("LIBRE", date.plusDays(3))))
        importReady()
        // Preparing, classifying and summarising never write.
        assertTrue(repo.snapshot().isEmpty())
        assertTrue(model.state.value.imports.all { it.status == ImportStatus.NEW })
        assertEquals(ImportSummary(days = 3, shifts = 3, added = 3, replaced = 0, unchanged = 0, kept = 0, unresolved = 0), model.importSummary())
        assertNull(model.importBlocker())
        model.saveImport()
        model.state.first { it.importSaved && !it.busy }
        assertEquals(SaveOutcome.CONFIRMED, model.state.value.saveOutcome)
        assertTrue(model.state.value.message!!.contains("3 día(s) con 3 turno(s)"))
        val rows = repo.snapshot()
        assertEquals(listOf("OCR_REVIEWED"), rows.map { it.day.origin }.distinct())
        assertEquals(1, rows.first { it.day.date == "2026-01-31" }.planned().intervals.single().endDayOffset)
        assertEquals(2, rows.first { it.day.date == "2026-02-02" }.intervals.size)
        assertFalse(model.state.value.week)
        assertEquals(date, model.state.value.focused)
        // A new ViewModel over the same database (as after a restart) observes the same rows.
        val reopened = CalendarViewModel(repo).also { store.put("reopened", it) }
        assertEquals(rows, reopened.state.first { !it.loading }.days)
    }
    @Test fun abandonedImportWritesNothingAndANewImportIsClassifiedAgainstCurrentData() = runTest(dispatcher) {
        model.prepareImport(listOf(planned("08:00-12:00")))
        importReady()
        // Leaving the confirmation without saving: the next import replaces the review and sees data saved meanwhile.
        repo.save(repo.snapshot(), listOf(planned("09:00-13:00")))
        model.prepareImport(listOf(planned("08:00-12:00"), planned("10:00-14:00", date.plusDays(1))))
        importReady()
        assertEquals(listOf(ImportStatus.CONFLICT, ImportStatus.NEW), model.state.value.imports.map { it.status })
        assertEquals(model.state.value.importReview!!.proposals, model.state.value.imports) // One source of truth.
        assertEquals(listOf("09:00–13:00"), repo.snapshot().map { it.planned().rangeText() })
        model.resetAfterRestore()
        assertNull(model.state.value.importReview)
        assertTrue(model.state.value.imports.isEmpty())
    }
    @Test fun reimportingTheSameScheduleIsDetectedAndNeverDuplicated() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(planned()))
        val before = repo.snapshot()
        model.prepareImport(listOf(planned()))
        importReady()
        assertEquals(ImportStatus.UNCHANGED, model.state.value.imports.single().status)
        assertTrue(model.importBlocker()!!.contains("No hay cambios"))
        model.saveImport()
        settled()
        assertEquals(SaveOutcome.REJECTED, model.state.value.saveOutcome)
        assertEquals(before, repo.snapshot())
        assertEquals(1L, repo.snapshot().single().day.revision)
    }
    @Test fun conflictsRequireAnExplicitDecisionAndKeepIsNotOverwritten() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(PlannedDay(date, DayKind.FREE, emptyList()), planned("08:00-16:00", date.plusDays(1))))
        model.prepareImport(listOf(planned(), planned("09:00-17:00", date.plusDays(1)), planned("10:00-14:00", date.plusDays(5))))
        importReady()
        assertEquals(listOf(ImportStatus.CONFLICT, ImportStatus.CONFLICT, ImportStatus.NEW), model.state.value.imports.map { it.status })
        assertTrue(importDifference(model.state.value.imports[0].existing!!, model.state.value.imports[0].planned).contains("libre"))
        assertTrue(model.importBlocker()!!.contains("2 día(s)"))
        model.saveImport()
        settled()
        assertEquals(DayKind.FREE, repo.snapshot().first { it.day.date == date.toString() }.planned().kind)
        model.resolveImport(0, replace = false)
        model.resolveImport(1, replace = true)
        assertEquals(ImportSummary(days = 2, shifts = 2, added = 1, replaced = 1, unchanged = 0, kept = 1, unresolved = 0), model.importSummary())
        model.saveImport()
        model.state.first { it.importSaved && !it.busy }
        val stored = stored().associateBy { it.date }
        assertEquals(DayKind.FREE, stored.getValue(date).kind) // kept
        assertEquals("09:00", stored.getValue(date.plusDays(1)).intervals.single().start.toString()) // replaced
        assertEquals("10:00", stored.getValue(date.plusDays(5)).intervals.single().start.toString()) // added
    }
    @Test fun overnightImportOverlappingAStoredNeighbourIsNamedBeforeWriting() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(planned("01:00-05:00", date.plusDays(1))))
        model.prepareImport(listOf(planned("22:00-02:00")))
        importReady()
        assertTrue(model.importBlocker()!!.contains(date.toString()))
        model.saveImport()
        settled()
        assertEquals(1, repo.snapshot().size)
    }
    @Test fun staleImportIsRejectedAndManualEditorCanReloadAndDelete() = runTest(dispatcher) {
        model.prepareImport(listOf(planned()))
        importReady()
        repo.save(repo.snapshot(), listOf(PlannedDay(date, DayKind.FREE, emptyList())))
        model.saveImport()
        model.state.first { !it.busy && it.message != null }
        assertEquals(SaveOutcome.REJECTED, model.state.value.saveOutcome)
        assertFalse(model.state.value.importSaved)
        assertEquals(1, model.state.value.imports.size) // Review data is preserved for a retry.
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
        // Replacing a day with extras warns that the extra marks would be lost.
        model.prepareImport(listOf(planned("08:00-12:00")))
        importReady()
        assertTrue(importDifference(model.state.value.imports.single().existing!!, model.state.value.imports.single().planned).contains("extra"))
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
    private suspend fun open(d: LocalDate) { model.select(d); model.state.first { !it.busy && it.editorReady } }
    private suspend fun settled() = model.state.first { !it.busy }
    private suspend fun stored() = repo.snapshot().map { it.planned() }
    private suspend fun month(month: java.time.YearMonth) = ScheduleSummary.month(stored(), month)

    @Test fun singleSickLeaveDayIsPersistedWithoutHours() = runTest(dispatcher) {
        open(date)
        model.markKind(DayKind.SICK_LEAVE)
        model.state.first { !it.busy && it.days.size == 1 }
        val row = repo.snapshot().single()
        assertEquals("SICK_LEAVE", row.day.kind)
        assertTrue(row.intervals.isEmpty())
        val summary = month(java.time.YearMonth.of(2026, 1))
        assertEquals(1, summary.sickDays)
        assertEquals(0, summary.totalMinutes)
        assertEquals(0, summary.freeDays)
        assertEquals(0, summary.vacationDays)
    }
    @Test fun sickLeaveRangeCrossesMonthAndYear() = runTest(dispatcher) {
        open(LocalDate.parse("2026-12-30"))
        model.markRange(DayKind.SICK_LEAVE, LocalDate.parse("2027-01-02"))
        model.state.first { !it.busy && it.days.size == 4 }
        assertEquals(2, month(java.time.YearMonth.of(2026, 12)).sickDays)
        assertEquals(2, month(java.time.YearMonth.of(2027, 1)).sickDays)
        assertEquals(2, ScheduleSummary.year(stored(), 2026).sickDays)
        assertEquals(2, ScheduleSummary.year(stored(), 2027).sickDays)
    }
    @Test fun rangePreviewCountsOnlyChangedDays() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(
            PlannedDay(LocalDate.parse("2026-03-02"), DayKind.WORK, TimeCellParser.parse("08:00-16:00").intervals),
            PlannedDay(LocalDate.parse("2026-03-03"), DayKind.SICK_LEAVE, emptyList())))
        open(LocalDate.parse("2026-03-01"))
        assertEquals(RangePreview(days = 4, replaced = 1), model.rangePreview(DayKind.SICK_LEAVE, LocalDate.parse("2026-03-04")))
        model.markRange(DayKind.SICK_LEAVE, LocalDate.parse("2026-03-04"))
        model.state.first { !it.busy && it.days.size == 4 }
        val rows = repo.snapshot()
        assertEquals(1L, rows.single { it.day.date == "2026-03-03" }.day.revision)
        assertEquals("SICK_LEAVE", rows.single { it.day.date == "2026-03-02" }.day.kind)
        assertTrue(rows.single { it.day.date == "2026-03-02" }.intervals.isEmpty())
    }
    @Test fun rangeRejectsInvalidEnd() = runTest(dispatcher) {
        open(date)
        model.markRange(DayKind.SICK_LEAVE, date.minusDays(1))
        settled()
        assertEquals("La fecha final debe ser igual o posterior al día seleccionado.", model.state.value.message)
        assertTrue(repo.snapshot().isEmpty())
        model.markRange(DayKind.VACATION, date.plusDays(366))
        settled()
        assertEquals("El periodo no puede superar 366 días.", model.state.value.message)
        assertTrue(repo.snapshot().isEmpty())
    }
    @Test fun clearPeriodRemovesOnlyTheContiguousRun() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf("2026-03-01", "2026-03-02", "2026-03-03", "2026-03-06").map { PlannedDay(LocalDate.parse(it), DayKind.SICK_LEAVE, emptyList()) } +
            PlannedDay(LocalDate.parse("2026-03-04"), DayKind.FREE, emptyList()))
        open(LocalDate.parse("2026-03-02"))
        assertEquals(LocalDate.parse("2026-03-01")..LocalDate.parse("2026-03-03"), model.contiguousPeriod(LocalDate.parse("2026-03-02"), DayKind.SICK_LEAVE))
        model.clearPeriod()
        model.state.first { !it.busy && it.days.size == 2 }
        assertEquals(listOf("2026-03-04" to "FREE", "2026-03-06" to "SICK_LEAVE"), repo.snapshot().map { it.day.date to it.day.kind })
        assertEquals(1, month(java.time.YearMonth.of(2026, 3)).sickDays)
    }
    @Test fun vacationRangeUsesSameMechanism() = runTest(dispatcher) {
        val start = LocalDate.parse("2026-01-10")
        open(start)
        model.markRange(DayKind.VACATION, start.plusDays(2))
        model.state.first { !it.busy && it.days.size == 3 }
        val summary = month(java.time.YearMonth.of(2026, 1))
        assertEquals(3, summary.vacationDays)
        assertEquals(0, summary.sickDays)
        assertEquals(0, summary.freeDays)
        assertEquals("VACACIONES", stored().first().rangeText())
    }
    @Test fun countersFollowEditsAfterReload() = runTest(dispatcher) {
        val start = LocalDate.parse("2026-01-10")
        open(start)
        model.markRange(DayKind.SICK_LEAVE, start.plusDays(1))
        model.state.first { !it.busy && it.days.size == 2 }
        val before = ScheduleSummary.month(model.state.value.days.map { it.planned() }, java.time.YearMonth.of(2026, 1))
        val reloaded = CalendarViewModel(repo).also { store.put("reloaded", it) }
        val after = ScheduleSummary.month(reloaded.state.first { !it.loading }.days.map { it.planned() }, java.time.YearMonth.of(2026, 1))
        assertEquals(before, after)
        assertEquals(2, after.sickDays)
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
