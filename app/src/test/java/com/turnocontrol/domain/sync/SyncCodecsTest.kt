package com.turnocontrol.domain.sync

import com.turnocontrol.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class SyncCodecsTest {
    private val date = LocalDate.parse("2026-12-31")
    private val key = SyncCodecs.dayKey(date)

    @Test fun dayRoundTripIsCanonicalAndOrderIndependent() {
        val late = ProvisionalInterval(LocalTime.of(22, 0), LocalTime.of(2, 0), 0, 1, isExtra = true)
        val early = ProvisionalInterval(LocalTime.of(8, 0), LocalTime.of(12, 0), 0, 0)
        val a = SyncCodecs.encodeDay(DayRecord(PlannedDay(date, DayKind.WORK, listOf(late, early)), "OCR_REVIEWED")) as LocalRecord.Shared
        val b = SyncCodecs.encodeDay(DayRecord(PlannedDay(date, DayKind.WORK, listOf(early, late)), "OCR_REVIEWED")) as LocalRecord.Shared
        assertEquals(a, b)
        assertEquals("1|2026-12-31|WORK|OCR_REVIEWED|08:00+0-12:00+0:N,22:00+0-02:00+1:E", a.payload.text)
        val decoded = SyncCodecs.decodeDay(key, a.payload)
        assertEquals(listOf(early, late), decoded.day.intervals)
        assertEquals("OCR_REVIEWED", decoded.origin)
    }

    @Test fun onlySickLeaveIsLocalOnlyAndUnknownIsNeverStored() {
        SyncCodecs.shareableDayKinds.forEach { kind ->
            val intervals = if (kind == DayKind.WORK) listOf(ProvisionalInterval(LocalTime.of(8, 0), LocalTime.of(9, 0), 0, 0)) else emptyList()
            assertTrue(SyncCodecs.encodeDay(DayRecord(PlannedDay(date, kind, intervals))) is LocalRecord.Shared)
        }
        val sick = SyncCodecs.encodeDay(DayRecord(PlannedDay(date, DayKind.SICK_LEAVE, emptyList())))
        assertTrue(sick is LocalRecord.LocalOnly)
        assertEquals(DayKind.SICK_LEAVE, SyncCodecs.localDay(key, sick).day.kind)
        assertThrows(IllegalArgumentException::class.java) { SyncCodecs.encodeDay(DayRecord(PlannedDay(date, DayKind.UNKNOWN, emptyList()))) }
    }

    @Test fun nonCanonicalMismatchedOrMalformedPayloadsAreRefused() {
        fun refused(text: String, k: RecordKey = key) = SyncCodecs.validationError(k, SyncPayload(SyncEntity.SCHEDULED_DAY, text)) != null
        assertTrue(refused("1|2026-12-31|WORK|MANUAL|8:00+0-12:00+0:N"))
        assertTrue(refused("1|2026-12-31|WORK|MANUAL|12:00+0-13:00+0:N,08:00+0-09:00+0:N")) // Unsorted.
        assertTrue(refused("1|2026-12-31|LIBRE|MANUAL|"))
        assertTrue(refused("1|2026-12-31|FREE|OCR|"))
        assertTrue(refused("1|2026-12-31|FREE|MANUAL|", SyncCodecs.dayKey(date.plusDays(1))))
        assertTrue(refused("1|2026-12-31|WORK|MANUAL|25:00+0-26:00+0:N"))
        assertFalse(refused("1|2026-12-31|FREE|MANUAL|"))
        assertNotNull(SyncCodecs.validationError(key, SyncPayload(SyncEntity.SCHEDULED_DAY, "1|2026-12-31|FREE|MANUAL|"), dataFormat = 2))
    }

    @Test fun ratesAndPayslipsUseNaturalKeysAndStrictAmounts() {
        val rate = SyncCodecs.encodeRate(RateRecord("NIGHT", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30"), BigDecimal("1.2500")))
        assertEquals("1|NIGHT|2026-01-01|2026-06-30|1.25|EUR", rate.payload.text)
        assertEquals(BigDecimal("1.25"), SyncCodecs.decodeRate(SyncCodecs.rateKey("NIGHT", LocalDate.parse("2026-01-01")), rate.payload).amount)
        assertThrows(IllegalArgumentException::class.java) { SyncCodecs.encodeRate(RateRecord("BONUS", LocalDate.parse("2026-01-01"), null, BigDecimal.ONE)) }
        assertThrows(IllegalArgumentException::class.java) { SyncCodecs.encodeRate(RateRecord("NIGHT", LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01"), BigDecimal.ONE)) }
        val slip = SyncCodecs.encodePayslip(PayslipRecord(YearMonth.of(2026, 2), null, BigDecimal("1234.50")))
        assertEquals("1|2026-02|-|1234.5", slip.payload.text)
        assertThrows(IllegalArgumentException::class.java) { SyncCodecs.encodePayslip(PayslipRecord(YearMonth.of(2026, 2), null, null)) }
    }

    @Test fun overlappingRatePeriodsAreInvalidAcrossRecords() {
        val open = SyncCodecs.encodeRate(RateRecord("HOURLY_BASE", LocalDate.parse("2026-01-01"), null, BigDecimal.TEN))
        val later = SyncCodecs.encodeRate(RateRecord("HOURLY_BASE", LocalDate.parse("2026-04-01"), null, BigDecimal("11")))
        val closed = SyncCodecs.encodeRate(RateRecord("HOURLY_BASE", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"), BigDecimal.TEN))
        val k1 = SyncCodecs.rateKey("HOURLY_BASE", LocalDate.parse("2026-01-01")); val k2 = SyncCodecs.rateKey("HOURLY_BASE", LocalDate.parse("2026-04-01"))
        assertNotNull(SyncRules.validate(mapOf(k1 to open, k2 to later)))
        assertNull(SyncRules.validate(mapOf(k1 to closed, k2 to later)))
    }
}
