package com.turnocontrol.domain.sync

import com.turnocontrol.domain.PlannedScheduleValidator

/**
 * Rules across records, checked on the whole local state after any local edit, incoming record or resolution.
 * Calendar rules are not duplicated: they are the existing [PlannedScheduleValidator] (overnight continuations,
 * overlaps, split shifts, 24 h limits), applied to shared and local-only days together.
 */
object SyncRules {
    fun validate(records: Map<RecordKey, LocalRecord>): String? {
        val days = try {
            records.filterKeys { it.entity == SyncEntity.SCHEDULED_DAY }.map { (key, record) -> SyncCodecs.localDay(key, record).day }
        } catch (e: SyncFormatException) { return e.message }
        PlannedScheduleValidator.validate(days)?.let { return it }
        val rates = records.filterKeys { it.entity == SyncEntity.PAY_RULE }.map { (key, record) ->
            val shared = record as? LocalRecord.Shared ?: return "Una tarifa no puede ser solo local."
            try { SyncCodecs.decodeRate(key, shared.payload) } catch (e: SyncFormatException) { return e.message }
        }
        rates.groupBy { it.type }.forEach { (type, periods) ->
            periods.sortedBy { it.validFrom }.zipWithNext().firstOrNull { (a, b) -> a.validUntil == null || !a.validUntil.isBefore(b.validFrom) }?.let { (a, b) ->
                return "Hay vigencias solapadas de la tarifa $type (${a.validFrom} y ${b.validFrom})."
            }
        }
        return null
    }
}
