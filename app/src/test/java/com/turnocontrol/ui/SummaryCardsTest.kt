package com.turnocontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.turnocontrol.domain.PeriodSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/** Overtime and estimate cards rendered with synthetic values (JVM/Robolectric, not a device check). */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class SummaryCardsTest {
    @get:Rule val compose = createComposeRule()

    // 8 h regular + 1 h 30 min declared extra.
    private val summary = PeriodSummary(workDays = 1, totalMinutes = 570, extraMinutes = 90)

    @Test fun overtimeCardsShowTheMonthlyAndAnnualTitles() {
        compose.setContent { TurnoTheme(dark = false) { Column {
            MonthExtrasCard("noviembre 2026", 90)
            AnnualExtrasCard(2026, 754)
        } } }
        compose.onNodeWithText("Horas extra del mes").assertExists()
        compose.onNodeWithContentDescription("Horas extra del mes (noviembre 2026): 1 h 30 min").assertExists()
        compose.onNodeWithText("Horas extra acumuladas del año 2026").assertExists()
        compose.onNodeWithContentDescription("Horas extra acumuladas del año 2026: 12 h 34 min").assertExists()
        compose.onNodeWithText("Del 1 de enero al 31 de diciembre", substring = true).assertExists()
    }

    @Test fun clickableMonthlyCardAnnouncesItsAction() {
        compose.setContent { TurnoTheme(dark = false) { MonthExtrasCard("noviembre 2026", 0) {} } }
        compose.onNodeWithContentDescription("Horas extra del mes (noviembre 2026): 0 h 0 min. Abre el resumen").assertHasClickAction()
    }

    @Test fun estimateWithBothRatesShowsTheCurrentRatesNoticeAndTheTotal() {
        val state = SummaryState(rates = mapOf("HOURLY_BASE" to "10", "EXTRA_HOUR" to "12.5"), loading = false)
        compose.setContent { TurnoTheme(dark = false) { EarningsCard("Bruto estimado del mes", summary, state) {} } }
        compose.onNodeWithText("Estimación con las tarifas actuales").assertExists()
        // 8 h × 10 = 80,00 €; 1 h 30 min × 12,5 = 18,75 €.
        compose.onNodeWithText("98,75 €").assertExists()
        compose.onNodeWithText("80,00 €").assertExists()
        compose.onNodeWithText("18,75 €").assertExists()
        compose.onNodeWithText(EXTRAS_AS_PAID_NOTE).assertExists()
        compose.onNodeWithText("Estimación bruta nominal · no es salario neto").assertExists()
        compose.onNodeWithText("No disponible").assertDoesNotExist()
    }

    @Test fun missingOvertimeRateNeverShowsAnInventedAmount() {
        val state = SummaryState(rates = mapOf("HOURLY_BASE" to "10"), loading = false)
        compose.setContent { TurnoTheme(dark = false) { EarningsCard("Bruto estimado del mes", summary, state) {} } }
        compose.onNodeWithText("Estimación con las tarifas actuales").assertExists()
        compose.onAllNodesWithText("No disponible").assertCountEquals(2) // Total and the extra part.
        compose.onNodeWithText("Configura la tarifa de hora extra en Ajustes").assertExists()
        compose.onNodeWithText("Configurar tarifas").assertHasClickAction()
    }

    @Test fun missingBaseRateLeavesRegularAndTotalUnavailable() {
        compose.setContent { TurnoTheme(dark = false) { EarningsCard("Bruto estimado del año", summary, SummaryState(loading = false)) {} } }
        compose.onAllNodesWithText("No disponible").assertCountEquals(3)
        compose.onNodeWithText("Configura la tarifa base en Ajustes").assertExists()
    }

    @Test fun eurosUseSpanishSeparatorsAndHalfUpCents() {
        assertEquals("0,00 €", euros(BigDecimal.ZERO))
        assertEquals("18,52 €", euros(BigDecimal("18.515")))
        assertEquals("12.345,68 €", euros(BigDecimal("12345.675")))
        assertEquals("1.234.567,00 €", euros(BigDecimal("1234567")))
    }
}
