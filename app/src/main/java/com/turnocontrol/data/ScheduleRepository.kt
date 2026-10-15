package com.turnocontrol.data

import androidx.room.withTransaction
import com.turnocontrol.domain.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

fun StoredScheduleDay.planned() = PlannedDay(LocalDate.parse(day.date), DayKind.valueOf(day.kind), intervals.sortedBy { it.position }.map {
    ProvisionalInterval(LocalTime.of(it.startMinute / 60, it.startMinute % 60), LocalTime.of(it.endMinute / 60, it.endMinute % 60), it.startDayOffset, it.endDayOffset, it.isExtra)
})

class ScheduleChangedException : IllegalStateException("El calendario cambió desde que lo abriste. Vuelve a cargar y revisar; no se ha guardado nada.")

@Singleton
class ScheduleRepository @Inject constructor(private val db: TurnoDatabase) {
    val days = db.schedule().observe()
    suspend fun snapshot() = db.schedule().all().canonical()
    private fun List<StoredScheduleDay>.canonical() = sortedBy { it.day.date }.map { it.copy(intervals = it.intervals.sortedBy { i -> i.position }) }

    /** Expected snapshot covers adjacent overnight shifts too. Entire batch succeeds or fails. */
    suspend fun save(expected: List<StoredScheduleDay>, replacements: List<PlannedDay>, deletions: Set<LocalDate> = emptySet(), origin: String = "MANUAL") {
        require(origin in setOf("MANUAL", "OCR_REVIEWED"))
        require(replacements.map { it.date }.toSet().intersect(deletions).isEmpty())
        PlannedScheduleValidator.validate(replacements)?.let { throw IllegalArgumentException(it) }
        db.withTransaction {
            val dao = db.schedule()
            val current = dao.all().canonical()
            if (current != expected.canonical()) throw ScheduleChangedException()
            val changed = replacements.map { it.date }.toSet() + deletions
            val finalDays = current.filter { LocalDate.parse(it.day.date) !in changed }.map { it.planned() } + replacements
            PlannedScheduleValidator.validate(finalDays)?.let { throw IllegalArgumentException(it) }
            if (replacements.isNotEmpty() && dao.profile() == null) {
                // Calendar has no dependency on a contract, rate or legal rule.
                dao.insertProfile(Profile(displayName = "Mi calendario", alias = "", zoneId = ZoneId.systemDefault().id))
            }
            val zone = dao.profile()?.zoneId ?: ZoneId.systemDefault().id
            current.filter { LocalDate.parse(it.day.date) in deletions }.forEach { dao.deleteDay(it.day.id) }
            replacements.forEach { proposed ->
                val old = current.firstOrNull { it.day.date == proposed.date.toString() }
                if (old?.planned() != proposed) {
                    val row = ScheduledDay(id = old?.day?.id ?: 0, date = proposed.date.toString(), kind = proposed.kind.name,
                        zoneId = old?.day?.zoneId ?: zone, origin = origin, revision = (old?.day?.revision ?: 0) + 1)
                    val id = if (old == null) dao.insertDay(row) else { dao.updateDay(row); row.id }
                    dao.clearIntervals(id)
                    dao.insertIntervals(proposed.intervals.mapIndexed { index, interval ->
                        ScheduledInterval(dayId = id, position = index, startMinute = interval.start.hour * 60 + interval.start.minute,
                            endMinute = interval.end.hour * 60 + interval.end.minute, startDayOffset = interval.startDayOffset, endDayOffset = interval.endDayOffset, isExtra = interval.isExtra)
                    })
                }
            }
        }
    }
}
