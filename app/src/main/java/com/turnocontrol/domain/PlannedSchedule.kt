package com.turnocontrol.domain

import java.time.LocalDate

/** Confirmed planning, deliberately unrelated to actual attendance or payroll. */
data class PlannedDay(val date: LocalDate, val kind: DayKind, val intervals: List<ProvisionalInterval>)

fun ProvisionalInterval.durationMinutes() = PlannedScheduleValidator.end(this) - PlannedScheduleValidator.start(this)
fun PlannedDay.totalMinutes() = intervals.sumOf { it.durationMinutes() }
fun PlannedDay.extraMinutes() = intervals.filter { it.isExtra }.sumOf { it.durationMinutes() }
fun PlannedDay.breakMinutes(): Int = intervals.sortedBy(PlannedScheduleValidator::start).zipWithNext().sumOf { (a, b) -> (PlannedScheduleValidator.start(b) - PlannedScheduleValidator.end(a)).coerceAtLeast(0) }
fun minutesLabel(minutes: Int) = "${minutes / 60} h ${minutes % 60} min"

object PlannedScheduleValidator {
    const val MAX_DAY_MINUTES = 24 * 60
    fun start(interval: ProvisionalInterval) = interval.startDayOffset * 1440 + interval.start.hour * 60 + interval.start.minute
    fun end(interval: ProvisionalInterval) = interval.endDayOffset * 1440 + interval.end.hour * 60 + interval.end.minute
    fun validate(days: List<PlannedDay>): String? {
        if (days.map { it.date }.distinct().size != days.size) return "Hay fechas duplicadas. Revisa los días antes de guardar."
        for (day in days) {
            if (day.kind == DayKind.UNKNOWN || (day.kind == DayKind.FREE && day.intervals.isNotEmpty()) || (day.kind == DayKind.WORK && day.intervals.isEmpty())) return "Un día debe ser libre explícito o contener intervalos válidos."
            val ordered = day.intervals.sortedBy(::start)
            if (ordered.isNotEmpty() && ordered.first().startDayOffset != 0) return "El turno pertenece al día en que empieza."
            if (ordered.any { it.start.second != 0 || it.end.second != 0 || it.start.nano != 0 || it.end.nano != 0 || it.startDayOffset !in 0..1 || it.endDayOffset !in 0..1 || end(it) <= start(it) || end(it) - start(it) > MAX_DAY_MINUTES }) return "Duración inválida: cada intervalo debe durar entre 1 minuto y 24 horas."
            if (ordered.sumOf { end(it) - start(it) } > MAX_DAY_MINUTES) return "Los intervalos de un día de inicio no pueden sumar más de 24 horas."
            if (ordered.zipWithNext().any { (a, b) -> end(a) > start(b) }) return "Hay intervalos solapados o duplicados."
        }
        val spans = days.flatMap { day -> day.intervals.map { Triple(day.date.toEpochDay() * 1440 + start(it), day.date.toEpochDay() * 1440 + end(it), day.date) } }.sortedBy { it.first }
        if (spans.zipWithNext().any { (a, b) -> a.second > b.first }) return "Hay turnos solapados entre días, incluida una continuación nocturna."
        // FREE has no starts assigned to this date. A previous day's continuation is shown separately.
        return null
    }
}
