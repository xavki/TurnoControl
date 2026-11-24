package com.turnocontrol.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.turnocontrol.domain.PeriodSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Automated accessibility checks that are feasible on the JVM: contrast of the theme roles, wrapping (not clipping)
 * of statistics at 200 % font scale, and touch target / accessible name of every clickable node. They do not replace TalkBack on a device.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class AccessibilityChecksTest {
    @get:Rule val compose = createComposeRule()

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun schemes(): List<Pair<ColorScheme, TurnoColors>> {
        val captured = mutableListOf<Pair<ColorScheme, TurnoColors>>()
        compose.setContent {
            TurnoTheme(false) { captured += MaterialTheme.colorScheme to MaterialTheme.turno }
            TurnoTheme(true) { captured += MaterialTheme.colorScheme to MaterialTheme.turno }
        }
        compose.waitForIdle()
        return captured.distinct().also { assertEquals(2, it.size) }
    }

    @Test fun textAndIndicatorColorsMeetWcagContrastInLightAndDarkThemes() {
        schemes().forEachIndexed { index, (c, t) ->
            val theme = if (index == 0) "claro" else "oscuro"
            // Body text: WCAG AA 4.5:1.
            listOf(
                "onSurface/surface" to contrast(c.onSurface, c.surface),
                "onSurfaceVariant/surface" to contrast(c.onSurfaceVariant, c.surface),
                "onSurfaceVariant/surfaceContainerLow" to contrast(c.onSurfaceVariant, c.surfaceContainerLow),
                "onPrimary/primary" to contrast(c.onPrimary, c.primary),
                "onPrimaryContainer/primaryContainer" to contrast(c.onPrimaryContainer, c.primaryContainer),
                "onTertiaryContainer/tertiaryContainer" to contrast(c.onTertiaryContainer, c.tertiaryContainer),
                "onSecondaryContainer/secondaryContainer" to contrast(c.onSecondaryContainer, c.secondaryContainer),
                "error/surface" to contrast(c.error, c.surface),
                "primary/surface (enlaces y botones de texto)" to contrast(c.primary, c.surface),
            ).forEach { (pair, ratio) -> assertTrue("Tema $theme, $pair = $ratio", ratio >= 4.5) }
            // Day-state indicators are non-text graphics: WCAG 1.4.11, 3:1 against the cell background.
            listOf("trabajo" to c.primary, "libre" to t.free, "vacaciones" to t.vacation, "baja" to t.sick, "extras" to c.tertiary).forEach { (name, color) ->
                listOf(c.surfaceContainerLowest, c.primaryContainer).forEach { cell ->
                    assertTrue("Tema $theme, indicador $name = ${contrast(color, cell)}", contrast(color, cell) >= 3.0)
                }
            }
        }
    }

    private fun SemanticsNode.layout(): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.singleOrNull()
    }

    @Test fun statisticsWrapInsteadOfBeingCutAt200PercentFontScale() {
        val summary = PeriodSummary(workDays = 23, freeDays = 5, vacationDays = 2, sickDays = 1, totalMinutes = 10_110, extraMinutes = 1_470)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TurnoTheme(false) { StatGrid(periodStats(summary), Modifier.width(360.dp)) }
            }
        }
        val texts = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(texts.size >= 14)
        // Robolectric does not measure glyphs like a device, so visual overflow is not asserted here (manual check on a
        // device). What is asserted is deterministic: no statistic limits its lines or disables wrapping.
        texts.forEach { node ->
            val input = node.layout()!!.layoutInput
            val text = node.config.getOrNull(SemanticsProperties.Text)
            assertEquals("Líneas limitadas en $text", Int.MAX_VALUE, input.maxLines)
            assertTrue("Sin ajuste de línea en $text", input.softWrap)
            assertEquals(2f, input.density.fontScale)
        }
        // Every value stays available to a screen reader as one statement per card.
        compose.onNodeWithContentDescription("Horas extras: 24 h 30 min").assertExists()
        compose.onNodeWithContentDescription("Total: 168 h 30 min").assertExists()
    }

    @Test fun clickableElementsHaveAnAccessibleNameAndA48dpTarget() {
        val state = SummaryState(rates = mapOf("HOURLY_BASE" to "10"), loading = false)
        val summary = PeriodSummary(workDays = 1, totalMinutes = 480, extraMinutes = 60)
        compose.setContent { TurnoTheme(false) { androidx.compose.foundation.layout.Column {
            MonthExtrasCard("noviembre 2026", 60) {}
            AnnualExtrasCard(2026, 60) {}
            EarningsCard("Bruto estimado del mes", summary, state) {}
            TurnoTopBar("Resumen", onSettings = {}, onBack = {})
        } } }
        val clickable = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickable.size >= 5)
        clickable.forEach { node ->
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            assertFalse("Elemento pulsable sin nombre accesible", name.isNullOrBlank())
            val size = node.touchBoundsInRoot.let { with(compose.density) { it.width.toDp() to it.height.toDp() } }
            assertTrue("«$name» mide ${size.first} × ${size.second}", size.first >= 48.dp && size.second >= 48.dp)
        }
    }
}
