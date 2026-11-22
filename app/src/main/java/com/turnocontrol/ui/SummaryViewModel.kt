package com.turnocontrol.ui

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.data.StoredScheduleDay
import com.turnocontrol.data.planned
import com.turnocontrol.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.math.BigDecimal
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SummaryState(
    val month: YearMonth = YearMonth.now(), val days: List<PlannedDay> = emptyList(),
    val rates: Map<String, String> = emptyMap(), val loading: Boolean = true,
) {
    val year: Int get() = month.year
    val monthSummary: PeriodSummary get() = ScheduleSummary.month(days, month)
    val yearSummary: PeriodSummary get() = ScheduleSummary.year(days, year)
    val monthlyTotals: List<Int> get() = ScheduleSummary.monthlyTotalMinutes(days, year)
    val baseRate: BigDecimal? get() = rate("HOURLY_BASE")
    val extraRate: BigDecimal? get() = rate("EXTRA_HOUR")
    val plusRates: Map<String, String> get() = rates.filterKeys { it in rateLabels && it != "EXTRA_HOUR" }
    val monthEarnings: EarningsEstimate get() = EarningsEstimator.estimate(monthSummary, baseRate, extraRate)
    val yearEarnings: EarningsEstimate get() = EarningsEstimator.estimate(yearSummary, baseRate, extraRate)
    private fun rate(type: String) = rates[type]?.let { runCatching { decimal(it) }.getOrNull() }
}

@HiltViewModel
class SummaryViewModel internal constructor(days: Flow<List<StoredScheduleDay>>, rates: Flow<Map<String, String>>) : ViewModel() {
    @Inject constructor(schedule: ScheduleRepository, configuration: ConfigurationRepository) : this(schedule.days, configuration.rates)
    private val mutable = MutableStateFlow(SummaryState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            try { combine(days, rates) { rows, rules -> rows.map { it.planned() } to rules }.collect { (planned, rules) -> mutable.update { it.copy(days = planned, rates = rules, loading = false) } } }
            catch (e: Exception) {
                // Keep the last values; the calendar tab owns the retry UI for storage errors.
                if (e !is IOException && e !is SQLiteException && e !is com.turnocontrol.data.accounts.StorageSessionClosedException) throw e
                mutable.update { it.copy(loading = false) }
            }
        }
    }
    fun show(month: YearMonth) = mutable.update { it.copy(month = month) }
    fun moveMonth(delta: Long) = mutable.update { it.copy(month = it.month.plusMonths(delta)) }
    fun moveYear(delta: Int) = mutable.update { it.copy(month = it.month.plusYears(delta.toLong())) }
    fun selectMonth(month: Int) = mutable.update { it.copy(month = YearMonth.of(it.year, month)) }
    fun earnings(summary: PeriodSummary) = state.value.let { EarningsEstimator.estimate(summary, it.baseRate, it.extraRate) }
}
