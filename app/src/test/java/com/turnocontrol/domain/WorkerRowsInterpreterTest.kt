package com.turnocontrol.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class WorkerRowsInterpreterTest {
    private val interpreter: ScheduleInterpreter = WorkerRowsInterpreter()
    private fun token(text: String, x: Int, y: Int, width: Int = 130, confidence: Float? = .95f) =
        OcrRegion(text, OcrBox(x - width / 2, y, x + width / 2, y + 20), emptyList(), "es", confidence)
    private fun doc(vararg tokens: OcrRegion): OcrDocument {
        val lines = tokens.groupBy { it.box?.top }.values.map { children -> OcrRegion(children.joinToString(" ") { it.text }, null, emptyList(), "es", children = children) }
        return OcrDocument("Synthetic table", 1000, 600, listOf(OcrRegion("Synthetic table", null, emptyList(), "es", children = lines)))
    }
    private fun table(first: String = "08:00-12:00 / 16:00-20:00", second: String = "LIBRE", name: String = "ALFA UNO") = doc(
        token("2026-10-13", 400, 40), token("2026-10-14", 700, 40),
        token(name, 100, 120), token(first, 400, 120, 250), token(second, 700, 120),
        token("ALFA DOS", 100, 220), token("09:00-17:00", 400, 220), token("10:00-18:00", 700, 220))

    @Test fun selectsOnlyCompleteWorkerAndRecognizesSplitAndFreeDays() {
        val result = interpreter.interpret(table(), WorkerSelection("ALFA UNO"))
        assertEquals(2, result.days.size)
        assertEquals(LocalDate.of(2026, 10, 13), result.days[0].date)
        assertEquals(2, result.days[0].intervals.size)
        assertEquals(InterpretationStatus.READY, result.days[0].status)
        assertEquals(DayKind.FREE, result.days[1].kind)
        assertFalse(result.days.any { it.sourceText.contains("09:00") })
    }
    @Test fun noPrefixSubstringOrFuzzyIdentityIsUsed() {
        listOf("ALFA", "ALFA UN", "ALFA UN0", "UNO").forEach { query ->
            val result = interpreter.interpret(table(), WorkerSelection(query))
            assertTrue(result.days.isEmpty())
            assertTrue(result.issues.contains(ScheduleIssue.WORKER_NOT_FOUND))
        }
    }
    @Test fun aliasAndCaseAccentsWhitespaceAreExplicitExactIdentities() {
        val result = interpreter.interpret(table(name = "ÁLFA UNO"), WorkerSelection("SIN COINCIDENCIA", "  alfa   uno  "))
        assertEquals(2, result.days.size)
    }
    @Test fun multiElementNamesAreComparedAsACompleteCell() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40),
            token("ALFA", 70, 120, 50), token("UNO", 140, 120, 50), token("LIBRE", 400, 120))
        assertEquals(2, interpreter.interpret(document, WorkerSelection("ALFA UNO")).days.size)
        assertTrue(interpreter.interpret(document, WorkerSelection("ALFA")).days.isEmpty())
    }
    @Test fun incompleteDatesAcrossDifferentMonthsDoNotInventAYearBoundary() {
        val document = doc(token("31/12", 400, 40), token("01/01", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"), YearMonth.of(2026,12))
        assertEquals(LocalDate.of(2026,12,31), result.days[0].date)
        assertNull(result.days[1].date)
        assertTrue(ScheduleIssue.CONTEXT_CONFLICT in result.days[1].issues)
    }
    @Test fun fullySpecifiedDatesCanCrossMonthAndYearWithoutChangingContext() {
        val document = doc(token("31/12/2026", 400, 40), token("01/01/2027", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120))
        assertEquals(listOf(LocalDate.of(2026,12,31), LocalDate.of(2027,1,1)), interpreter.interpret(document, WorkerSelection("ALFA"), YearMonth.of(2026,12)).days.map { it.date })
    }
    @Test fun sourceMonthConflictsRequireExplicitContext() {
        val document = doc(token("OCTUBRE 2026", 500, 10, 220), token("13", 400, 50), token("14", 700, 50),
            token("NOVIEMBRE 2026", 500, 80, 230), token("ALFA", 100, 160), token("LIBRE", 400, 160))
        assertTrue(ScheduleIssue.CONTEXT_CONFLICT in interpreter.interpret(document, WorkerSelection("ALFA")).issues)
        assertEquals(LocalDate.of(2026,10,13), interpreter.interpret(document, WorkerSelection("ALFA"), YearMonth.of(2026,10)).days[0].date)
    }
    @Test fun duplicatedNamesAndNameAliasMatchingDifferentRowsRequireConfirmation() {
        val duplicate = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40),
            token("ALFA", 100, 120), token("08:00-12:00", 400, 120),
            token("ALFA", 100, 220), token("16:00-20:00", 400, 220))
        val result = interpreter.interpret(duplicate, WorkerSelection("ALFA"))
        assertTrue(result.days.isEmpty())
        assertEquals(2, result.candidates.size)
        assertTrue(result.issues.contains(ScheduleIssue.DUPLICATE_WORKER))
        val confirmed = interpreter.interpret(duplicate, WorkerSelection("ALFA", confirmedRowId = result.candidates[1].id))
        assertEquals("16:00-20:00", confirmed.days[0].sourceText)
        val twoIdentities = interpreter.interpret(table(), WorkerSelection("ALFA UNO", "ALFA DOS"))
        assertTrue(twoIdentities.days.isEmpty())
    }
    @Test fun incompleteDatesNeverUseCurrentDateAndExplicitContextResolvesThem() {
        val document = doc(token("13/10", 400, 40), token("14/10", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120))
        val unknown = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertTrue(unknown.days.all { it.date == null && it.issues.contains(ScheduleIssue.MISSING_DATE) })
        val resolved = interpreter.interpret(document, WorkerSelection("ALFA"), YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 10, 13), resolved.days[0].date)
    }
    @Test fun monthlyTitleAndSeparateWeekdayAndNumberRowsAreReconstructed() {
        val document = doc(token("OCTUBRE 2026", 500, 10, 220), token("MAR", 400, 50), token("MIE", 700, 50),
            token("13", 400, 80), token("14", 700, 80), token("ALFA", 100, 160), token("LIBRE", 400, 160), token("08:00-16:00", 700, 160))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertEquals(2, result.days.size)
        assertEquals(LocalDate.of(2026, 10, 14), result.days[1].date)
        assertFalse(result.days.any { ScheduleIssue.WEEKDAY_MISMATCH in it.issues })
    }
    @Test fun weekdayOnlyDoesNotCreateDatesEvenWithMonthContext() {
        val document = doc(token("LUNES", 400, 40), token("MARTES", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120))
        assertTrue(interpreter.interpret(document, WorkerSelection("ALFA"), YearMonth.of(2026, 10)).days.all { it.date == null })
    }
    @Test fun completeHeadersWithWeekdayValidateCalendarAndInvalidDatesStayNull() {
        val document = doc(token("LUN 13/10/2026", 400, 40, 230), token("31/02/2026", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertTrue(result.days[0].issues.contains(ScheduleIssue.WEEKDAY_MISMATCH))
        assertNull(result.days[1].date)
        assertTrue(result.days[1].issues.contains(ScheduleIssue.INVALID_DATE))
    }
    @Test fun blankUnknownAbbreviationsAndIncompleteOcrAreNotFreeOrInventedTimes() {
        listOf("", "L", "--", "8-16", "08:00-", "08:00-12:00 ?").forEach { text ->
            val result = interpreter.interpret(table(first = text), WorkerSelection("ALFA UNO"))
            // An unknown alphabetic code beside the name can also be a displaced name fragment.
            if (result.days.isEmpty()) { assertTrue(ScheduleIssue.ROW_AMBIGUOUS in result.issues); return@forEach }
            val day = result.days[0]
            assertEquals(DayKind.UNKNOWN, day.kind)
            assertTrue(day.intervals.isEmpty())
            assertEquals(InterpretationStatus.INCOMPLETE, day.status)
        }
    }
    @Test fun missingGeometryPreventsAssignmentToAnotherWorker() {
        val broken = table().copy(blocks = table().blocks + OcrRegion("UNLOCATED", null, emptyList(), "es"))
        val result = interpreter.interpret(broken, WorkerSelection("ALFA UNO"))
        assertTrue(result.days.isEmpty())
        assertTrue(ScheduleIssue.MISSING_GEOMETRY in result.issues)
    }
    @Test fun cornerCoordinatesCanSupplyMissingBox() {
        val original = table()
        fun corners(region: OcrRegion): OcrRegion = region.copy(box = null, corners = region.box?.let { listOf(OcrPoint(it.left,it.top), OcrPoint(it.right,it.top), OcrPoint(it.right,it.bottom), OcrPoint(it.left,it.bottom)) }.orEmpty(), children = region.children.map(::corners))
        assertEquals(2, interpreter.interpret(original.copy(blocks = original.blocks.map(::corners)), WorkerSelection("ALFA UNO")).days.size)
    }
    @Test fun crossingBoundaryDoesNotExtractHoursIntoWrongColumn() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40), token("ALFA", 100, 120), token("08:00-16:00", 550, 120, 200))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertTrue(result.days.all { it.kind == DayKind.UNKNOWN && it.intervals.isEmpty() && ScheduleIssue.COLUMN_AMBIGUOUS in it.issues })
    }
    @Test fun shiftedCellIsFlaggedEvenWhenItDoesNotCrossBoundary() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40), token("ALFA", 100, 120), token("08:00-16:00", 490, 120, 100))
        assertTrue(ScheduleIssue.COLUMN_SHIFT in interpreter.interpret(document, WorkerSelection("ALFA")).days[0].issues)
    }
    @Test fun duplicateDateColumnsStayProvisional() {
        val document = doc(token("13/10/2026", 400, 40), token("13/10/2026", 700, 40), token("ALFA", 100, 120), token("LIBRE", 400, 120), token("LIBRE", 700, 120))
        assertTrue(interpreter.interpret(document, WorkerSelection("ALFA")).days.all { ScheduleIssue.DUPLICATE_DATE in it.issues && it.status == InterpretationStatus.NEEDS_REVIEW })
    }
    @Test fun uncertainWorkerRequiresExplicitRowConfirmation() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40), token("ALFA", 100, 120, confidence = .2f), token("LIBRE", 400, 120))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertTrue(result.days.isEmpty())
        assertTrue(ScheduleIssue.LOW_CONFIDENCE in result.issues)
        assertEquals(2, interpreter.interpret(document, WorkerSelection("ALFA", confirmedRowId = result.candidates.single().id)).days.size)
    }
    @Test fun nameSpillingIntoDateAreaCannotSelectAWorkerByItsPrefix() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40),
            token("ALFA", 100, 120), token("UNO", 300, 120, 60), token("LIBRE", 700, 120))
        val result = interpreter.interpret(document, WorkerSelection("ALFA"))
        assertTrue(result.days.isEmpty())
        assertTrue(ScheduleIssue.ROW_AMBIGUOUS in result.issues)
    }
    @Test fun multiLineSplitCellsArePreservedButWarnAboutRowAssignment() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40), token("ALFA", 100, 120),
            token("08:00-12:00", 400, 120), token("16:00-20:00", 400, 145), token("BETA", 100, 220), token("09:00-17:00", 400, 220))
        val day = interpreter.interpret(document, WorkerSelection("ALFA")).days[0]
        assertEquals(2, day.intervals.size)
        assertTrue(ScheduleIssue.ROW_AMBIGUOUS in day.issues)
        assertFalse(day.sourceText.contains("09:00"))
    }
    @Test fun disconnectedUnlabelledRowsAreNotSilentlyAppended() {
        val document = doc(token("13/10/2026", 400, 40), token("14/10/2026", 700, 40), token("ALFA", 100, 120), token("08:00-12:00", 400, 120), token("16:00-20:00", 400, 240))
        val day = interpreter.interpret(document, WorkerSelection("ALFA")).days[0]
        assertEquals(1, day.intervals.size)
        assertTrue(ScheduleIssue.ROW_AMBIGUOUS in day.issues)
    }
    @Test fun unsupportedTransposedOrTextOnlyLayoutsAreHonest() {
        val transposed = doc(token("ALFA", 400, 40), token("BETA", 700, 40), token("13/10/2026", 100, 120), token("08:00-16:00", 400, 120))
        assertTrue(ScheduleIssue.UNSUPPORTED_LAYOUT in interpreter.interpret(transposed, WorkerSelection("ALFA")).issues)
        assertTrue(interpreter.interpret(OcrDocument("ALFA 13/10/2026 08:00-16:00", 1000, 600, emptyList()), WorkerSelection("ALFA")).days.isEmpty())
    }
}
