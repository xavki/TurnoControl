package com.turnocontrol.domain

import kotlin.math.abs

/** NEW is added, UNCHANGED is already stored identically (never duplicated), CONFLICT needs an explicit decision. */
enum class ImportStatus { NEW, UNCHANGED, CONFLICT }
data class ImportProposal(val planned: PlannedDay, val existing: PlannedDay?, val replace: Boolean? = null) {
    val status: ImportStatus get() = when { existing == null -> ImportStatus.NEW; existing == planned -> ImportStatus.UNCHANGED; else -> ImportStatus.CONFLICT }
    val writes: Boolean get() = status == ImportStatus.NEW || (status == ImportStatus.CONFLICT && replace == true)
}
data class ImportSummary(val days: Int, val shifts: Int, val added: Int, val replaced: Int, val unchanged: Int, val kept: Int, val unresolved: Int)

fun PlannedDay.rangeText() = when (kind) { DayKind.FREE -> "LIBRE"; DayKind.VACATION -> "VACACIONES"; DayKind.SICK_LEAVE -> "BAJA MÉDICA"; else -> intervals.joinToString(" / ") { "${it.start}${if (it.startDayOffset == 1) " (+1 día)" else ""}–${it.end}${if (it.endDayOffset == 1) " (+1 día)" else ""}${if (it.isExtra) " · Extra declarada" else ""}" } }

/** Plain-language difference between a stored day and an imported one. */
fun importDifference(existing: PlannedDay, imported: PlannedDay): String = buildString {
    append(if (existing.kind != imported.kind) "Cambia de ${existing.kind.label.lowercase()} a ${imported.kind.label.lowercase()}."
        else "Cambia el horario: ${existing.rangeText()} → ${imported.rangeText()}.")
    if (existing.extraMinutes() > 0) append(" Sustituir eliminará las marcas de hora extra del día guardado.")
}

/**
 * Explicitly reviewed OCR days compared with one calendar snapshot ([stored]). Immutable and side-effect free:
 * it never writes and never decides a conflict for the user. The repository transaction re-checks the snapshot
 * and re-validates the resulting calendar on save, so this is advice for the screen, not the final authority.
 */
data class OcrImportReview(val proposals: List<ImportProposal>, val stored: List<PlannedDay>) {
    /** Days the save will write: new ones and conflicts the user chose to replace. */
    val writes: List<PlannedDay> get() = proposals.filter { it.writes }.map { it.planned }

    /** Only a conflict can be decided; any other index is ignored. */
    fun resolve(index: Int, replace: Boolean) = copy(proposals = proposals.mapIndexed { i, p -> if (i == index && p.status == ImportStatus.CONFLICT) p.copy(replace = replace) else p })

    fun summary(): ImportSummary {
        val writing = proposals.filter { it.writes }
        return ImportSummary(writing.size, writing.sumOf { it.planned.intervals.size }, writing.count { it.status == ImportStatus.NEW },
            writing.count { it.status == ImportStatus.CONFLICT }, proposals.count { it.status == ImportStatus.UNCHANGED },
            proposals.count { it.status == ImportStatus.CONFLICT && it.replace == false }, proposals.count { it.status == ImportStatus.CONFLICT && it.replace == null })
    }

    /** Why the import cannot be saved yet, or null. Uses the same validator as the repository. */
    fun blocker(): String? {
        val summary = summary()
        if (summary.unresolved > 0) return "Decide qué hacer con ${summary.unresolved} día(s) que ya tienen datos distintos: sustituir o conservar."
        val writes = writes
        if (writes.isEmpty()) return "No hay cambios que guardar: los días ya están en el calendario con los mismos datos o se conservan."
        val kept = stored.filter { day -> writes.none { it.date == day.date } }
        PlannedScheduleValidator.validate(kept + writes) ?: return null
        val clashes = writes.filter { day -> PlannedScheduleValidator.validate(kept.filter { abs(it.date.toEpochDay() - day.date.toEpochDay()) <= 1 } + day) != null }
        return "Hay turnos solapados con días guardados contiguos (turno nocturno): ${clashes.joinToString { it.date.toString() }.ifBlank { "revisa los días importados" }}."
    }

    companion object {
        fun classify(days: List<PlannedDay>, stored: List<PlannedDay>) =
            OcrImportReview(days.map { day -> ImportProposal(day, stored.firstOrNull { it.date == day.date }) }, stored)
    }
}
