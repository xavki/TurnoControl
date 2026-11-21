package com.turnocontrol.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** Totals for a period. Days are assigned by their start date; extras are a subset of total. */
data class PeriodSummary(
    val workDays: Int = 0, val freeDays: Int = 0, val vacationDays: Int = 0, val sickDays: Int = 0,
    val totalMinutes: Int = 0, val extraMinutes: Int = 0,
) {
    val regularMinutes: Int get() = totalMinutes - extraMinutes
}

object ScheduleSummary {
    fun month(days: List<PlannedDay>, month: YearMonth) = of(days) { YearMonth.from(it) == month }
    fun year(days: List<PlannedDay>, year: Int) = of(days) { it.year == year }
    fun monthlyTotalMinutes(days: List<PlannedDay>, year: Int): List<Int> = (1..12).map { month(days, YearMonth.of(year, it)).totalMinutes }
    private fun of(days: List<PlannedDay>, inPeriod: (LocalDate) -> Boolean): PeriodSummary =
        days.filter { inPeriod(it.date) }.fold(PeriodSummary()) { acc, day ->
            when (day.kind) {
                DayKind.WORK -> acc.copy(workDays = acc.workDays + 1, totalMinutes = acc.totalMinutes + day.totalMinutes(), extraMinutes = acc.extraMinutes + day.extraMinutes())
                DayKind.FREE -> acc.copy(freeDays = acc.freeDays + 1)
                DayKind.VACATION -> acc.copy(vacationDays = acc.vacationDays + 1)
                DayKind.SICK_LEAVE -> acc.copy(sickDays = acc.sickDays + 1)
                DayKind.UNKNOWN -> acc
            }
        }
}

/** Gross estimate with the rates configured now; null means unavailable, never a guessed amount. */
data class EarningsEstimate(val regular: BigDecimal?, val extra: BigDecimal?, val missing: List<String>) {
    val total: BigDecimal? get() = if (regular != null && extra != null) regular + extra else null
}

object EarningsEstimator {
    const val MISSING_BASE = "Configura la tarifa base en Ajustes"
    const val MISSING_EXTRA = "Configura la tarifa de hora extra en Ajustes"
    fun amount(minutes: Int, rate: BigDecimal): BigDecimal = rate.multiply(BigDecimal(minutes)).divide(BigDecimal(60), 2, RoundingMode.HALF_UP)
    fun estimate(summary: PeriodSummary, baseRate: BigDecimal?, extraRate: BigDecimal?): EarningsEstimate {
        val regular = baseRate?.let { amount(summary.regularMinutes, it) }
        val extra = when {
            summary.extraMinutes == 0 -> BigDecimal.ZERO.setScale(2)
            extraRate != null -> amount(summary.extraMinutes, extraRate)
            else -> null
        }
        return EarningsEstimate(regular, extra, listOfNotNull(MISSING_BASE.takeIf { regular == null }, MISSING_EXTRA.takeIf { extra == null }))
    }
}
