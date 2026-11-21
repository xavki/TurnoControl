package com.turnocontrol.ui

import com.turnocontrol.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScheduleReviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val document = OcrDocument("Synthetic", 1000, 600, emptyList())
    private fun ocrDay(date: String?, cell: String): ProvisionalDay {
        val parsed = ReportCellParser.parse(cell)
        return ProvisionalDay(date?.let(LocalDate::parse), date ?: "MAR", cell, parsed.kind, parsed.intervals,
            if (date == null || parsed.kind == DayKind.UNKNOWN) InterpretationStatus.INCOMPLETE else InterpretationStatus.READY,
            parsed.issues + if (date == null) listOf(ScheduleIssue.MISSING_DATE) else emptyList(), parsed.absence)
    }
    private var days = listOf(ocrDay(null, "08:00-16:00"))
    private val fake = object : ScheduleInterpreter {
        override fun interpret(document: OcrDocument, worker: WorkerSelection, month: java.time.YearMonth?) = ScheduleResult(days = days)
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private suspend fun interpreted(vararg detected: ProvisionalDay): ScheduleReviewViewModel {
        days = detected.toList()
        val model = ScheduleReviewViewModel(fake)
        model.prepare(document, "ALFA", "")
        model.interpret()
        model.state.first { it.result != null && !it.loading }
        return model
    }
    private fun ScheduleReviewViewModel.draft(i: Int) = state.value.drafts[i]

    @Test fun correctDetectionsCanBeReviewedWithoutOpeningTheEditor() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:00-16:00"), ocrDay("2026-10-13", "08:15-12:00 (COC) 16:00-20:30"),
            ocrDay("2026-10-14", "22:00-06:00"), ocrDay("2026-10-15", "LIB"))
        assertTrue(model.state.value.drafts.all { it.status == ReviewStatus.PENDING && it.editor == null })
        (0..3).forEach(model::markReviewed)
        assertTrue(model.state.value.drafts.all { it.status == ReviewStatus.REVIEWED && it.editor == null && !it.edited })
        val ready = model.readyForSave()!!
        assertEquals(listOf(DayKind.WORK, DayKind.WORK, DayKind.WORK, DayKind.FREE), ready.map { it.kind })
        assertEquals(2, ready[1].intervals.size) // split
        assertEquals(1, ready[2].intervals.single().endDayOffset) // overnight
        assertTrue(ready[3].intervals.isEmpty()) // day off
        assertNull(model.state.value.message)
    }

    @Test fun editingNeverReviewsAndAChangedReviewedDayReturnsToPending() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:00-16:00"), ocrDay("2026-10-13", "09:00-17:00"))
        model.startEdit(0)
        model.editInterval(0, 0, end = "15:00")
        model.applyEdit(0)
        assertEquals(ReviewStatus.PENDING, model.draft(0).status)
        assertTrue(model.draft(0).edited)
        assertNull(model.draft(0).editor)
        assertEquals("08:00-15:00", model.draft(0).timeText)
        model.markReviewed(0)
        assertEquals(ReviewStatus.REVIEWED, model.draft(0).status)
        // Reviewed, then modified: back to pending until reviewed again.
        model.markReviewed(1)
        model.startEdit(1)
        model.markReviewed(1) // Not while an edit is open.
        assertTrue(model.state.value.message!!.contains("Aplica o cancela"))
        model.addInterval(1)
        model.editInterval(1, 0, end = "13:00")
        model.editInterval(1, 1, start = "15:00", end = "19:00")
        model.applyEdit(1)
        assertEquals(ReviewStatus.PENDING, model.draft(1).status)
        assertEquals(2, model.draft(1).planned!!.intervals.size)
        // Applying identical values is not a modification.
        model.markReviewed(1)
        model.startEdit(1)
        model.applyEdit(1)
        assertEquals(ReviewStatus.REVIEWED, model.draft(1).status)
        model.markPending(1)
        assertEquals(ReviewStatus.PENDING, model.draft(1).status)
    }

    @Test fun invalidDaysCannotBeReviewedUntilCorrected() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:15-12:00 (C0C)"), ocrDay(null, "08:00-16:00"))
        assertEquals(ReviewStatus.INVALID, model.draft(0).status)
        assertEquals(ReviewStatus.INVALID, model.draft(1).status)
        assertTrue(model.draft(1).error!!.contains("fecha"))
        model.markReviewed(0)
        assertEquals(ReviewStatus.INVALID, model.draft(0).status)
        assertTrue(model.state.value.message!!.contains("error de validación"))
        // Never invented: the uncertain cell keeps no hours until the user types them.
        model.startEdit(0)
        assertEquals(listOf(IntervalText()), model.draft(0).editor!!.intervals)
        model.editInterval(0, 0, start = "08:15", end = "12:00")
        model.applyEdit(0)
        assertEquals(ReviewStatus.PENDING, model.draft(0).status)
        model.markReviewed(0)
        assertEquals(ReviewStatus.REVIEWED, model.draft(0).status)
        // Invalid edits keep the editor open and the day in error.
        model.startEdit(1)
        model.editDate(1, "2026-02-31")
        model.applyEdit(1)
        assertEquals(ReviewStatus.INVALID, model.draft(1).status)
        assertNotNull(model.draft(1).editor)
        model.editDate(1, "2026-10-13")
        model.applyEdit(1)
        assertEquals(ReviewStatus.PENDING, model.draft(1).status)
        assertNull(model.draft(1).editor)
    }

    @Test fun pendingOrInvalidDaysBlockSavingUntilReviewedOrExcluded() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:00-16:00"), ocrDay("2026-10-13", "09:00-17:00"), ocrDay("2026-10-14", "???"))
        model.markReviewed(0)
        assertNull(model.readyForSave())
        val message = model.state.value.message!!
        assertTrue(message, message.contains("Día 2 (2026-10-13): pendiente de revisar"))
        assertTrue(message, message.contains("Día 3 (2026-10-14): error de validación"))
        assertFalse(message, message.contains("Día 1"))
        model.setIncluded(2, false)
        assertNull(model.readyForSave())
        model.markReviewed(1)
        assertEquals(listOf("2026-10-12", "2026-10-13"), model.readyForSave()!!.map { it.date.toString() })
        (0..2).forEach { model.setIncluded(it, false) }
        assertNull(model.readyForSave())
        assertTrue(model.state.value.message!!.contains("No hay días incluidos"))
    }

    @Test fun duplicateDatesAndVacationsAreNotSavedSilently() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:00-16:00"), ocrDay("2026-10-12", "LIBRE"), ocrDay("2032-03-11", "VAC"))
        assertEquals(ReviewStatus.INVALID, model.draft(0).status)
        assertEquals(ReviewStatus.INVALID, model.draft(1).status)
        assertTrue(model.draft(0).error!!.contains("repetida"))
        assertFalse(model.draft(2).included)
        model.setIncluded(1, false)
        assertEquals(ReviewStatus.PENDING, model.draft(0).status)
        model.setIncluded(2, true)
        assertEquals(ReviewStatus.INVALID, model.draft(2).status)
        assertTrue(model.draft(2).error!!.contains("VAC"))
    }

    @Test fun manualDayAndFreeDayCorrection() = runTest(dispatcher) {
        val model = interpreted(ocrDay("2026-10-12", "08:00-16:00"))
        model.addManualDay()
        assertNotNull(model.draft(1).editor)
        assertEquals(ReviewStatus.INVALID, model.draft(1).status)
        model.editDate(1, "2026-10-14")
        model.editInterval(1, 0, start = "23:00", end = "07:00")
        model.applyEdit(1)
        assertEquals(1, model.draft(1).planned!!.intervals.single().endDayOffset)
        model.startEdit(0)
        model.editFree(0, true)
        model.applyEdit(0)
        assertEquals(DayKind.FREE, model.draft(0).planned!!.kind)
        assertEquals(ReviewStatus.PENDING, model.draft(0).status)
    }

    @Test fun missingMonthContextIsNotReplacedByTodayAndStateSurvivesRePreparation() = runTest(dispatcher) {
        val model = interpreted(ocrDay(null, "08:00-16:00"))
        assertEquals("", model.draft(0).dateText)
        model.updateIdentity(month = "10/2026")
        model.interpret()
        assertTrue(model.state.value.message!!.contains("Mes no válido"))
        assertNull(model.state.value.result)
        val reloaded = interpreted(ocrDay("2026-10-12", "08:00-16:00"))
        reloaded.markReviewed(0)
        reloaded.prepare(document, "ALFA", "") // Same OCR document: navigating back keeps review decisions.
        assertEquals(ReviewStatus.REVIEWED, reloaded.draft(0).status)
        reloaded.updateIdentity(name = "BETA")
        assertTrue(reloaded.state.value.drafts.isEmpty())
        reloaded.prepare(document.copy(text = "Other synthetic OCR"), "GAMMA", "")
        assertEquals("GAMMA", reloaded.state.value.name)
        reloaded.clear()
        assertNull(reloaded.state.value.result)
    }
}
