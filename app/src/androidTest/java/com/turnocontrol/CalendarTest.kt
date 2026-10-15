package com.turnocontrol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.turnocontrol.data.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.YearMonth
import org.junit.*
import org.junit.Assert.*

class CalendarTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val date = LocalDate.now()
    private fun <T> storage(action: suspend (ScheduleRepository) -> T): T = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.turnocontrol.verification")
        val db = EntryPointAccessors.fromApplication(context, CalendarTestDatabase::class.java).database()
        action(ScheduleRepository(db))
    }
    @Before fun cleanSyntheticDays() { storage { repo -> val old = repo.snapshot(); repo.save(old, emptyList(), old.map { LocalDate.parse(it.day.date) }.toSet()) } }
    @After fun cleanup() { cleanSyntheticDays() }
    private fun select(label: String) {
        compose.waitUntil(10000) { runCatching { compose.onNodeWithContentDescription("$date · $label", substring = true).assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithContentDescription("$date · $label", substring = true).performClick()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithText("Marcar libre").assertIsEnabled(); true }.getOrDefault(false) }
    }
    private fun wheel(field: String, hour: Int, minute: Int) {
        compose.onNodeWithContentDescription(field).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rueda Hora").performScrollToIndex(hour)
        compose.onNodeWithContentDescription("Rueda Minuto").performScrollToIndex(minute)
        compose.waitForIdle()
        compose.onNodeWithText(String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)).assertExists()
        compose.onNodeWithText("Confirmar hora").performClick()
    }
    private fun save() {
        compose.onNodeWithText("Guardar registro").performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Guardar registro")).fetchSemanticsNodes().isEmpty() }
    }
    @Test fun wheelSupportsMinutePrecisionCancellationAndLastIntervalChoice() {
        select("Sin configurar")
        compose.onNodeWithContentDescription("Añadir registro").performClick()
        wheel("Entrada 1", 17, 59)
        wheel("Salida 1", 18, 0)
        save()
        compose.onNodeWithText("Programadas: 0 h 1 min").assertExists()
        val before = storage { it.snapshot() }
        compose.onNodeWithText("Editar intervalo 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Entrada 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rueda Hora").performScrollToIndex(14)
        compose.onNodeWithContentDescription("Rueda Minuto").performScrollToIndex(7)
        compose.onNodeWithText("Cancelar hora").performClick()
        compose.onNodeWithText("Entrada · 17:59").assertExists()
        compose.onNodeWithText("Cancelar registro").performClick()
        assertEquals(before, storage { it.snapshot() })
        compose.onNodeWithText("Eliminar intervalo 1").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar cambio").performClick()
        compose.onNodeWithText("Último intervalo").assertExists()
        compose.onNodeWithText("Sin configurar").performClick()
        compose.waitUntil(10000) { storage { it.snapshot().isEmpty() } }
    }
    @Test fun monthAndWeekNavigationCrossDecemberJanuary() {
        repeat(12 - date.monthValue) { compose.onNodeWithContentDescription("Mes siguiente").performClick() }
        compose.onNodeWithText("diciembre ${date.year}").assertExists()
        compose.onNodeWithContentDescription("Mes siguiente").performClick()
        compose.onNodeWithText("enero ${date.year + 1}").assertExists()
        compose.onNodeWithContentDescription("Mes anterior").performClick()
        compose.onNodeWithText("diciembre ${date.year}").assertExists()
        compose.onNodeWithText("Semana").performClick()
        compose.onNodeWithContentDescription("Semana siguiente").performClick()
        compose.onNodeWithContentDescription("Semana anterior").performClick()
        compose.onNodeWithText("Hoy").performClick()
        compose.onNodeWithText("Mes").performClick()
        compose.onNodeWithContentDescription("$date · Sin configurar", substring = true).assertExists()
    }
    @Test fun manualCalendarSupportsSplitOvernightFreeDeletionAndMonthNavigation() {
        compose.onNodeWithContentDescription("Mes siguiente").performClick()
        compose.onNodeWithContentDescription("Mes anterior").performClick()
        compose.onNodeWithText("Semana").performClick()
        compose.onNodeWithContentDescription("Semana siguiente").performClick()
        compose.onNodeWithContentDescription("Semana anterior").performClick()
        compose.onNodeWithText("Mes").performClick()
        select("Sin configurar")
        compose.onNodeWithContentDescription("Añadir registro").performClick()
        compose.onNodeWithContentDescription("Entrada 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Rueda Hora").performScrollToIndex(17)
        compose.onNodeWithText("Cancelar hora").performClick()
        compose.onNodeWithText("Entrada · Elegir hora").assertExists()
        compose.onNodeWithText("Partido").performClick()
        wheel("Entrada 1", 8, 0)
        wheel("Salida 1", 12, 0)
        wheel("Entrada 2", 16, 0)
        wheel("Salida 2", 20, 0)
        compose.onNodeWithContentDescription("Extra intervalo 2").performScrollTo().performClick()
        save()
        compose.onNodeWithText("Programadas: 8 h 0 min").assertExists()
        assertEquals(2, storage { it.snapshot().single().intervals.size })
        assertTrue(storage { it.snapshot().single().intervals[1].isExtra })
        compose.onNodeWithText("Eliminar intervalo 2").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar cambio").performClick()
        compose.waitUntil(10000) { storage { it.snapshot().single().intervals.size == 1 } }
        compose.onNodeWithText("Editar intervalo 1").performScrollTo().performClick()
        wheel("Entrada 1", 22, 0)
        wheel("Salida 1", 0, 0)
        compose.onNodeWithContentDescription("Día de salida 1 · Día siguiente").performScrollTo().performClick()
        save()
        compose.onNodeWithContentDescription("Añadir registro").performClick()
        wheel("Entrada 1", 0, 0)
        wheel("Salida 1", 1, 0)
        compose.onNodeWithContentDescription("Día de entrada 1 · Día siguiente").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Día de salida 1 · Día siguiente").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Extra intervalo 1").performScrollTo().performClick()
        save()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Programadas: 3 h 0 min").assertExists()
        compose.onNodeWithText("Extras declaradas: 1 h 0 min · incluidas en el total").assertExists()
        compose.onNodeWithText("Volver al calendario").performScrollTo().performClick()
        compose.onNodeWithContentDescription("${date.plusDays(1)} · Sin configurar · Continuación nocturna", substring = true).assertExists()
        compose.onNodeWithText("Horas extras").performClick()
        compose.onNodeWithText("1 h 0 min").assertExists()
        compose.onNodeWithText("Calendario").performClick()
        select("Trabajo")
        compose.onNodeWithText("Marcar libre").performScrollTo().performClick()
        compose.onNodeWithText("¿Sustituir turnos por libre?").assertExists()
        compose.onNodeWithText("Cancelar cambio").performClick()
        compose.onNodeWithText("Eliminar día").performScrollTo().performClick()
        compose.onNodeWithText("Confirmar cambio").performClick()
        compose.waitUntil(10000) { storage { it.snapshot().isEmpty() } }
        compose.onNodeWithText("Marcar libre").performScrollTo().performClick()
        compose.waitUntil(10000) { storage { it.snapshot().singleOrNull()?.day?.kind == "FREE" } }
        assertTrue(storage { it.snapshot().single().intervals.isEmpty() })
    }
}
