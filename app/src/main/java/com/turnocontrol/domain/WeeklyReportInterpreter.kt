package com.turnocontrol.domain

import java.time.LocalDate
import kotlin.math.*

/** Geometry strategy for seven-date worker reports. No dates, identities or times are inferred. */
internal object WeeklyReportInterpreter {
    private data class T(val text: String, val box: OcrBox, val confidence: Float?) {
        val x get() = (box.left + box.right) / 2.0
        val y get() = (box.top + box.bottom) / 2.0
        val h get() = box.bottom - box.top
    }
    private val datePattern = Regex("(?<!\\d)(\\d{1,2})/(\\d{1,2})/(\\d{4})(?!\\d)")
    private val timeOnly = Regex("^\\d{1,3}:\\d{2}$")
    private fun box(region: OcrRegion) = region.box ?: region.corners.takeIf { it.size >= 4 }?.let { OcrBox(it.minOf { p -> p.x }, it.minOf { p -> p.y }, it.maxOf { p -> p.x }, it.maxOf { p -> p.y }) }

    fun interpret(document: OcrDocument, worker: WorkerSelection): ScheduleResult? {
        val tokens = mutableListOf<T>()
        val lines = mutableListOf<T>()
        var missing = false
        fun visit(region: OcrRegion, confidence: Float? = null) {
            val c = listOfNotNull(confidence, region.confidence).minOrNull()
            val b = box(region)
            if (region.children.isNotEmpty()) {
                if (region.children.all { it.children.isEmpty() } && b != null) lines += T(region.text, b, c)
                region.children.forEach { visit(it, c) }
            } else if (region.text.isNotBlank()) {
                if (b == null || b.right <= b.left || b.bottom <= b.top) missing = true else tokens += T(region.text, b, c)
            }
        }
        document.blocks.forEach { visit(it) }
        val dates = tokens.mapNotNull { token ->
            val matches = datePattern.findAll(token.text.replace(" ", "")).toList()
            if (matches.size != 1) null else token to matches.single()
        }
        // A period in the title has two dates. It cannot masquerade as the seven-column header.
        val header = dates.map { seed -> dates.filter { abs(it.first.y - seed.first.y) < max(12.0, seed.first.h * 2.5) }.sortedBy { it.first.x } }
            .filter { it.size == 7 && it.zipWithNext().all { (a, b) -> b.first.x - a.first.x > max(8, a.first.h) } }
            .maxByOrNull { it.last().first.x - it.first().first.x } ?: return null
        val global = mutableListOf<ScheduleIssue>()
        if (missing) global += ScheduleIssue.MISSING_GEOMETRY
        val spacing = header.zipWithNext { a, b -> b.first.x - a.first.x }.sorted().let { it[it.size / 2] }
        val left = header.first().first.x - (header[1].first.x - header[0].first.x) / 2
        val right = header.last().first.x + (header.last().first.x - header[5].first.x) / 2
        if (left <= 0 || right <= left) return null
        val anchorX = tokens.filter { it.x < left }.map { it.x }.sorted().let { it.getOrNull(it.size / 2) ?: left / 2 }
        val meanX = header.map { it.first.x }.average()
        val meanY = header.map { it.first.y }.average()
        val denom = header.sumOf { (it.first.x - meanX).pow(2) }
        val slope = if (denom == 0.0) 0.0 else header.sumOf { (it.first.x - meanX) * (it.first.y - meanY) } / denom
        fun headerY(x: Double) = meanY + slope * (x - meanX)
        fun footerLabel(text: String): Boolean {
            val s = normalizedLabel(text)
            return s.replace(" ", "").endsWith("CENTRO") || s.startsWith("TOTAL")
        }
        var detectedFooter = (lines + tokens).filter { it.x < left && it.y > meanY && footerLabel(it.text) }.minByOrNull { it.y }
        var estimatedFooter = false
        val totalAnchors = tokens.filter { it.y > meanY + document.height * .3 && it.x > left - spacing && it.x < right && timeOnly.matches(it.text.trim()) &&
            it.text.trim().substringBefore(':').toIntOrNull()?.let { h -> h > 23 } == true }.sortedBy { it.x }
        if (detectedFooter == null && totalAnchors.size == 7) {
            val tx = totalAnchors.map { it.x }.average()
            val ty = totalAnchors.map { it.y }.average()
            val d = totalAnchors.sumOf { (it.x - tx).pow(2) }
            val m = if (d == 0.0) 0.0 else totalAnchors.sumOf { (it.x - tx) * (it.y - ty) } / d
            val y = (ty + m * (anchorX - tx)).roundToInt()
            detectedFooter = T("", OcrBox(anchorX.roundToInt() - 1, y - 1, anchorX.roundToInt() + 1, y + 1), null)
            estimatedFooter = true
        }
        val footer = detectedFooter
        // Durations greater than a clock hour cannot be worker entry/exit times. A complete
        // seven-value totals row supplies ordered geometry even when columns lean strongly.
        val strongFooter = tokens.filter { footer != null && it.y >= footer.y - it.h * 2 && it.x > left - spacing && it.x < right &&
            timeOnly.matches(it.text.trim()) && it.text.trim().substringBefore(':').toIntOrNull()?.let { h -> h > 23 } == true }.sortedBy { it.x }
        val footerColumns = if (strongFooter.size == 7) strongFooter.map { it.x to it.y } else header.mapIndexed { index, _ ->
            tokens.filter { token -> footer != null && token.y > footer.y - document.height * .12 && timeOnly.matches(token.text.trim()) &&
                token.x < right && token.x > left - spacing * .5 && header.indices.minByOrNull { abs(header[it].first.x - token.x) } == index }
                .maxByOrNull { it.y }?.let { it.x to it.y }
        }
        val footerAnchors = footerColumns.filterNotNull().toMutableList()
        if (footer != null) footerAnchors += anchorX to footer.y
        footerAnchors.sortBy { it.first }
        fun footerY(x: Double): Double? {
            if (footer == null || footerAnchors.size < 4) return null
            val pair = footerAnchors.zipWithNext().firstOrNull { x >= it.first.first && x <= it.second.first }
                ?: if (x < footerAnchors.first().first) footerAnchors.zipWithNext().first() else footerAnchors.zipWithNext().last()
            val (a, b) = pair
            return a.second + (b.second - a.second) * (x - a.first) / (b.first - a.first).coerceAtLeast(1.0)
        }
        fun normalizedY(token: T): Double {
            val base = headerY(anchorX)
            val end = footerY(token.x)
            return if (end != null && footer != null && end - headerY(token.x) > 1) base + (token.y - headerY(token.x)) * (footer.y - base) / (end - headerY(token.x))
                else token.y - slope * (token.x - anchorX)
        }
        fun physicalY(y: Double, x: Double): Int {
            val base = headerY(anchorX)
            val end = footerY(x)
            return (if (end != null && footer != null && footer.y - base > 1) headerY(x) + (y - base) * (end - headerY(x)) / (footer.y - base)
                else y + slope * (x - anchorX)).roundToInt()
        }
        // Project slanted column borders as well as row baselines. Lower cells can move left
        // of the header's worker/date boundary; fixed x bands would merge them into names.
        fun projectedX(x: Double, referenceY: Double): Double {
            if (footer == null || footerColumns.any { it == null }) return x
            val fraction = ((referenceY - headerY(anchorX)) / (footer.y - headerY(anchorX))).coerceIn(0.0, 1.0)
            val centersAtY = header.indices.map { i -> header[i].first.x + (footerColumns[i]!!.first - header[i].first.x) * fraction }
            val i = (0..5).firstOrNull { x >= centersAtY[it] && x <= centersAtY[it + 1] } ?: if (x < centersAtY.first()) 0 else 5
            return header[i].first.x + (x - centersAtY[i]) * (header[i + 1].first.x - header[i].first.x) / (centersAtY[i + 1] - centersAtY[i]).coerceAtLeast(1.0)
        }
        fun px(token: T) = projectedX(token.x, normalizedY(token))
        fun pl(token: T) = projectedX(token.box.left.toDouble(), normalizedY(token))
        fun pr(token: T) = projectedX(token.box.right.toDouble(), normalizedY(token))
        val labelTokens = tokens.filter { px(it) < left && normalizedY(it) > headerY(anchorX) + it.h && (footer == null || normalizedY(it) < footer.y - it.h * .5) && !footerLabel(it.text) }
        val nameGroups = mutableListOf<MutableList<T>>()
        labelTokens.sortedBy(::normalizedY).forEach { token ->
            val last = nameGroups.lastOrNull()
            if (last != null && abs(last.map(::normalizedY).average() - normalizedY(token)) <= max(3.0, token.h * .65)) last += token
            else if (last != null && normalizedLabel(token.text).matches(Regex("\\([A-Z0-9]+\\)")) && normalizedY(token) - last.map(::normalizedY).average() < token.h * 1.7) last += token
            else nameGroups += mutableListOf(token)
        }
        val joinedNames = mutableListOf<MutableList<T>>()
        nameGroups.forEach { group ->
            val code = normalizedLabel(group.sortedBy(T::x).joinToString(" ") { it.text }).matches(Regex("\\(\\s*[A-Z0-9]+\\s*\\)"))
            val previous = joinedNames.lastOrNull()
            if (code && previous != null && group.map(::normalizedY).average() - previous.map(::normalizedY).average() < group.maxOf { it.h } * 1.7) previous += group.sortedBy(T::x)
            else joinedNames += group.sortedBy(T::x).toMutableList()
        }
        val names = joinedNames.filter { it.any { t -> t.text.any(Char::isLetter) } }.map { it.toList() }
        if (names.isEmpty() && worker.manualBand == null) return ScheduleResult(issues = global + ScheduleIssue.WORKER_NOT_FOUND, layout = "weekly-report-v1")
        val centers = names.map { it.map(::normalizedY).average() }
        val medianGap = centers.zipWithNext { a, b -> b - a }.sorted().let { it.getOrNull(it.size / 2) } ?: (names.firstOrNull()?.maxOf { it.h } ?: 16) * 3.0
        fun bounds(index: Int): Pair<Double, Double> {
            val y = centers[index]
            val top = if (index == 0) max(headerY(anchorX) + 1, y - medianGap / 2) else (centers[index - 1] + y) / 2
            val bottom = centers.getOrNull(index + 1)?.let { (it + y) / 2 } ?: min(footer?.y ?: document.height.toDouble(), y + medianGap / 2)
            return top to bottom
        }
        val candidates = names.mapIndexed { index, parts ->
            val (top, bottom) = bounds(index)
            WorkerRowCandidate(index, parts.joinToString(" ") { it.text }, top.roundToInt(), bottom.roundToInt(), listOf(
                OcrPoint(0, physicalY(top, 0.0)), OcrPoint(right.roundToInt(), physicalY(top, right)),
                OcrPoint(right.roundToInt(), physicalY(bottom, right)), OcrPoint(0, physicalY(bottom, 0.0))))
        }
        val identities = listOf(worker.name, worker.alias).map(::normalizedWorkerLabel).filter(String::isNotBlank)
        val exact = candidates.filter { normalizedWorkerLabel(it.label) in identities }
        val chosen = if (worker.confirmedRowId != null) candidates.firstOrNull { it.id == worker.confirmedRowId } else exact.singleOrNull()
        if (chosen == null && worker.manualBand == null) return ScheduleResult(issues = global + if (exact.size > 1) ScheduleIssue.DUPLICATE_WORKER else ScheduleIssue.WORKER_NOT_FOUND, candidates = candidates, layout = "weekly-report-v1")
        val selectedParts = chosen?.let { names[it.id] }.orEmpty()
        val nameConfidence = selectedParts.mapNotNull { it.confidence }.minOrNull()
        if (chosen != null && worker.confirmedRowId == null && nameConfidence != null && nameConfidence < .65f) return ScheduleResult(issues = global + ScheduleIssue.LOW_CONFIDENCE, candidates = candidates, layout = "weekly-report-v1")
        val (top, bottom) = worker.manualBand?.let { it.top.toDouble() to it.bottom.toDouble() } ?: bounds(chosen!!.id)
        if (top < headerY(anchorX) || bottom <= top || bottom > (footer?.y ?: document.height.toDouble())) return ScheduleResult(issues = global + ScheduleIssue.ROW_AMBIGUOUS, candidates = candidates, layout = "weekly-report-v1")
        val manual = worker.manualBand != null || (chosen != null && normalizedWorkerLabel(chosen.label) !in identities)
        val body = tokens.filter { px(it) >= left && px(it) < right && normalizedY(it) >= top && normalizedY(it) < bottom }
        val days = header.mapIndexed { index, (anchor, match) ->
            val l = if (index == 0) left else (header[index - 1].first.x + anchor.x) / 2
            val r = header.getOrNull(index + 1)?.let { (it.first.x + anchor.x) / 2 } ?: right
            val overlapping = body.filter { min(pr(it), r) > max(pl(it), l) }
            val ambiguous = overlapping.any { (max(0.0, l - pl(it)) + max(0.0, pr(it) - r)) > (pr(it) - pl(it)) * .2 }
            val assigned = overlapping.filter { px(it) >= l && px(it) < r }
            val cellLines = mutableListOf<MutableList<T>>()
            assigned.sortedBy(::normalizedY).forEach { token ->
                val last = cellLines.lastOrNull()
                if (last != null && abs(last.map(::normalizedY).average() - normalizedY(token)) <= max(3.0, token.h * .65)) last += token else cellLines += mutableListOf(token)
            }
            val text = cellLines.joinToString("\n") { row -> row.sortedBy(T::x).joinToString(" ") { it.text } }
            val date = runCatching { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()) }.getOrNull()
            val parsed = if (ambiguous || missing) ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(if (missing) ScheduleIssue.MISSING_GEOMETRY else ScheduleIssue.COLUMN_AMBIGUOUS)) else ReportCellParser.parse(text)
            val issues = (global + parsed.issues + (if (date == null) listOf(ScheduleIssue.INVALID_DATE) else emptyList()) +
                (if (header.count { it.second.value == match.value } > 1) listOf(ScheduleIssue.DUPLICATE_DATE) else emptyList()) +
                (if (manual) listOf(ScheduleIssue.MANUAL_ROW) else emptyList()) +
                (if (estimatedFooter || footerY(anchor.x) == null || footerColumns.any { it == null } || selectedParts.isEmpty()) listOf(ScheduleIssue.ROW_AMBIGUOUS) else emptyList()) +
                (if ((assigned + anchor).any { it.confidence?.let { c -> c < .65f } == true }) listOf(ScheduleIssue.LOW_CONFIDENCE) else emptyList())).distinct()
            ProvisionalDay(date, anchor.text, text, parsed.kind, parsed.intervals.map { it.copy(isExtra = false) },
                if (date == null || parsed.kind == DayKind.UNKNOWN) InterpretationStatus.INCOMPLETE else if (issues.isEmpty()) InterpretationStatus.READY else InterpretationStatus.NEEDS_REVIEW, issues, parsed.absence)
        }
        return ScheduleResult(days, global, candidates, chosen?.id, "weekly-report-v1")
    }
}
