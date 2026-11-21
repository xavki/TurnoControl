package com.turnocontrol.domain

/**
 * Values this version can persist and interpret. Older versions do not know every value (0.1.0 lacks VACATION,
 * SICK_LEAVE and EXTRA_HOUR), and future versions may add more: unknown stored values are never coerced.
 */
object DataFormat {
    /** Data format of the stored calendar: 1 = 0.1.0 (WORK/FREE), 2 = 0.2.0 (adds VACATION, SICK_LEAVE, EXTRA_HOUR). */
    const val CURRENT = 2
    /** SQLite `application_id` marker: "TC" in the high bytes, data format in the low bytes. Room does not use it. */
    const val APPLICATION_ID_MAGIC = 0x54430000
    const val ROOM_SCHEMA_VERSION = 4
    fun applicationId(format: Int = CURRENT) = APPLICATION_ID_MAGIC or format

    val persistedDayKinds: Set<DayKind> = setOf(DayKind.WORK, DayKind.FREE, DayKind.VACATION, DayKind.SICK_LEAVE)
    val knownRateTypes: Set<String> get() = setOf("HOURLY_BASE") + rateLabels.keys

    /** Null for anything this version cannot interpret; callers must reject it, not replace it. */
    fun dayKind(raw: String): DayKind? = persistedDayKinds.firstOrNull { it.name == raw }
}

/** Stored data written by a newer or incompatible version. The original rows are left untouched. */
class UnsupportedDataException(message: String) : IllegalStateException(message)

sealed interface CompatibilityReport {
    data object Compatible : CompatibilityReport
    data class Incompatible(val reasons: List<String>) : CompatibilityReport
}
