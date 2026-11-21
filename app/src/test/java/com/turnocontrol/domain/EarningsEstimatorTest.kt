package com.turnocontrol.domain

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class EarningsEstimatorTest {
    @Test fun missingBaseRateIsUnavailable() {
        val estimate = EarningsEstimator.estimate(PeriodSummary(totalMinutes = 480), null, null)
        assertNull(estimate.regular)
        assertNull(estimate.total)
        assertEquals(listOf(EarningsEstimator.MISSING_BASE), estimate.missing)
    }

    @Test fun missingExtraRateLeavesExtraAndTotalUnavailable() {
        val estimate = EarningsEstimator.estimate(PeriodSummary(totalMinutes = 480, extraMinutes = 60), BigDecimal("10"), null)
        assertEquals(BigDecimal("70.00"), estimate.regular)
        assertNull(estimate.extra)
        assertNull(estimate.total)
        assertEquals(listOf(EarningsEstimator.MISSING_EXTRA), estimate.missing)
    }

    @Test fun noExtrasNeedNoExtraRate() {
        val estimate = EarningsEstimator.estimate(PeriodSummary(totalMinutes = 450), BigDecimal("10.1234"), null)
        assertEquals(BigDecimal("75.93"), estimate.regular)
        assertEquals(BigDecimal("0.00"), estimate.extra)
        assertEquals(BigDecimal("75.93"), estimate.total)
        assertTrue(estimate.missing.isEmpty())
    }

    @Test fun roundsHalfUpToCents() {
        assertEquals(BigDecimal("0.01"), EarningsEstimator.amount(1, BigDecimal("0.30")))
    }

    @Test fun fullEstimateAddsBothParts() {
        val estimate = EarningsEstimator.estimate(PeriodSummary(totalMinutes = 480, extraMinutes = 120), BigDecimal("10"), BigDecimal("15"))
        assertEquals(BigDecimal("60.00"), estimate.regular)
        assertEquals(BigDecimal("30.00"), estimate.extra)
        assertEquals(BigDecimal("90.00"), estimate.total)
    }
}
