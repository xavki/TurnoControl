package com.turnocontrol.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeeklyReportTest {
    private fun t(text: String, x: Int, y: Int, width: Int = 78) = OcrRegion(text, OcrBox(x - width / 2, y - 5, x + width / 2, y + 5), emptyList(), "es", .99f)
    private fun report(perspective: Boolean = false, damaged: Boolean = false): OcrDocument {
        val values = mutableListOf<OcrRegion>()
        fun y(reference: Int, x: Int) = if (perspective) reference + ((reference - 50) * (x - 70) / 10000.0).toInt() else reference
        values += t("HORARIO POR TRABAJADOR", 320, 15, 230)
        values += t("TRABAJADOR", 70, 50, 100)
        repeat(7) { i -> val x = 250 + i * 110; values += t("${10 + i}/03/2032", x, y(50,x)) }
        values += t("Horas Proyectadas", 1050, 50, 110)
        listOf("ALFA. S. (X123)", "BETA. S. (X124)", "ALFA. S. (Y123)").forEachIndexed { row, name ->
            val cy = 120 + row * 65
            values += t(name, 80, cy, 140)
            repeat(7) { col ->
                val x = 250 + col * 110
                when (col) {
                    0 -> { values += t("08:15-12:00 (COC)", x, y(cy - 10,x), 104); values += t("16:00-20:30 (GER)", x, y(cy + 10,x), 104) }
                    1 -> values += t("LIB", x, y(cy,x), 30)
                    2 -> values += t("VAC", x, y(cy,x), 30)
                    3 -> values += t("18:00-00:00 (Sala)", x, y(cy,x), 104)
                    4 -> values += t("19:00-00:45 (Rep)", x, y(cy,x), 104)
                    5 -> values += t(if (damaged) "08:00-?? (COC)" else "09:00-13:00", x, y(cy,x), 92)
                    6 -> values += t("LIB", x, y(cy,x), 30)
                }
            }
            values += t("40:00", 1050, y(cy,1050), 45)
        }
        values += t("Horas en el Centro", 80, 320, 145)
        repeat(7) { i -> val x = 250 + i * 110; values += t("60:00", x, y(320,x), 45) }
        values += t("420:00", 1050, y(320,1050), 50)
        return OcrDocument("Synthetic report", 1150, 420, values)
    }
    @Test fun sevenDatesAuxiliaryColumnAndTallSplitRowsAreReconstructed() {
        val result = WorkerRowsInterpreter().interpret(report(), WorkerSelection("ALFA. S. (X123)"))
        assertEquals(7, result.days.size)
        assertEquals(LocalDate.of(2032,3,10), result.days[0].date)
        assertEquals(2, result.days[0].intervals.size)
        assertEquals(DayKind.FREE, result.days[1].kind)
        assertEquals(ProvisionalAbsence.VACATION, result.days[2].absence)
        assertEquals(DayKind.UNKNOWN, result.days[2].kind)
        assertEquals(1, result.days[3].intervals.single().endDayOffset)
        assertEquals(1, result.days[4].intervals.single().endDayOffset)
        assertTrue(result.days.flatMap { it.intervals }.none { it.isExtra })
        assertFalse(result.days.any { it.sourceText.contains("40:00") || it.sourceText.contains("60:00") })
    }
    @Test fun punctuationCodeDifferencesNeverAutoSelectButAnyRowCanBeConfirmed() {
        val interpreter = WorkerRowsInterpreter()
        val result = interpreter.interpret(report(), WorkerSelection("ALFA S"))
        assertTrue(result.days.isEmpty())
        assertEquals(3, result.candidates.size)
        val selected = interpreter.interpret(report(), WorkerSelection("ALFA S", confirmedRowId = result.candidates[2].id))
        assertEquals(7, selected.days.size)
        assertEquals(2, selected.selectedRowId)
        assertTrue(selected.days.all { ScheduleIssue.MANUAL_ROW in it.issues })
    }
    @Test fun spacesAroundExistingDotsAndSplitCodeKeepExactFullIdentity() {
        val original = report()
        val changed = original.copy(blocks = original.blocks.filterNot { it.text == "ALFA. S. (X123)" } + listOf(
            t("ALFA", 35, 120, 35), t(".", 60, 120, 5), t("S", 80, 120, 10), t(".", 95, 120, 5),
            t("(", 40, 132, 5), t("X123", 70, 132, 35), t(")", 100, 132, 5)))
        val result = WorkerRowsInterpreter().interpret(changed, WorkerSelection("ALFA. S. (X123)"))
        assertEquals(3, result.candidates.size)
        assertEquals(7, result.days.size)
        assertEquals(2, result.days[0].intervals.size)
        assertTrue(WorkerRowsInterpreter().interpret(changed, WorkerSelection("ALFA. S. (X12)" )).days.isEmpty())
    }
    @Test fun perspectiveFooterGuidesKeepSevenCellsInsideOneRow() {
        val result = WorkerRowsInterpreter().interpret(report(perspective = true), WorkerSelection("BETA. S. (X124)"))
        assertEquals(7, result.days.size)
        assertEquals(2, result.days[0].intervals.size)
        assertEquals(DayKind.FREE, result.days.last().kind)
        assertEquals(4, result.candidates[1].corners.size)
    }
    @Test fun leaningFirstDateColumnDoesNotBecomePartOfWorkerNames() {
        val original = report()
        val projected = original.copy(blocks = original.blocks.map { region ->
            val b = region.box!!
            val y = (b.top + b.bottom) / 2.0
            val fraction = ((y - 50) / 270).coerceIn(0.0, 1.0)
            fun x(value: Int) = (value - 80 * fraction + .09 * fraction * (value - 250)).toInt()
            region.copy(box = OcrBox(x(b.left), b.top, x(b.right), b.bottom))
        })
        val result = WorkerRowsInterpreter().interpret(projected, WorkerSelection("ALFA. S. (Y123)"))
        assertEquals(3, result.candidates.size)
        assertEquals(7, result.days.size)
        assertEquals(2, result.days[0].intervals.size)
    }
    @Test fun incompleteHoursAreNotPartiallyRecovered() {
        val result = WorkerRowsInterpreter().interpret(report(damaged = true), WorkerSelection("BETA. S. (X124)"))
        assertEquals(7, result.days.size)
        assertEquals(DayKind.UNKNOWN, result.days[5].kind)
        assertTrue(result.days[5].intervals.isEmpty())
    }
    @Test fun unreadableTotalsLabelUsesOnlyGeometryAndRequiresReview() {
        val original = report()
        val missingLabel = original.copy(blocks = original.blocks.filterNot { it.text == "Horas en el Centro" })
        val result = WorkerRowsInterpreter().interpret(missingLabel, WorkerSelection("BETA. S. (X124)"))
        assertEquals(7, result.days.size)
        assertEquals(2, result.days[0].intervals.size)
        assertTrue(result.days.all { ScheduleIssue.ROW_AMBIGUOUS in it.issues })
    }
    @Test fun manualBandWorksWithoutIdentityAndInvalidBandsDoNotAssignHours() {
        val interpreter = WorkerRowsInterpreter()
        val selected = interpreter.interpret(report(), WorkerSelection("", manualBand = ManualRowBand(88,152)))
        assertEquals(7, selected.days.size)
        assertEquals(2, selected.days[0].intervals.size)
        assertTrue(interpreter.interpret(report(), WorkerSelection("", manualBand = ManualRowBand(170,100))).days.isEmpty())
    }
    @Test fun knownAnnotationsAreExplicitAndVacationsStayNonSaveable() {
        assertEquals(2, ReportCellParser.parse("08:15-12:00 ( COC )\n16:00-20:30 (Rep)").intervals.size)
        assertEquals(DayKind.UNKNOWN, ReportCellParser.parse("08:00-12:00 (UNKNOWN)").kind)
        assertEquals(DayKind.FREE, ReportCellParser.parse("LIB").kind)
        assertEquals(DayKind.FREE, ReportCellParser.parse("L I B").kind)
        assertEquals(DayKind.UNKNOWN, ReportCellParser.parse("UB").kind)
        assertEquals(ProvisionalAbsence.VACATION, ReportCellParser.parse("VAC").absence)
        assertEquals(DayKind.UNKNOWN, ReportCellParser.parse("VAC 08:00-12:00").kind)
    }
}
