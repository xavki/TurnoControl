package com.turnocontrol.domain

/** Report-only vocabulary. Unknown annotations are deliberately not removed. */
object ReportCellParser {
    private val knownNote = Regex("\\(\\s*(?:COC|GER|SALA|REP)\\s*\\)", RegexOption.IGNORE_CASE)
    fun parse(text: String): ParsedTimeCell {
        val clean = knownNote.replace(text, " ").trim()
        // OCR can emit the letters of a short code as separate elements. Whitespace is
        // layout, but a different letter (for example an uncertain glyph) is never repaired.
        return when (normalizedLabel(clean).replace(" ", "")) {
            "LIB" -> ParsedTimeCell(DayKind.FREE, emptyList(), emptyList())
            "VAC" -> ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.VACATION_NOT_SAVED), ProvisionalAbsence.VACATION)
            else -> TimeCellParser.parse(clean)
        }
    }
}

/** The original OCR is kept separately. Only validated ranges are passed to the existing save flow. */
fun ProvisionalDay.reviewText(): String = when {
    absence == ProvisionalAbsence.VACATION -> "VAC"
    kind == DayKind.FREE -> "LIBRE"
    kind == DayKind.WORK -> intervals.joinToString(" / ") { "${it.start}-${if (it.endDayOffset > it.startDayOffset && it.end.toString() == "00:00") "24:00" else it.end}" }
    else -> sourceText
}
