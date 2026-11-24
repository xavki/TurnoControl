package com.turnocontrol.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class ScheduleSummaryTest {
    private fun day(date: String, text: String, extraIndexes: Set<Int> = emptySet()) =
        PlannedDay(LocalDate.parse(date), DayKind.WORK, TimeCellParser.parse(text).intervals.mapIndexed { i, interval -> interval.copy(isExtra = i in extraIndexes) })
    private fun absence(date: String, kind: DayKind) = PlannedDay(LocalDate.parse(date), kind, emptyList())
    private val march = listOf(
        day("2026-03-02", "08:00-16:00"),
        day("2026-03-03", "08:00-12:00 / 16:00-20:00", setOf(1)),
        day("2026-03-04", "09:00-13:00"),
        absence("2026-03-05", DayKind.FREE),
        absence("2026-03-06", DayKind.VACATION),
        absence("2026-03-07", DayKind.VACATION),
        absence("2026-03-09", DayKind.SICK_LEAVE),
    )

    @Test fun monthSeparatesCategoriesAndHours() {
        val summary = ScheduleSummary.month(march, YearMonth.of(2026, 3))
        assertEquals(3, summary.workDays)
        assertEquals(1, summary.freeDays)
        assertEquals(2, summary.vacationDays)
        assertEquals(1, summary.sickDays)
        assertEquals(480 + 480 + 240, summary.totalMinutes)
        assertEquals(240, summary.extraMinutes)
        assertEquals(summary.totalMinutes - 240, summary.regularMinutes)
    }

    @Test fun monthWithoutDataIsZero() {
        assertEquals(PeriodSummary(), ScheduleSummary.month(march, YearMonth.of(2026, 4)))
    }

    @Test fun yearBoundaryShiftBelongsToStartYear() {
        val days = listOf(day("2026-12-31", "22:00-24:00 / 00:00-02:00", setOf(1)))
        assertEquals(240, ScheduleSummary.year(days, 2026).totalMinutes)
        assertEquals(120, ScheduleSummary.year(days, 2026).extraMinutes)
        assertEquals(PeriodSummary(), ScheduleSummary.year(days, 2027))
        assertEquals(PeriodSummary(), ScheduleSummary.month(days, YearMonth.of(2027, 1)))
    }

    @Test fun yearIsCalendarYearNotTrailingTwelveMonths() {
        val days = listOf(day("2025-12-15", "08:00-10:00", setOf(0)), day("2026-01-15", "08:00-11:00", setOf(0)))
        assertEquals(180, ScheduleSummary.year(days, 2026).extraMinutes)
        assertEquals(120, ScheduleSummary.year(days, 2025).extraMinutes)
    }

    @Test fun extrasAreNotDoubleCounted() {
        val summary = ScheduleSummary.month(listOf(day("2026-03-03", "08:00-12:00 / 16:00-20:00", setOf(1))), YearMonth.of(2026, 3))
        assertEquals(480, summary.totalMinutes)
        assertEquals(240, summary.extraMinutes)
        assertEquals(240, summary.regularMinutes)
    }

    @Test fun annualOvertimeAccumulatesEveryMonthOfTheCalendarYear() {
        val days = listOf(
            day("2026-01-10", "08:00-13:00", setOf(0)),                    // 5 h
            day("2026-02-10", "08:00-16:00", setOf(0)),                    // 8 h
            day("2026-03-10", "08:00-14:00 / 15:00-21:00", setOf(0, 1)),   // 12 h
            day("2025-12-31", "22:00-24:00 / 00:00-03:00", setOf(0, 1)),   // 5 h, previous year
        )
        assertEquals(5 * 60, ScheduleSummary.month(days, YearMonth.of(2026, 1)).extraMinutes)
        assertEquals(8 * 60, ScheduleSummary.month(days, YearMonth.of(2026, 2)).extraMinutes)
        assertEquals(12 * 60, ScheduleSummary.month(days, YearMonth.of(2026, 3)).extraMinutes)
        assertEquals(25 * 60, ScheduleSummary.year(days, 2026).extraMinutes)
        assertEquals(5 * 60, ScheduleSummary.year(days, 2025).extraMinutes)
        assertEquals(0, ScheduleSummary.year(days, 2027).extraMinutes)
        assertEquals("25 h 0 min", minutesLabel(ScheduleSummary.year(days, 2026).extraMinutes))
    }

    @Test fun annualOvertimeKeepsMinutePrecision() {
        val days = listOf(day("2026-05-01", "08:00-09:45", setOf(0)), day("2026-11-30", "23:30-24:00 / 00:00-00:45", setOf(0, 1)))
        assertEquals(105 + 75, ScheduleSummary.year(days, 2026).extraMinutes)
        assertEquals("3 h 0 min", minutesLabel(ScheduleSummary.year(days, 2026).extraMinutes))
    }

    @Test fun monthlyTotalsHaveTwelveEntries() {
        val totals = ScheduleSummary.monthlyTotalMinutes(march, 2026)
        assertEquals(12, totals.size)
        assertEquals(1200, totals[2])
        assertEquals(0, totals.filterIndexed { i, _ -> i != 2 }.sum())
    }

    @Test fun monthlyExtrasHistoryAddsUpToTheYearAndKeepsOvernightShiftsInTheirStartMonth() {
        val days = listOf(
            day("2026-01-31", "22:00-02:00", setOf(0)),         // 4 h, January, although it ends on 1 February.
            day("2026-03-10", "08:00-12:00 / 16:00-17:07", setOf(1)), // 1 h 7 min of a split shift.
            day("2025-12-31", "22:00-06:00", setOf(0)),          // Previous year: never in 2026.
            absence("2026-04-01", DayKind.SICK_LEAVE),
        )
        val extras = ScheduleSummary.monthlyExtraMinutes(days, 2026)
        assertEquals(12, extras.size)
        assertEquals(240, extras[0])
        assertEquals(0, extras[1])
        assertEquals(67, extras[2])
        assertEquals(ScheduleSummary.year(days, 2026).extraMinutes, extras.sum())
        assertEquals(480, ScheduleSummary.monthlyExtraMinutes(days, 2025)[11])
    }
}
