package com.turnocontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
            val theme by model.theme.collectAsStateWithLifecycle()
            MaterialTheme(
                colorScheme = turnoColors(theme.dark),
                shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
            ) { TurnoApp(model, importModel, reviewModel, calendarModel) }
        }
    }
}

private fun turnoColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFFA6D1C4), onPrimary = Color(0xFF0E372D),
    secondary = Color(0xFFAECDBB), onSecondary = Color(0xFF183527),
    primaryContainer = Color(0xFF245044), onPrimaryContainer = Color(0xFFC2EAD9),
    secondaryContainer = Color(0xFF2B3A31), onSecondaryContainer = Color(0xFFD8E9DC),
    background = Color(0xFF121814), surface = Color(0xFF121814),
    onBackground = Color(0xFFE1E9E1), onSurface = Color(0xFFE1E9E1),
    surfaceVariant = Color(0xFF26312B), onSurfaceVariant = Color(0xFFBAC9BE),
    surfaceContainerLowest = Color(0xFF0D130F), surfaceContainerLow = Color(0xFF17201A),
    surfaceContainer = Color(0xFF1C261F), surfaceContainerHigh = Color(0xFF232D26), surfaceContainerHighest = Color(0xFF2E3830),
) else lightColorScheme(
    primary = Color(0xFF27645D), onPrimary = Color.White,
    secondary = Color(0xFF52645D), onSecondary = Color.White,
    primaryContainer = Color(0xFFD2EAE3), onPrimaryContainer = Color(0xFF123832),
    secondaryContainer = Color(0xFFE3EBE5), onSecondaryContainer = Color(0xFF182E26),
    background = Color(0xFFF9FAF7), surface = Color(0xFFF9FAF7),
    onBackground = Color(0xFF1D2521), onSurface = Color(0xFF1D2521),
    surfaceVariant = Color(0xFFE8EEE8), onSurfaceVariant = Color(0xFF52615A),
    outlineVariant = Color(0xFFD7DED8),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F0),
    surfaceContainer = Color(0xFFECEFEA), surfaceContainerHigh = Color(0xFFE6EBE5), surfaceContainerHighest = Color(0xFFE1E7DF),
)

@Composable
fun TurnoApp(model: ConfigurationViewModel, importModel: ImportViewModel, reviewModel: ScheduleReviewViewModel, calendarModel: CalendarViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val screens = listOf("Calendario", "Horas extras", "Nómina", "Ajustes")
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            screens.forEachIndexed { index, title ->
                NavigationBarItem(selected = entry?.destination?.route == title || (title == "Calendario" && entry?.destination?.route == "Detalle") || (title == "Ajustes" && entry?.destination?.route in listOf("Importar", "Revisar", "Confirmar")),
                    onClick = { nav.navigate(title) { popUpTo(nav.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } },
                    icon = { Text(listOf("▦", "+", "€", "⚙")[index], style = MaterialTheme.typography.titleLarge) }, label = { Text(title, style = MaterialTheme.typography.labelMedium) })
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("TurnoControl · $title", style = MaterialTheme.typography.headlineSmall)
                    when (title) {
                        "Nómina" -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Sin estimaciones disponibles", style = MaterialTheme.typography.titleMedium)
                                Text("No hay estimaciones. Las tarifas guardadas no generan cálculos hasta definir las reglas laborales aplicables.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        "Ajustes" -> {
                            OutlinedButton(onClick = { nav.navigate("Importar") }, modifier = Modifier.fillMaxWidth()) { Text("Importar horario") }
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
    val theme by model.theme.collectAsStateWithLifecycle()
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Tarifas", style = MaterialTheme.typography.titleMedium)
    Text("Gestiona aquí los importes por hora. El editor diario se centra en los horarios.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    field("Tarifa base EUR/h (opcional)", d.hourlyRate) { model.update(d.copy(hourlyRate = it)) }
    rateLabels.forEach { (type, label) -> field("$label (opcional)", d.extraRates[type].orEmpty()) { model.update(d.copy(extraRates = d.extraRates + (type to it))) } }
    Text("Las tarifas son solo configuración. Franjas, acumulación y clasificación de horas están pendientes de definir.")
    state.message?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
    Button(onClick = model::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(if (state.saving) "Guardando…" else "Guardar configuración") }
    HorizontalDivider()
    Text("Apariencia", style = MaterialTheme.typography.titleMedium)
    Row { Text("Tema oscuro", modifier = Modifier.weight(1f)); Switch(checked = theme.dark, onCheckedChange = model::setDarkTheme, enabled = !theme.loading && theme.error == null, modifier = Modifier.semantics { contentDescription = "Tema oscuro" }) }
    if (theme.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    theme.error?.let {
        Text(it)
        TextButton(onClick = model::retryThemeRead) { Text("Reintentar lectura de apariencia") }
    }
}
