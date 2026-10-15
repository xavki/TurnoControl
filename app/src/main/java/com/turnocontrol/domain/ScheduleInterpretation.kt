package com.turnocontrol.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.text.Normalizer
import java.util.Locale

enum class InterpretationStatus { READY, NEEDS_REVIEW, INCOMPLETE, CORRECTED }
enum class DayKind { WORK, FREE, UNKNOWN }
enum class ProvisionalAbsence { VACATION }
enum class ScheduleIssue(val message: String) {
    MISSING_DATE("Falta una fecha completa. Indica mes/año o corrige la fecha."),
    INVALID_DATE("La fecha de la cabecera no es válida."),
    WEEKDAY_MISMATCH("El día de la semana no coincide con la fecha."),
    DUPLICATE_DATE("Hay columnas con la misma fecha. Revisa sus cabeceras."),
    EMPTY_CELL("Celda vacía: no se supone que sea un día libre."),
    UNKNOWN_CELL("Texto no reconocido como turno o día libre; requiere corrección."),
    INVALID_TIME("Horas inválidas, incompletas, solapadas o de orden ambiguo."),
    OVERNIGHT("Salida después de medianoche: revisa los desplazamientos de día."),
    COLUMN_AMBIGUOUS("Texto entre columnas: no se asignan sus horas automáticamente."),
    COLUMN_SHIFT("La celda está desplazada respecto a su cabecera. Revisa la asignación."),
    LOW_CONFIDENCE("El OCR indica confianza baja. Revisa el texto original."),
    MISSING_GEOMETRY("Faltan coordenadas OCR; no se puede reconstruir la tabla con seguridad."),
    WORKER_NOT_FOUND("No hay una fila con el nombre o alias completo indicado. No se usan coincidencias aproximadas."),
    DUPLICATE_WORKER("Hay varias filas coincidentes. Confirma explícitamente una fila."),
    ROW_AMBIGUOUS("Las etiquetas de filas están muy próximas o partidas. Confirma la fila y revisa su contenido."),
    UNSUPPORTED_LAYOUT("No se han localizado cabeceras y filas de trabajadores con geometría suficiente."),
    CONTEXT_CONFLICT("Hay varios meses/años posibles. Indica el contexto de fecha explícitamente."),
    MANUAL_CORRECTION("Resultado corregido manualmente; permanece provisional."),
    VACATION_NOT_SAVED("VAC: vacaciones/ausencia provisional. No se guarda como libre ni como turno."),
    MANUAL_ROW("Fila elegida manualmente. Comprueba sus siete celdas con la fotografía."),
}

/** Offsets are calendar days relative to the header date, not payroll classifications. */
data class ProvisionalInterval(val start: LocalTime, val end: LocalTime, val startDayOffset: Int = 0, val endDayOffset: Int = 0, val isExtra: Boolean = false)
data class ProvisionalDay(
    val date: LocalDate?, val header: String, val sourceText: String,
    val kind: DayKind, val intervals: List<ProvisionalInterval>,
    val status: InterpretationStatus, val issues: List<ScheduleIssue>, val absence: ProvisionalAbsence? = null,
)
data class ManualRowBand(val top: Int, val bottom: Int)
data class WorkerSelection(val name: String, val alias: String = "", val confirmedRowId: Int? = null, val manualBand: ManualRowBand? = null)
data class WorkerRowCandidate(val id: Int, val label: String, val top: Int, val bottom: Int, val corners: List<OcrPoint> = emptyList())
data class ScheduleResult(
    val days: List<ProvisionalDay> = emptyList(),
    val issues: List<ScheduleIssue> = emptyList(),
    val candidates: List<WorkerRowCandidate> = emptyList(),
    val selectedRowId: Int? = null,
    val layout: String = "worker-rows-v1",
)

interface ScheduleInterpreter {
    fun interpret(document: OcrDocument, worker: WorkerSelection, month: YearMonth? = null): ScheduleResult
}

internal fun normalizedLabel(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

/** Layout whitespace around existing punctuation is normalized; letters, dots and codes stay exact. */
internal fun normalizedWorkerLabel(text: String): String = normalizedLabel(text)
    .replace(Regex("\\s*\\.\\s*"), ". ").replace(Regex("\\(\\s*"), "(")
    .replace(Regex("\\s*\\)"), ")").replace(Regex("\\s+"), " ").trim()

data class ParsedTimeCell(val kind: DayKind, val intervals: List<ProvisionalInterval>, val issues: List<ScheduleIssue>, val absence: ProvisionalAbsence? = null)

/** A strict textual parser shared by automatic interpretation and explicit manual corrections. */
object TimeCellParser {
    private val range = Regex("(?<![\\d:.])([0-2]?\\d)[:.]([0-5]\\d)\\s*(?:-|–|—|a)\\s*([0-2]?\\d)[:.]([0-5]\\d)(?![\\d:.])", RegexOption.IGNORE_CASE)
    fun parse(text: String): ParsedTimeCell {
        val raw = text.trim()
        if (raw.isEmpty()) return ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.EMPTY_CELL))
        if (normalizedLabel(raw) in setOf("LIBRE", "DESCANSO", "OFF")) return ParsedTimeCell(DayKind.FREE, emptyList(), emptyList())
        val matches = range.findAll(raw).toList()
        if (matches.isEmpty()) return ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.UNKNOWN_CELL))
        if (range.replace(raw, "").replace(Regex("[\\s,;/+]+"), "").isNotEmpty()) {
            return ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.INVALID_TIME))
        }
        val intervals = mutableListOf<ProvisionalInterval>()
        for (match in matches) {
            val (sh, sm, eh, em) = match.destructured.toList().map(String::toInt)
            if (sh > 23 || eh > 24 || (eh == 24 && em != 0)) return invalid()
            val start = LocalTime.of(sh, sm)
            val end = LocalTime.of(eh % 24, em)
            val offset = intervals.lastOrNull()?.endDayOffset ?: 0
            val previousEnd = intervals.lastOrNull()?.end
            if (previousEnd != null && start < previousEnd) return invalid()
            if (start == end && eh != 24) return invalid()
            val endOffset = offset + if (eh == 24 || end < start) 1 else 0
            // A cell spanning several midnights is ambiguous; it is not silently extended.
            if (endOffset > 1) return invalid()
            intervals += ProvisionalInterval(start, end, offset, endOffset)
        }
        return ParsedTimeCell(DayKind.WORK, intervals, if (intervals.any { it.endDayOffset > 0 }) listOf(ScheduleIssue.OVERNIGHT) else emptyList())
    }
    private fun invalid() = ParsedTimeCell(DayKind.UNKNOWN, emptyList(), listOf(ScheduleIssue.INVALID_TIME))
}
