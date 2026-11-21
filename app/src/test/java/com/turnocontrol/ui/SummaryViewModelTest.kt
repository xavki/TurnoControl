package com.turnocontrol.ui

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turnocontrol.data.*
import com.turnocontrol.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: TurnoDatabase
    private lateinit var repo: ScheduleRepository
    private val rates = MutableStateFlow<Map<String, String>>(emptyMap())
    private val store = ViewModelStore()
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TurnoDatabase::class.java).build()
        repo = ScheduleRepository(db)
    }
    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }
    private fun model() = SummaryViewModel(repo.days, rates).also { store.put("summary", it) }
    private fun extra(date: String, text: String) = PlannedDay(LocalDate.parse(date), DayKind.WORK, TimeCellParser.parse(text).intervals.map { it.copy(isExtra = true) })

    @Test fun monthAndYearCountersAreIndependent() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(extra("2026-01-10", "08:00-09:00"), extra("2026-02-10", "08:00-10:00")))
        val model = model()
        model.show(YearMonth.of(2026, 1))
        model.state.first { !it.loading && it.days.size == 2 }
        assertEquals(60, model.state.value.monthSummary.extraMinutes)
        assertEquals(180, model.state.value.yearSummary.extraMinutes)
        model.moveMonth(1)
        assertEquals(120, model.state.value.monthSummary.extraMinutes)
        assertEquals(180, model.state.value.yearSummary.extraMinutes)
        model.moveYear(1)
        assertEquals(YearMonth.of(2027, 2), model.state.value.month)
        assertEquals(0, model.state.value.monthSummary.extraMinutes)
        assertEquals(0, model.state.value.yearSummary.extraMinutes)
        assertEquals(listOf(60, 120) + List(10) { 0 }, ScheduleSummary.monthlyTotalMinutes(model.state.value.days, 2026))
    }

    @Test fun annualOvertimeFollowsEditsAndDeletionsAndSurvivesMonthChanges() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(extra("2026-01-10", "08:00-13:00"), extra("2026-02-10", "08:00-16:00"), extra("2026-03-10", "08:00-14:00 / 15:00-21:00")))
        val model = model()
        model.show(YearMonth.of(2026, 2))
        model.state.first { !it.loading && it.days.size == 3 }
        assertEquals(8 * 60, model.state.value.monthSummary.extraMinutes)
        assertEquals(25 * 60, model.state.value.yearSummary.extraMinutes)
        model.selectMonth(7)
        assertEquals(0, model.state.value.monthSummary.extraMinutes)
        assertEquals(25 * 60, model.state.value.yearSummary.extraMinutes)
        // edit: March overtime grows from 12 h to 13 h
        repo.save(repo.snapshot(), listOf(extra("2026-03-10", "08:00-14:00 / 15:00-22:00")))
        model.state.first { it.yearSummary.extraMinutes == 26 * 60 }
        // delete February
        val current = repo.snapshot()
        repo.save(current, emptyList(), setOf(LocalDate.parse("2026-02-10")))
        model.state.first { it.yearSummary.extraMinutes == 18 * 60 }
        assertEquals(2, model.state.value.days.size)
        model.moveYear(1)
        assertEquals(0, model.state.value.yearSummary.extraMinutes)
        model.moveYear(-1)
        assertEquals(18 * 60, model.state.value.yearSummary.extraMinutes)
    }

    @Test fun selectMonthKeepsYear() = runTest(dispatcher) {
        val model = model()
        model.show(YearMonth.of(2026, 5))
        model.selectMonth(11)
        assertEquals(YearMonth.of(2026, 11), model.state.value.month)
        assertEquals(2026, model.state.value.year)
    }

    @Test fun ratesWithCommaAreParsed() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-03-10"), DayKind.WORK, TimeCellParser.parse("08:00-16:00").intervals)))
        rates.value = mapOf("HOURLY_BASE" to "7,17", "NIGHT" to "0.50")
        val model = model()
        model.show(YearMonth.of(2026, 3))
        model.state.first { !it.loading && it.days.size == 1 && it.rates.isNotEmpty() }
        assertEquals(BigDecimal("7.17"), model.state.value.baseRate)
        assertNull(model.state.value.extraRate)
        assertEquals(mapOf("NIGHT" to "0.50"), model.state.value.plusRates)
        assertEquals(BigDecimal("57.36"), model.state.value.monthEarnings.regular)
        assertEquals(BigDecimal("57.36"), model.state.value.monthEarnings.total)
        assertEquals(model.state.value.monthEarnings, model.earnings(model.state.value.monthSummary))
    }

    @Test fun earningsUnavailableWithoutRates() = runTest(dispatcher) {
        repo.save(repo.snapshot(), listOf(PlannedDay(LocalDate.parse("2026-03-10"), DayKind.WORK, TimeCellParser.parse("08:00-16:00").intervals)))
        val model = model()
        model.show(YearMonth.of(2026, 3))
        model.state.first { !it.loading && it.days.size == 1 }
        assertNull(model.state.value.monthEarnings.total)
        assertEquals(listOf(EarningsEstimator.MISSING_BASE), model.state.value.monthEarnings.missing)
        assertNull(model.state.value.yearEarnings.total)
    }
}
