package com.turnocontrol.domain

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Conservative strategy for date columns and worker rows. Other layouts can implement ScheduleInterpreter. */
class WorkerRowsInterpreter : ScheduleInterpreter {
    private data class Token(val text: String, val box: OcrBox, val confidence: Float?) {
        val x get() = (box.left + box.right) / 2.0
        val y get() = (box.top + box.bottom) / 2.0
        val height get() = box.bottom - box.top
    }
    private data class VisualRow(val tokens: MutableList<Token>) {
        val y get() = tokens.map { it.y }.average()
        val height get() = tokens.map { it.height }.sorted().let { it[it.size / 2] }
    }
    private data class Header(val token: Token, val date: LocalDate?, val issues: List<ScheduleIssue>)
    private val weekdays = mapOf("LUNES" to 1, "LUN" to 1, "L" to 1, "MARTES" to 2, "MAR" to 2, "M" to 2,
        "MIERCOLES" to 3, "MIE" to 3, "X" to 3, "JUEVES" to 4, "JUE" to 4, "J" to 4,
        "VIERNES" to 5, "VIE" to 5, "V" to 5, "SABADO" to 6, "SAB" to 6, "S" to 6,
        "DOMINGO" to 7, "DOM" to 7, "D" to 7)
    private val months = listOf("ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO", "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE")
    private val iso = Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")
    private val numeric = Regex("^(\\d{1,2})(?:[/-](\\d{1,2})(?:[/-](\\d{4}))?)?$")

    override fun interpret(document: OcrDocument, worker: WorkerSelection, month: YearMonth?): ScheduleResult {
        WeeklyReportInterpreter.interpret(document, worker)?.let { return it }
        val identities = listOf(worker.name, worker.alias).map(::normalizedWorkerLabel).filter(String::isNotEmpty).toSet()
        val tokens = mutableListOf<Token>()
        var missingGeometry = false
        fun flatten(region: OcrRegion, parentConfidence: Float? = null) {
            val confidence = listOfNotNull(parentConfidence, region.confidence).minOrNull()
            val box = region.box ?: region.corners.takeIf { it.size >= 4 }?.let { points ->
                OcrBox(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
            }
            if (region.children.isNotEmpty()) region.children.forEach { flatten(it, confidence) }
            else if (region.text.isNotBlank()) {
                if (box == null || box.right <= box.left || box.bottom <= box.top || region.text.contains('\n')) missingGeometry = true
                else tokens += Token(region.text, box, confidence)
            }
        }
        document.blocks.forEach { flatten(it) }
        // Missing leaves could belong to another worker or an incomplete date. Never guess their location.
        if (missingGeometry) return ScheduleResult(issues = listOf(ScheduleIssue.MISSING_GEOMETRY))
        if (tokens.isEmpty()) return ScheduleResult(issues = listOf(ScheduleIssue.UNSUPPORTED_LAYOUT))
        val rows = mutableListOf<VisualRow>()
        tokens.sortedBy { it.y }.forEach { token ->
            val row = rows.lastOrNull()?.takeIf { abs(it.y - token.y) <= max(3.0, min(it.height, token.height) * .55) }
            if (row == null) rows += VisualRow(mutableListOf(token)) else row.tokens += token
        }
        rows.forEach { it.tokens.sortBy(Token::x) }
        val sourceMonths = rows.mapNotNull { row ->
            val text = normalizedLabel(row.tokens.joinToString(" ") { it.text })
            val match = Regex("\\b(${months.joinToString("|")})\\s+(\\d{4})\\b").find(text)
            match?.let { runCatching { YearMonth.of(it.groupValues[2].toInt(), months.indexOf(it.groupValues[1]) + 1) }.getOrNull() }
        }.distinct()
        val context = month ?: sourceMonths.singleOrNull()
        val globalIssues = if (month == null && sourceMonths.size > 1) listOf(ScheduleIssue.CONTEXT_CONFLICT) else emptyList()
        // Join nearby OCR elements for a header such as “LUN 13/10/2026” or “13 / 10 / 2026”.
        fun clusters(row: VisualRow): List<Token> {
            val groups = mutableListOf<MutableList<Token>>()
            row.tokens.forEach { token ->
                if (groups.isEmpty() || token.box.left - groups.last().last().box.right > row.height * .8) groups += mutableListOf(token)
                else groups.last() += token
            }
            return groups.map { group ->
                Token(group.joinToString(" ") { it.text }, OcrBox(group.minOf { it.box.left }, group.minOf { it.box.top }, group.maxOf { it.box.right }, group.maxOf { it.box.bottom }),
                    group.mapNotNull { it.confidence }.minOrNull())
            }
        }
        fun header(token: Token): Header? {
            var text = normalizedLabel(token.text)
            val first = text.substringBefore(' ')
            val weekday = weekdays[first]
            if (weekday != null) text = text.removePrefix(first).trim()
            text = text.replace(" ", "")
            if (text.isEmpty() && weekday != null) return Header(token, null, listOf(ScheduleIssue.MISSING_DATE))
            val issues = mutableListOf<ScheduleIssue>()
            val date: LocalDate? = if (iso.matches(text)) {
                runCatching { val parts = text.split('-').map(String::toInt); LocalDate.of(parts[0], parts[1], parts[2]) }.getOrElse { issues += ScheduleIssue.INVALID_DATE; null }
            } else {
                val match = numeric.matchEntire(text) ?: return null
                val d = match.groupValues[1].toInt()
                if (d !in 1..31) return Header(token, null, listOf(ScheduleIssue.INVALID_DATE))
                val m = match.groupValues[2].toIntOrNull() ?: context?.monthValue
                val y = match.groupValues[3].toIntOrNull() ?: context?.year
                if (m == null || y == null) { issues += ScheduleIssue.MISSING_DATE; null }
                else if (match.groupValues[2].isNotEmpty() && match.groupValues[3].isEmpty() && context != null && m != context.monthValue) {
                    issues += ScheduleIssue.CONTEXT_CONFLICT; null
                }
                else runCatching { LocalDate.of(y, m, d) }.getOrElse { issues += ScheduleIssue.INVALID_DATE; null }
            }
            if (date != null && weekday != null && date.dayOfWeek.value != weekday) issues += ScheduleIssue.WEEKDAY_MISMATCH
            if (token.confidence != null && token.confidence < .65f) issues += ScheduleIssue.LOW_CONFIDENCE
            return Header(token, date, issues)
        }
        val headerRowIndex = rows.indexOfFirst { row ->
            val cells = clusters(row)
            val dateCells = cells.mapNotNull(::header)
            dateCells.isNotEmpty() && cells.all { header(it) != null || normalizedLabel(it.text) in setOf("TRABAJADOR", "TRABAJADORES", "PERSONA", "NOMBRE", "FECHA") }
        }
        if (headerRowIndex < 0) return ScheduleResult(issues = globalIssues + ScheduleIssue.UNSUPPORTED_LAYOUT)
        // Prefer numeric dates below a separate weekday row, without treating weekdays as a second set of columns.
        var anchorIndex = headerRowIndex
        val firstCells = clusters(rows[anchorIndex]).mapNotNull(::header)
        if (firstCells.all { it.token.text.trim().let(::normalizedLabel) in weekdays }) {
            val next = rows.getOrNull(anchorIndex + 1)
            if (next != null && next.y - rows[anchorIndex].y <= rows[anchorIndex].height * 3 && clusters(next).count { header(it) != null } == firstCells.size) anchorIndex++
        }
        var headers = clusters(rows[anchorIndex]).mapNotNull(::header).sortedBy { it.token.x }
        if (headers.isEmpty()) return ScheduleResult(issues = globalIssues + ScheduleIssue.UNSUPPORTED_LAYOUT)
        if (headers.size == 1 && headers.first().token.x <= document.width / 3.0) return ScheduleResult(issues = globalIssues + ScheduleIssue.UNSUPPORTED_LAYOUT)
        val gaps = headers.zipWithNext { a, b -> b.token.x - a.token.x }.sorted()
        val spacing = gaps.getOrNull(gaps.size / 2) ?: max(headers.first().token.box.right - headers.first().token.box.left, document.width / 3).toDouble()
        val leftEdge = headers.first().token.x - (headers.zipWithNext { a, b -> b.token.x - a.token.x }.firstOrNull() ?: spacing) / 2
        if (leftEdge <= 0 || gaps.any { it < max(4.0, spacing * .3) }) return ScheduleResult(issues = globalIssues + ScheduleIssue.UNSUPPORTED_LAYOUT)
        // Associate weekday labels from a separate adjacent row using their x geometry.
        val weekdayTokens = rows.filterIndexed { index, row -> index != anchorIndex && abs(row.y - rows[anchorIndex].y) <= row.height * 3 &&
            row.tokens.filter { it.x >= leftEdge }.let { cells -> cells.isNotEmpty() && cells.all { normalizedLabel(it.text) in weekdays } } }
            .flatMap { it.tokens }.filter { normalizedLabel(it.text) in weekdays && it.x >= leftEdge }
        headers = headers.map { h ->
            val labels = weekdayTokens.filter { abs(it.x - h.token.x) < spacing * .35 }.map { weekdays.getValue(normalizedLabel(it.text)) }.distinct()
            val lowConfidence = weekdayTokens.any { abs(it.x - h.token.x) < spacing * .35 && it.confidence?.let { c -> c < .65f } == true }
            h.copy(issues = h.issues +
                (if (labels.size > 1 || (h.date != null && labels.singleOrNull()?.let { it != h.date.dayOfWeek.value } == true)) listOf(ScheduleIssue.WEEKDAY_MISMATCH) else emptyList()) +
                (if (lowConfidence) listOf(ScheduleIssue.LOW_CONFIDENCE) else emptyList()))
        }
        val duplicateDates = headers.mapNotNull { it.date }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val headerBottom = max(rows[anchorIndex].tokens.maxOf { it.box.bottom }, weekdayTokens.maxOfOrNull { it.box.bottom } ?: 0)
        val labelRows = rows.filter { row -> row.y > headerBottom && row.tokens.any { it.x < leftEdge } }
        val allCandidates = labelRows.mapIndexed { index, row ->
            val label = row.tokens.filter { it.x < leftEdge }.joinToString(" ") { it.text }
            index to WorkerRowCandidate(index, label, row.tokens.minOf { it.box.top }, row.tokens.maxOf { it.box.bottom })
        }
        val matches = allCandidates.filter { normalizedWorkerLabel(it.second.label) in identities }
        val candidates = allCandidates.map { it.second }
        if (matches.isEmpty() && worker.confirmedRowId == null) return ScheduleResult(issues = globalIssues + ScheduleIssue.WORKER_NOT_FOUND, candidates = candidates)
        val chosen = if (worker.confirmedRowId == null) matches.singleOrNull() else allCandidates.singleOrNull { it.second.id == worker.confirmedRowId }
        if (chosen == null) return ScheduleResult(issues = globalIssues + ScheduleIssue.DUPLICATE_WORKER, candidates = candidates)
        val (labelIndex, candidate) = chosen
        val selectedLabelRow = labelRows[labelIndex]
        val firstColumnRight = headers.getOrNull(1)?.let { (headers[0].token.x + it.token.x) / 2 } ?: headers[0].token.x + spacing / 2
        val labelBoundaryUncertain = selectedLabelRow.tokens.any { it.box.left < leftEdge && it.box.right > leftEdge } ||
            selectedLabelRow.tokens.any { it.x >= leftEdge && it.x < firstColumnRight &&
                normalizedLabel(it.text).matches(Regex("[A-Z]+(?: [A-Z]+)*")) && normalizedLabel(it.text) !in setOf("LIBRE", "DESCANSO", "OFF") }
        val nearLabel = labelBoundaryUncertain || listOfNotNull(labelRows.getOrNull(labelIndex - 1), labelRows.getOrNull(labelIndex + 1)).any { abs(it.y - selectedLabelRow.y) < selectedLabelRow.height * 1.6 }
        val uncertainName = selectedLabelRow.tokens.filter { it.x < leftEdge }.any { it.confidence?.let { c -> c < .65f } == true }
        if ((nearLabel || uncertainName) && worker.confirmedRowId == null) {
            return ScheduleResult(issues = globalIssues + if (nearLabel) ScheduleIssue.ROW_AMBIGUOUS else ScheduleIssue.LOW_CONFIDENCE, candidates = candidates)
        }
        // Row bands end before the next worker label; continuation lines belong only to this band.
        val top = selectedLabelRow.tokens.minOf { it.box.top } - selectedLabelRow.height * .15
        val bottom = labelRows.getOrNull(labelIndex + 1)?.tokens?.minOf { it.box.top }?.minus(selectedLabelRow.height * .15) ?: document.height.toDouble()
        val bodyRows = rows.filter { it.y >= top && it.y < bottom && it.tokens.any { token -> token.x >= leftEdge } }
        val contiguous = mutableListOf<VisualRow>()
        var previousY = selectedLabelRow.y
        for (row in bodyRows) {
            if (row.y - previousY > selectedLabelRow.height * 1.6) break
            contiguous += row
            previousY = row.y
        }
        val body = contiguous.flatMap { it.tokens }.filter { it.x >= leftEdge }
        val rowIncomplete = bodyRows.size > contiguous.size || contiguous.size > 1
        val outerRight = headers.last().token.x + (headers.zipWithNext { a, b -> b.token.x - a.token.x }.lastOrNull() ?: spacing) / 2
        val extraColumns = body.any { it.x >= outerRight }
        val days = headers.mapIndexed { index, h ->
            val left = if (index == 0) leftEdge else (headers[index - 1].token.x + h.token.x) / 2
            val right = headers.getOrNull(index + 1)?.let { (it.token.x + h.token.x) / 2 } ?: outerRight
            val overlapping = body.filter { min(it.box.right.toDouble(), right) - max(it.box.left.toDouble(), left) > 0 }
            val ambiguous = overlapping.any { token ->
                val outside = max(0.0, left - token.box.left) + max(0.0, token.box.right - right)
                outside > (token.box.right - token.box.left) * .15
            }
            val assigned = overlapping.filter { it.x >= left && it.x < right }
            val raw = rows.map { row -> row.tokens.filter { it in assigned }.joinToString(" ") { it.text } }.filter(String::isNotBlank).joinToString("\n")
            val parsed = if (ambiguous) ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.COLUMN_AMBIGUOUS)) else ReportCellParser.parse(raw)
            val issues = (h.issues + globalIssues + parsed.issues +
                (if (h.date in duplicateDates) listOf(ScheduleIssue.DUPLICATE_DATE) else emptyList()) +
                (if (assigned.any { it.confidence?.let { c -> c < .65f } == true } || uncertainName) listOf(ScheduleIssue.LOW_CONFIDENCE) else emptyList()) +
                (if (nearLabel || rowIncomplete) listOf(ScheduleIssue.ROW_AMBIGUOUS) else emptyList()) +
                (if (extraColumns) listOf(ScheduleIssue.COLUMN_AMBIGUOUS) else emptyList()) +
                (if (assigned.isNotEmpty() && abs(assigned.map { it.x }.average() - h.token.x) > (right - left) * .28) listOf(ScheduleIssue.COLUMN_SHIFT) else emptyList())).distinct()
            ProvisionalDay(h.date, h.token.text, raw, parsed.kind, parsed.intervals,
                if (h.date == null || parsed.kind == DayKind.UNKNOWN) InterpretationStatus.INCOMPLETE else if (issues.isNotEmpty()) InterpretationStatus.NEEDS_REVIEW else InterpretationStatus.READY, issues, parsed.absence)
        }
        return ScheduleResult(days, globalIssues, candidates, candidate.id)
    }
}
