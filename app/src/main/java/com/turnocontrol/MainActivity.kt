package com.turnocontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.turnocontrol.ui.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ConfigurationViewModel by viewModels()
    private val importModel: ImportViewModel by viewModels()
    private val reviewModel: ScheduleReviewViewModel by viewModels()
    private val calendarModel: CalendarViewModel by viewModels()
    private val summaryModel: SummaryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            TurnoTheme(theme.dark) { TurnoApp(model, importModel, reviewModel, calendarModel, summaryModel) }
        }
    }
}

private val tabs = listOf("Calendario" to TurnoIcons.Calendar, "Resumen" to TurnoIcons.Summary)

@Composable
fun TurnoApp(model: ConfigurationViewModel, importModel: ImportViewModel, reviewModel: ScheduleReviewViewModel, calendarModel: CalendarViewModel, summaryModel: SummaryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // No saveState/restoreState: secondary routes (Detalle, Ajustes, OCR) above a tab would be saved and resurface on a later tab switch.
    // Tab state lives in Activity-scoped ViewModels; only the scroll position resets.
    fun tab(title: String) = nav.navigate(title) { popUpTo(nav.graph.startDestinationId); launchSingleTop = true }
    val openSettings: () -> Unit = { nav.navigate("Ajustes") { launchSingleTop = true } }
    val backToCalendar: () -> Unit = { nav.navigate("Calendario") { popUpTo("Calendario") { inclusive = true }; launchSingleTop = true } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
            tabs.forEach { (title, icon) ->
                NavigationBarItem(selected = route == title || (title == "Calendario" && route == "Detalle"),
                    onClick = { tab(title) }, icon = { Icon(icon, contentDescription = null) }, label = { Text(title, style = MaterialTheme.typography.labelMedium) })
            }
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = "Calendario", modifier = Modifier.padding(padding)) {
            composable("Calendario") { CalendarScreen(calendarModel, summaryModel, openDay = { nav.navigate("Detalle") },
                openSummary = { month -> summaryModel.show(month); tab("Resumen") }, openSettings = openSettings) }
            composable("Resumen") { SummaryScreen(summaryModel, calendarModel, openDay = { date -> calendarModel.select(date); nav.navigate("Detalle") }, openSettings = openSettings) }
            composable("Detalle") {
                val detailState by calendarModel.state.collectAsStateWithLifecycle()
                if (detailState.selected == null) LaunchedEffect(Unit) { backToCalendar() }
                else DayDetailScreen(calendarModel, backToCalendar)
            }
            composable("Ajustes") { SettingsScreen(model, state, onImport = { nav.navigate("Importar") }, onBack = { nav.popBackStack() }) }
            composable("Importar") { ImportScreen(importModel, reviewModel::clear, { document ->
                reviewModel.prepare(document, state.draft.name, state.draft.alias)
                nav.navigate("Revisar")
            }) { nav.popBackStack() } }
            composable("Revisar") {
                val imported by importModel.state.collectAsStateWithLifecycle()
                ScheduleReviewScreen(reviewModel, { days ->
                    calendarModel.prepareImport(days)
                    nav.navigate("Confirmar")
                }, imported.image?.bitmap) { nav.popBackStack() }
            }
            // Review data is discarded only after a confirmed write; on failure it stays for a retry.
            composable("Confirmar") { ImportConfirmationScreen(calendarModel, { nav.popBackStack() }, backToCalendar,
                saved = { reviewModel.clear(); importModel.clear(); backToCalendar() }) }
        }
    }
}
