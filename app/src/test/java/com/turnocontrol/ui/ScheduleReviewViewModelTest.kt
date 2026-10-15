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
    private val day = ProvisionalDay(null, "MAR", "08:00-16:00", DayKind.WORK, emptyList(), InterpretationStatus.INCOMPLETE, listOf(ScheduleIssue.MISSING_DATE))
    private val fake = object : ScheduleInterpreter {
        override fun interpret(document: OcrDocument, worker: WorkerSelection, month: java.time.YearMonth?) = ScheduleResult(days = listOf(day))
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun missingMonthContextIsNotReplacedByTodayAndManualCorrectionIsTemporary() = runTest(dispatcher) {
        val model = ScheduleReviewViewModel(fake)
        model.prepare(document, "ALFA", "")
        model.interpret()
        val result = model.state.first { it.result != null && !it.loading }
        assertNull(result.drafts.single().source.date)
        model.edit(0, date = "2026-10-13", time = "22:00-02:00")
        assertTrue(model.state.value.drafts.single().dirty)
        model.correct(0)
        val corrected = model.state.value.drafts.single()
        assertEquals(LocalDate.of(2026,10,13), corrected.source.date)
        assertEquals(InterpretationStatus.CORRECTED, corrected.source.status)
        assertEquals(1, corrected.source.intervals.single().endDayOffset)
        assertEquals("08:00-16:00", corrected.source.sourceText)
        assertFalse(ScheduleIssue.MISSING_DATE in corrected.source.issues)
        assertTrue(ScheduleIssue.MISSING_DATE in corrected.originalIssues)
        model.prepare(document, "ALFA", "")
        assertEquals(corrected, model.state.value.drafts.single())
        model.clear()
        assertTrue(model.state.value.drafts.isEmpty())
        assertNull(model.state.value.result)
    }
    @Test fun invalidInputsAndDuplicateManualDatesCannotBeApplied() = runTest(dispatcher) {
        val model = ScheduleReviewViewModel(fake)
        model.prepare(document, "ALFA", "")
        model.updateIdentity(month = "10/2026")
        model.interpret()
        assertTrue(model.state.value.message!!.contains("Mes no válido"))
        assertNull(model.state.value.result)
        model.addManualDay()
        model.edit(0, date = "2026-02-31", time = "8-16")
        model.correct(0)
        assertNotNull(model.state.value.drafts[0].error)
        assertNull(model.state.value.drafts[0].source.date)
        model.edit(0, date = "2026-10-13", time = "LIBRE")
        model.correct(0)
        model.addManualDay()
        model.edit(1, date = "2026-10-13", time = "08:00-16:00")
        model.correct(1)
        assertNotNull(model.state.value.drafts[1].error)
        assertNull(model.state.value.drafts[1].source.date)
    }
    @Test fun identityChangeInvalidatesOldResultsAndReplacingOcrClearsCorrections() = runTest(dispatcher) {
        val model = ScheduleReviewViewModel(fake)
        model.prepare(document, "ALFA", "")
        model.interpret()
        model.state.first { it.result != null && !it.loading }
        model.updateIdentity(name = "BETA")
        assertTrue(model.state.value.drafts.isEmpty())
        model.addManualDay()
        model.prepare(document.copy(text = "Other synthetic OCR"), "GAMMA", "")
        assertEquals("GAMMA", model.state.value.name)
        assertTrue(model.state.value.drafts.isEmpty())
    }
    @Test fun reportCorrectionsCanonicalizeNotesAndExcludeVacationsFromConfirmation() = runTest(dispatcher) {
        val model = ScheduleReviewViewModel(fake)
        model.prepare(document, "ALFA", "")
        model.addManualDay()
        model.edit(0, date = "2032-03-10", time = "18:00-00:00 (COC)")
        model.correct(0)
        assertEquals("18:00-24:00", model.state.value.drafts[0].timeText)
        assertTrue(model.state.value.drafts[0].source.intervals.none { it.isExtra })
        model.addManualDay()
        model.edit(1, date = "2032-03-11", time = "VAC")
        model.correct(1)
        assertEquals(ProvisionalAbsence.VACATION, model.state.value.drafts[1].source.absence)
        assertEquals(DayKind.UNKNOWN, model.state.value.drafts[1].source.kind)
        assertEquals(1, model.confirmableDrafts().size)
        model.edit(1, time = "LIB")
        model.correct(1)
        assertNull(model.state.value.drafts[1].source.absence)
        assertEquals(DayKind.FREE, model.state.value.drafts[1].source.kind)
        assertEquals(2, model.confirmableDrafts().size)
    }
}
