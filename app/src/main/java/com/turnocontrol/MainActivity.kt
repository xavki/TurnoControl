package com.turnocontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import com.turnocontrol.ui.*
import com.turnocontrol.domain.rateLabels
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ConfigurationViewModel by viewModels()
    private val importModel: ImportViewModel by viewModels()
    private val reviewModel: ScheduleReviewViewModel by viewModels()
    private val calendarModel: CalendarViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark by model.darkTheme.collectAsStateWithLifecycle()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { TurnoApp(model, importModel, reviewModel, calendarModel) }
        }
    }
}

@Composable
fun TurnoApp(model: ConfigurationViewModel, importModel: ImportViewModel, reviewModel: ScheduleReviewViewModel, calendarModel: CalendarViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val screens = listOf("Calendario", "Horas extras", "Nómina", "Ajustes")
    Scaffold(bottomBar = {
        NavigationBar {
            screens.forEachIndexed { index, title ->
                NavigationBarItem(selected = entry?.destination?.route == title || (title == "Calendario" && entry?.destination?.route == "Detalle") || (title == "Ajustes" && entry?.destination?.route in listOf("Importar", "Revisar", "Confirmar")),
                    onClick = { nav.navigate(title) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } },
                    icon = { Text(listOf("▦", "+", "€", "⚙")[index]) }, label = { Text(title) })
            }
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = "Calendario", modifier = Modifier.padding(padding)) {
            composable("Calendario") { CalendarScreen(calendarModel) { nav.navigate("Detalle") } }
            composable("Detalle") {
                val detailState by calendarModel.state.collectAsStateWithLifecycle()
                val returnCalendar: () -> Unit = { nav.navigate("Calendario") { popUpTo("Calendario") { inclusive = true }; launchSingleTop = true } }
                if (detailState.selected == null) LaunchedEffect(Unit) { returnCalendar() }
                else DayDetailScreen(calendarModel, returnCalendar)
            }
            composable("Horas extras") { ExtrasScreen(calendarModel) { date -> calendarModel.select(date); nav.navigate("Detalle") } }
            composable("Importar") { ImportScreen(importModel, reviewModel::clear, { document ->
                reviewModel.prepare(document, state.draft.name, state.draft.alias)
                nav.navigate("Revisar")
            }) { nav.popBackStack() } }
            composable("Revisar") {
                val imported by importModel.state.collectAsStateWithLifecycle()
                ScheduleReviewScreen(reviewModel, {
                calendarModel.prepareImport(reviewModel.confirmableDrafts())
                nav.navigate("Confirmar")
            }, imported.image?.bitmap) { nav.popBackStack() } }
            composable("Confirmar") { ImportConfirmationScreen(calendarModel, { nav.popBackStack() }, {
                nav.navigate("Calendario") { popUpTo("Calendario") { inclusive = true }; launchSingleTop = true }
            }) }
            screens.filter { it !in listOf("Calendario", "Horas extras") }.forEach { title -> composable(title) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("TurnoControl · $title", style = MaterialTheme.typography.headlineSmall)
                    when (title) {
                        "Nómina" -> Text("No hay estimaciones. Las tarifas guardadas no generan cálculos hasta definir las reglas laborales aplicables.")
                        "Ajustes" -> {
                            Button(onClick = { nav.navigate("Importar") }) { Text("Importar horario") }
                            ConfigurationForm(state, model)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun ConfigurationForm(state: ConfigurationState, model: ConfigurationViewModel) {
    if (state.loading) { CircularProgressIndicator(); return }
    val d = state.draft
    val dark by model.darkTheme.collectAsStateWithLifecycle()
    Text("Perfil del trabajador", style = MaterialTheme.typography.titleMedium)
    @Composable fun field(label: String, value: String, change: (String) -> Unit) {
        OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = !state.saving, modifier = Modifier.fillMaxWidth())
    }
    field("Nombre visible", d.name) { model.update(d.copy(name = it)) }
    field("Alias de coincidencia (opcional)", d.alias) { model.update(d.copy(alias = it)) }
    field("Zona horaria IANA", d.zone) { model.update(d.copy(zone = it)) }
    Text("Configuración laboral", style = MaterialTheme.typography.titleMedium)
    Row {
        FilterChip(enabled = !state.saving, selected = d.type == "PARTIAL", onClick = { model.update(d.copy(type = "PARTIAL")) }, label = { Text("Parcial") })
        Spacer(Modifier.width(8.dp))
        FilterChip(enabled = !state.saving, selected = d.type == "FULL", onClick = { model.update(d.copy(type = "FULL")) }, label = { Text("Completa") })
    }
    field("Horas pactadas", d.hours) { model.update(d.copy(hours = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("WEEK" to "Semana", "MONTH" to "Mes", "YEAR" to "Año").forEach { (unit, label) ->
            FilterChip(enabled = !state.saving, selected = d.unit == unit, onClick = { model.update(d.copy(unit = unit)) }, label = { Text(label) })
        }
    }
    field("Vigente desde (AAAA-MM-DD)", d.from) { model.update(d.copy(from = it)) }
    field("Tarifa base EUR/h (opcional)", d.hourlyRate) { model.update(d.copy(hourlyRate = it)) }
    rateLabels.forEach { (type, label) -> field("$label (opcional)", d.extraRates[type].orEmpty()) { model.update(d.copy(extraRates = d.extraRates + (type to it))) } }
    Text("Las tarifas son solo configuración. Franjas, acumulación y clasificación de horas están pendientes de definir.")
    state.message?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
    Button(onClick = model::save, enabled = !state.saving) { Text(if (state.saving) "Guardando…" else "Guardar configuración") }
    HorizontalDivider()
    Text("Apariencia", style = MaterialTheme.typography.titleMedium)
    Row { Text("Tema oscuro", modifier = Modifier.weight(1f)); Switch(checked = dark, onCheckedChange = model::setDarkTheme, modifier = Modifier.semantics { contentDescription = "Tema oscuro" }) }
}
