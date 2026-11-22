package com.turnocontrol.data

import androidx.room.withTransaction
import com.turnocontrol.data.accounts.OperationGate
import com.turnocontrol.domain.*
import com.turnocontrol.domain.sync.DayRecord
import com.turnocontrol.domain.sync.LocalRecord
import com.turnocontrol.domain.sync.RecordKey
import com.turnocontrol.domain.sync.SyncCodecs
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

fun StoredScheduleDay.planned() = PlannedDay(LocalDate.parse(day.date),
    DataFormat.dayKind(day.kind) ?: throw UnsupportedDataException("Tipo de día desconocido «${day.kind}» el ${day.date}."), intervals.sortedBy { it.position }.map {
    ProvisionalInterval(LocalTime.of(it.startMinute / 60, it.startMinute % 60), LocalTime.of(it.endMinute / 60, it.endMinute % 60), it.startDayOffset, it.endDayOffset, it.isExtra)
})

class ScheduleChangedException : IllegalStateException("El calendario cambió desde que lo abriste. Vuelve a cargar y revisar; no se ha guardado nada.")

/** One instance per storage session (see StorageScope). */
class ScheduleRepository(private val db: TurnoDatabase, private val gate: OperationGate) {
    constructor(db: TurnoDatabase) : this(db, OperationGate())
    val days = db.schedule().observe()
    suspend fun snapshot() = db.schedule().all().canonical()
    private fun List<StoredScheduleDay>.canonical() = sortedBy { it.day.date }.map { it.copy(intervals = it.intervals.sortedBy { i -> i.position }) }

    /**
     * Entire batch succeeds or fails. The snapshot check covers the changed dates and their neighbours (an interval
     * lasts at most until the next day, so only adjacent days can interact); unrelated days written meanwhile, e.g.
     * by synchronization, do not reject the edit. The resulting calendar is still validated in full. If the database
     * is linked to an account, the outgoing changes are recorded in this same transaction.
     */
    suspend fun save(expected: List<StoredScheduleDay>, replacements: List<PlannedDay>, deletions: Set<LocalDate> = emptySet(), origin: String = "MANUAL") =
        gate.operation("calendar-save") { saveLocked(expected, replacements, deletions, origin) }

    private suspend fun saveLocked(expected: List<StoredScheduleDay>, replacements: List<PlannedDay>, deletions: Set<LocalDate>, origin: String) {
        require(origin in setOf("MANUAL", "OCR_REVIEWED"))
        require(replacements.map { it.date }.toSet().intersect(deletions).isEmpty())
        PlannedScheduleValidator.validate(replacements)?.let { throw IllegalArgumentException(it) }
        db.withTransaction {
            val dao = db.schedule()
            val current = dao.all().canonical()
            val changed = replacements.map { it.date }.toSet() + deletions
            val window = changed.flatMap { listOf(it.minusDays(1), it, it.plusDays(1)) }.map { it.toString() }.toSet()
            fun List<StoredScheduleDay>.affected() = filter { it.day.date in window }
            if (current.affected() != expected.canonical().affected()) throw ScheduleChangedException()
            val finalDays = current.filter { LocalDate.parse(it.day.date) !in changed }.map { it.planned() } + replacements
            PlannedScheduleValidator.validate(finalDays)?.let { throw IllegalArgumentException(it) }
            if (replacements.isNotEmpty() && dao.profile() == null) {
                // Calendar has no dependency on a contract, rate or legal rule.
                dao.insertProfile(Profile(displayName = "Mi calendario", alias = "", zoneId = ZoneId.systemDefault().id))
            }
            val zone = dao.profile()?.zoneId ?: ZoneId.systemDefault().id
            val outgoing = mutableMapOf<RecordKey, LocalRecord?>()
            current.filter { LocalDate.parse(it.day.date) in deletions }.forEach {
                dao.deleteDay(it.day.id)
                outgoing[SyncCodecs.dayKey(LocalDate.parse(it.day.date))] = null
            }
            replacements.forEach { proposed ->
                val old = current.firstOrNull { it.day.date == proposed.date.toString() }
                if (old?.planned() != proposed) {
                    outgoing[SyncCodecs.dayKey(proposed.date)] = SyncCodecs.encodeDay(DayRecord(proposed, origin))
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
            SyncCapture.record(db, outgoing)
        }
    }
}
