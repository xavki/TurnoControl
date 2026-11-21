@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.domain.rateLabels

@Composable
fun TurnoTopBar(title: String, onSettings: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") } } },
        actions = { onSettings?.let { IconButton(onClick = it) { Icon(Icons.Default.Settings, contentDescription = "Ajustes") } } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun SettingsScreen(model: ConfigurationViewModel, state: ConfigurationState, onImport: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Ajustes", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importar horario") }
            ConfigurationForm(state, model)
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
    Text("Las tarifas alimentan solo la estimación bruta de Calendario y Resumen. Los pluses se muestran sin cálculo automático.")
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
