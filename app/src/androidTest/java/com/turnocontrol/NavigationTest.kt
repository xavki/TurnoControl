package com.turnocontrol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun navigationConfigurationAndThemeSurviveRecreation() {
        compose.waitUntil(10000) {
            runCatching { compose.onAllNodes(hasText("TurnoControl · Inicio")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        listOf("Calendario", "Fichajes", "Nómina", "Ajustes", "Inicio").forEach {
            compose.onNodeWithText(it).performClick()
            compose.onNodeWithText("TurnoControl · $it").assertIsDisplayed()
        }
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithText("Nombre visible").performScrollTo().performTextInput("Perfil ficticio")
        compose.onNodeWithText("Horas pactadas").performScrollTo().performTextInput("60")
        compose.onNodeWithText("Tarifa base EUR/h (opcional)").performScrollTo().performTextInput("7,17")
        compose.onNodeWithText("Guardar configuración").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodes(hasText("Configuración guardada en este dispositivo.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Inicio").performClick()
        compose.onNodeWithText("Perfil local configurado. Aún no hay actividad registrada.").assertIsDisplayed()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithContentDescription("Tema oscuro").performScrollTo().performClick()
        compose.waitUntil(5000) {
            compose.onNodeWithContentDescription("Tema oscuro").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Ajustes").performClick()
        compose.onNodeWithContentDescription("Tema oscuro").assertIsOn()
        compose.onNodeWithText("Tarifa base EUR/h (opcional)").performScrollTo()
        // Activity recreation retains its ViewModel and the user's decimal separator.
        compose.onNodeWithText("7,17").assertExists()
    }
}
