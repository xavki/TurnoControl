package com.turnocontrol.domain

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class TimeCellParserTest {
    @Test fun acceptsExplicitFreeLabelsButNotUnconfirmedCodes() {
        listOf("LIBRE", "descanso", "OFF").forEach { assertEquals(DayKind.FREE, TimeCellParser.parse(it).kind) }
        listOf("L", "D", "F", "VAC", "--", "").forEach { assertEquals(DayKind.UNKNOWN, TimeCellParser.parse(it).kind) }
    }
    @Test fun simpleAndSplitRangesKeepExactMinuteValues() {
        val parsed = TimeCellParser.parse("8:15–12:30 / 16.45 a 20.10")
        assertEquals(2, parsed.intervals.size)
        assertEquals(LocalTime.of(16,45), parsed.intervals[1].start)
        assertEquals(LocalTime.of(20,10), parsed.intervals[1].end)
        assertTrue(parsed.issues.isEmpty())
    }
    @Test fun midnightOffsetsAreExplicitAndSplitContinuationIsChronological() {
        val parsed = TimeCellParser.parse("22:00-02:00 / 03:00-05:00")
        assertEquals(listOf(0,1), parsed.intervals.map { it.startDayOffset })
        assertEquals(listOf(1,1), parsed.intervals.map { it.endDayOffset })
        assertTrue(ScheduleIssue.OVERNIGHT in parsed.issues)
    }
    @Test fun explicit24HourEndpointMeansMidnightButEqualTimesAreAmbiguous() {
        assertEquals(1, TimeCellParser.parse("18:00-24:00").intervals.single().endDayOffset)
        assertEquals(LocalTime.MIDNIGHT, TimeCellParser.parse("18:00-24:00").intervals.single().end)
        assertEquals(DayKind.UNKNOWN, TimeCellParser.parse("08:00-08:00").kind)
    }
    @Test fun invalidOrOverlappingRangesAreNeverAutomaticallyRepaired() {
        listOf("25:00-26:00", "08:60-16:00", "24:00-01:00", "18:00-24:30", "08:00-12:00 / 10:00-14:00", "08:00 16:00", "O8:00-16:00", "08:00-12:00 LIBRE").forEach { text ->
            assertEquals(text, DayKind.UNKNOWN, TimeCellParser.parse(text).kind)
            assertTrue(text, TimeCellParser.parse(text).intervals.isEmpty())
        }
    }
}
