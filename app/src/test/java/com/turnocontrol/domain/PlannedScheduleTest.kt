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
}
