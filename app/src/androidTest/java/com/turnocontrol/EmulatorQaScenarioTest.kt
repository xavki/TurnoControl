package com.turnocontrol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.data.ScheduleRepository
import com.turnocontrol.domain.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Multi-month synthetic scenario on a device: Calendario and Resumen must show the same overtime and payroll values,
 * recalculate immediately after an edit, keep them across recreation and attribute an overnight shift of 31 December
 * to its start year. Only the isolated verification app; synthetic data. With the instrumentation argument
 * `qaKeepData=true` the synthetic data is left in place for manual QA (screenshots, rotation, font scale).
 */
class EmulatorQaScenarioTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun waitForStartupGate() = compose.awaitStartup()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db by lazy {
        check(context.packageName == "com.turnocontrol.verification")
        EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database()
    }
    private val schedule by lazy { ScheduleRepository(db) }
    private val configuration by lazy { ConfigurationRepository(db, context) }
    private val keep = InstrumentationRegistry.getArguments().getString("qaKeepData") == "true"
    private val month = YearMonth.now()
    private val year = month.year
    private val previous = month.minusMonths(1)
    private val spanish = Locale.forLanguageTag("es")
    private val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish))

    private fun work(date: LocalDate, text: String, extras: Set<Int>) =
        PlannedDay(date, DayKind.WORK, TimeCellParser.parse(text).intervals.mapIndexed { i, interval -> interval.copy(isExtra = i in extras) })
    private fun absence(date: LocalDate, kind: DayKind) = PlannedDay(date, kind, emptyList())

    // Overtime: day 3 = 3 h 15 min (split), day 5 = 6 h (overnight part after midnight), previous month = 8 h 7 min,
    // 31 December of the previous year = 4 h (belongs to that year, never to this one).
    private val days get() = listOf(
        work(month.atDay(3), "08:00-12:00 / 16:00-19:15", setOf(1)),
        work(month.atDay(5), "22:00-24:00 / 00:00-06:00", setOf(1)),
        work(previous.atDay(15), "09:00-17:07", setOf(0)),
        work(LocalDate.of(year - 1, 12, 31), "22:00-24:00 / 00:00-02:00", setOf(0, 1)),
        absence(month.atDay(8), DayKind.VACATION), absence(month.atDay(9), DayKind.VACATION), absence(month.atDay(10), DayKind.VACATION),
        absence(month.atDay(12), DayKind.SICK_LEAVE), absence(month.atDay(13), DayKind.SICK_LEAVE),
        absence(month.atDay(14), DayKind.FREE),
    )
    private val draft = ConfigurationDraft(name = "Perfil ficticio QA", zone = "Europe/Madrid", hours = "40", unit = "WEEK",
        from = "${year - 1}-01-01", hourlyRate = "10", extraRates = mapOf("EXTRA_HOUR" to "15"))

    @Before fun seed() = runBlocking {
        val old = schedule.snapshot()
        schedule.save(old, days, old.map { LocalDate.parse(it.day.date) }.filter { d -> days.none { it.date == d } }.toSet())
        configuration.save(draft)
    }
    @After fun cleanup() = runBlocking {
        if (keep) return@runBlocking
        val old = schedule.snapshot()
        schedule.save(old, emptyList(), old.map { LocalDate.parse(it.day.date) }.toSet())
    }

    private fun waitDesc(desc: String) = compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun tab(name: String) = compose.onAllNodesWithText(name).filterToOne(hasClickAction()).performClick()

    @Test fun calendarAndSummaryAgreeAndRecalculateAfterEditsAndRecreation() {
        val annual = if (previous.year == year) "17 h 22 min" else "9 h 15 min"
        // Month: regular 6 h × 10 = 60,00 €; extras 9 h 15 min × 15 = 138,75 €.
        waitDesc("Horas extra del mes ($monthLabel): 9 h 15 min. Abre el resumen")
        compose.onNodeWithContentDescription("Horas extra acumuladas del año $year: $annual").assertExists()
        compose.onNodeWithText("198,75 €").assertExists()
        compose.onNodeWithContentDescription("Vacaciones: 3").assertExists()
        compose.onNodeWithContentDescription("Baja médica: 2").assertExists()
        compose.onNodeWithContentDescription("Días libres: 1").assertExists()
        compose.onNodeWithContentDescription("${month.atDay(9)} · Vacaciones", substring = true).assertExists()
        compose.onNodeWithContentDescription("${month.atDay(6)} · Sin configurar · Continuación nocturna", substring = true).assertExists()

        tab("Resumen")
        waitDesc("Horas extra del mes ($monthLabel): 9 h 15 min")
        compose.onNodeWithContentDescription("Horas extra acumuladas del año $year: $annual").assertExists()
        // Month card only, unless it is January: then the previous month is last year and the year card equals the month.
        compose.onAllNodesWithText("198,75 €").assertCountEquals(if (previous.year == year) 1 else 2)
        compose.onNodeWithContentDescription("${month.month.getDisplayName(java.time.format.TextStyle.FULL, spanish)} de $year: 9 h 15 min de horas extra").assertExists()
        // Year transition: 31 December of last year stays in last year.
        compose.onNodeWithContentDescription("Año anterior").performClick()
        val lastYear = if (previous.year == year) "4 h 0 min" else "12 h 7 min"
        waitDesc("Horas extra acumuladas del año ${year - 1}: $lastYear")
        compose.onNodeWithContentDescription("Año siguiente").performClick()

        // Edit through the UI: remove the overnight extra interval of day 5.
        tab("Calendario")
        val cell = hasContentDescription("${month.atDay(5)} · Trabajo", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodes(cell and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(cell).performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Eliminar intervalo 2").assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithText("Eliminar intervalo 2").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar cambio").performClick()
        waitText("Programadas: 2 h 0 min")
        compose.onNodeWithText("Volver al calendario").performScrollTo().performClick()
        val annualAfter = if (previous.year == year) "11 h 22 min" else "3 h 15 min"
        waitDesc("Horas extra del mes ($monthLabel): 3 h 15 min. Abre el resumen")
        compose.onNodeWithContentDescription("Horas extra acumuladas del año $year: $annualAfter").assertExists()
        // Regular 6 h × 10 = 60,00 €; extras 3 h 15 min × 15 = 48,75 €.
        compose.onNodeWithText("108,75 €").assertExists()
        tab("Resumen")
        waitDesc("Horas extra del mes ($monthLabel): 3 h 15 min")
        compose.onNodeWithContentDescription("Horas extra acumuladas del año $year: $annualAfter").assertExists()

        // Recreation keeps everything; the stored data is the source of truth.
        compose.activityRule.scenario.recreate()
        compose.awaitStartup()
        tab("Calendario")
        waitDesc("Horas extra del mes ($monthLabel): 3 h 15 min. Abre el resumen")
        runBlocking { check(schedule.snapshot().size == days.size) }
    }

    @Test fun missingOvertimeRateShowsAMessageInsteadOfAnAmount() {
        runBlocking { configuration.save(draft.copy(extraRates = emptyMap())) }
        waitText("Configura la tarifa de hora extra en Ajustes")
        compose.onAllNodesWithText("198,75 €").assertCountEquals(0)
        compose.onAllNodesWithText("No disponible").fetchSemanticsNodes().let { check(it.isNotEmpty()) }
        runBlocking { configuration.save(draft) }
        waitText("198,75 €")
    }
}
