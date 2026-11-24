package com.turnocontrol.domain

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class PlannedScheduleTest {
    private val date = LocalDate.parse("2026-01-31")
    private fun work(text: String, d: LocalDate = date) = PlannedDay(d, DayKind.WORK, TimeCellParser.parse(text).intervals)
    @Test fun splitAndOvernightBelongToStartDay() {
        assertNull(PlannedScheduleValidator.validate(listOf(work("08:00-12:00 / 16:00-20:00"))))
        val overnight = work("22:00-02:00 / 03:00-05:00")
        assertEquals(1, overnight.intervals[1].startDayOffset)
        assertNull(PlannedScheduleValidator.validate(listOf(overnight)))
    }
    @Test fun duplicatesAndOverlapsAreRejectedButContinuationKeepsItsStartDay() {
        assertNotNull(PlannedScheduleValidator.validate(listOf(work("08:00-12:00"), work("08:00-12:00"))))
        assertNotNull(PlannedScheduleValidator.validate(listOf(work("22:00-02:00"), work("01:00-03:00", date.plusDays(1)))))
        assertNull(PlannedScheduleValidator.validate(listOf(work("22:00-02:00"), PlannedDay(date.plusDays(1), DayKind.FREE, emptyList()))))
        assertNull(PlannedScheduleValidator.validate(listOf(work("22:00-02:00"), work("02:00-03:00", date.plusDays(1)))))
    }
    @Test fun freeUnknownEmptyAndDurationLimitAreDistinct() {
        assertNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, DayKind.FREE, emptyList()))))
        assertNotNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, DayKind.UNKNOWN, emptyList()))))
        assertNotNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, DayKind.WORK, emptyList()))))
        val tooLong = ProvisionalInterval(LocalTime.of(8,0), LocalTime.of(9,0), 0, 1)
        assertNotNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, DayKind.WORK, listOf(tooLong)))))
        assertNull(PlannedScheduleValidator.validate(listOf(work("00:00-24:00"))))
        assertNotNull(PlannedScheduleValidator.validate(listOf(work("08:00-24:00 / 00:00-09:00"))))
    }
    @Test fun absenceKindsRequireNoIntervals() {
        for (kind in listOf(DayKind.VACATION, DayKind.SICK_LEAVE)) {
            assertNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, kind, emptyList()))))
            assertNotNull(PlannedScheduleValidator.validate(listOf(PlannedDay(date, kind, TimeCellParser.parse("08:00-12:00").intervals))))
        }
        assertEquals("Vacaciones", DayKind.VACATION.label)
        assertEquals("Baja médica", DayKind.SICK_LEAVE.label)
        assertEquals("Sin configurar", DayKind.UNKNOWN.label)
    }
    @Test fun overnightContinuationMayLandOnSickDay() {
        assertNull(PlannedScheduleValidator.validate(listOf(work("22:00-02:00"), PlannedDay(date.plusDays(1), DayKind.SICK_LEAVE, emptyList()))))
    }
    @Test fun regularMinutesAndBoundaryIntervals() {
        val split = work("08:00-12:00 / 16:00-20:00").let { it.copy(intervals = it.intervals.mapIndexed { index, interval -> interval.copy(isExtra = index == 1) }) }
        assertEquals(240, split.regularMinutes())
        assertEquals(LocalTime.of(8, 0), split.firstInterval()!!.start)
        assertEquals(LocalTime.of(20, 0), split.lastInterval()!!.end)
        assertNull(PlannedDay(date, DayKind.FREE, emptyList()).firstInterval())
    }
    @Test fun extrasAreSubsetOfPlanningAndBreaksAreExcluded() {
        val split = work("08:00-12:00 / 16:00-20:00").let { it.copy(intervals = it.intervals.mapIndexed { index, interval -> interval.copy(isExtra = index == 1) }) }
        assertEquals(480, split.totalMinutes())
        assertEquals(240, split.extraMinutes())
        assertEquals(240, split.breakMinutes())
        val night = work("18:00-24:00 / 00:00-01:00").let { it.copy(intervals = it.intervals.mapIndexed { index, interval -> interval.copy(isExtra = index == 1) }) }
        assertEquals(420, night.totalMinutes())
        assertEquals(60, night.extraMinutes())
        assertEquals(0, night.breakMinutes())
        assertNull(PlannedScheduleValidator.validate(listOf(night)))
        assertTrue(TimeCellParser.parse("18:00-24:00 / 00:00-01:00").intervals.none { it.isExtra })
    }

    @Test fun continuationIndexMatchesThePerCellRuleIncludingMidnightEdges() {
        fun at(h: Int, m: Int = 0) = java.time.LocalTime.of(h, m)
        val base = java.time.LocalDate.parse("2026-12-30")
        val days = listOf(
            PlannedDay(base, DayKind.WORK, listOf(ProvisionalInterval(at(22), at(6), 0, 1))),                  // Crosses into 31 Dec.
            PlannedDay(base.plusDays(1), DayKind.WORK, listOf(ProvisionalInterval(at(16), at(0), 0, 1))),       // Ends exactly at midnight.
            PlannedDay(base.plusDays(2), DayKind.WORK, listOf(ProvisionalInterval(at(18), at(22)), ProvisionalInterval(at(0, 30), at(4), 1, 1))), // Second part entirely next day.
            PlannedDay(base.plusDays(5), DayKind.WORK, listOf(ProvisionalInterval(at(8), at(8), 0, 1))),        // 24 h shift.
            PlannedDay(base.plusDays(3), DayKind.SICK_LEAVE, emptyList()),
        )
        fun oldRule(date: java.time.LocalDate) = days.any { day -> day.intervals.any { i ->
            val offset = java.time.temporal.ChronoUnit.DAYS.between(day.date, date).toInt()
            offset > 0 && PlannedScheduleValidator.start(i) < (offset + 1) * 1440 && PlannedScheduleValidator.end(i) > offset * 1440
        } }
        val index = continuationDates(days)
        (-2L..10L).map { base.plusDays(it) }.forEach { assertEquals("$it", oldRule(it), it in index) }
        assertEquals(setOf(base.plusDays(1), base.plusDays(3), base.plusDays(6)), index)
    }
}
