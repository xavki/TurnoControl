package com.turnocontrol

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.domain.DayKind
import com.turnocontrol.domain.PlannedDay
import com.turnocontrol.ui.CalendarScreen
import com.turnocontrol.ui.CalendarViewModel
import com.turnocontrol.ui.SummaryViewModel
import com.turnocontrol.ui.TurnoTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Regression (QA-1 emulator): at 150 % and 200 % font scale the calendar cells kept a fixed height, so the day-state
 * indicator under the day number was squashed or pushed out. Measured with real device text layout.
 */
class LargeFontCalendarTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db by lazy {
        check(context.packageName == "com.turnocontrol.verification")
        EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database()
    }
    private val schedule by lazy { ScheduleRepository(db) }
    private val day = LocalDate.now().withDayOfMonth(9)

    @Before fun seed() = runBlocking { schedule.snapshot().let { old ->
        schedule.save(old, listOf(PlannedDay(day, DayKind.VACATION, emptyList())), old.map { LocalDate.parse(it.day.date) }.filter { it != day }.toSet())
    } }
    @After fun cleanup() = runBlocking { schedule.snapshot().let { old -> schedule.save(old, emptyList(), old.map { LocalDate.parse(it.day.date) }.toSet()) } }

    @Test fun dayNumberAndStateIndicatorFitInTheCellAt150And200Percent() {
        var scale by mutableFloatStateOf(1.5f)
        val calendar = CalendarViewModel(schedule)
        val summary = SummaryViewModel(schedule, ConfigurationRepository(db, context))
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TurnoTheme(false) { CalendarScreen(calendar, summary, openDay = {}, openSummary = {}, openSettings = {}) }
            }
        }
        val density = context.resources.displayMetrics.density
        listOf(1.5f, 2f).forEach { value ->
            scale = value
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("$day · Vacaciones", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val cell = compose.onNodeWithContentDescription("$day · Vacaciones", substring = true).fetchSemanticsNode().boundsInRoot
            val number = compose.onAllNodesWithText("${day.dayOfMonth}", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }.single { it.top >= cell.top && it.bottom <= cell.bottom + 1 && it.left >= cell.left && it.right <= cell.right }
            // Below the number: 4 dp gap + 14 dp indicator row must fit inside the cell.
            val room = (cell.bottom - number.bottom) / density
            check(room >= 18f) { "Escala $value: solo ${room} dp bajo el número (celda ${cell.height / density} dp)" }
        }
    }
}
