@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
fun SettingsScreen(model: ConfigurationViewModel, state: ConfigurationState, onImport: () -> Unit, onBack: () -> Unit,
    backup: BackupViewModel? = null, restoreBlockedReason: String? = null, account: AccountViewModel? = null, onAccount: () -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Ajustes", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            backup?.let { RestoreNotice(it) }
            account?.let { AccountSummaryCard(it, onAccount) }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importar horario") }
            ConfigurationForm(state, model)
            backup?.let { BackupSection(it, restoreBlockedReason) }
        }
    }
}

/** Top of Ajustes: after a restore the form reloads, so a message lower down would be off screen. */
@Composable
private fun RestoreNotice(model: BackupViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val notice = state.notice ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notice, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = model::dismissNotice, contentPadding = PaddingValues(0.dp)) { Text("Entendido") }
        }
    }
}

@Composable
private fun BackupSection(model: BackupViewModel, restoreBlockedReason: String?) {
    val state by model.state.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.exportTo(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.pick(it) }
    HorizontalDivider()
    Text("Copia de seguridad", style = MaterialTheme.typography.titleMedium)
    Text("Guarda perfil, contrato, tarifas, días, turnos y apariencia en un archivo que tú eliges. Todo ocurre en el dispositivo, sin Internet. " +
        "El archivo contiene tus horarios: guárdalo en un lugar privado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { exporter.launch(model.suggestedName()) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Exportar copia de seguridad") }
    OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy && restoreBlockedReason == null,
        modifier = Modifier.fillMaxWidth()) { Text("Restaurar desde una copia") }
    restoreBlockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.announced()) }
    state.preview?.let { preview -> RestorePreviewDialog(preview, state.busy, model::confirmRestore, model::cancelRestore) }
}

@Composable
private fun RestorePreviewDialog(preview: RestorePreview, busy: Boolean, confirm: () -> Unit, cancel: () -> Unit) {
    var understood by remember(preview) { mutableStateOf(false) }
    fun describe(c: DataCounts) = buildString {
        append(if (c.profile) "Perfil y " else "Sin perfil · ")
        append(if (c.contract) "contrato · " else "sin contrato · ")
        append("${c.rates} tarifa(s) · ${c.days} día(s) · ${c.intervals} intervalo(s)")
        if (c.firstDate != null) append(" · del ${c.firstDate} al ${c.lastDate}")
    }
    val header = preview.file.header
    AlertDialog(onDismissRequest = { if (!busy) cancel() }, title = { Text("¿Restaurar esta copia?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Copia creada el ${header.createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)} con TurnoControl ${header.appVersionName}.")
            Text("Contenido de la copia", style = MaterialTheme.typography.labelLarge)
            Text(describe(preview.incoming))
            Text("Datos actuales que se sustituirán", style = MaterialTheme.typography.labelLarge)
            Text(describe(preview.current))
            Text("Se sustituirán TODOS los datos actuales (no se combinan). Antes se guardará en el dispositivo una copia automática de los datos actuales.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            // The whole row (box and label) is one toggle: a 48 dp target and a single TalkBack checkbox.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = understood, enabled = !busy, role = Role.Checkbox) { understood = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(understood, onCheckedChange = null, enabled = !busy)
                Spacer(Modifier.width(8.dp))
                Text("Entiendo que se sustituirán mis datos actuales")
            }
        }
    }, confirmButton = { TextButton(onClick = confirm, enabled = understood && !busy) { Text("Restaurar") } },
        dismissButton = { TextButton(onClick = cancel, enabled = !busy) { Text("Cancelar") } })
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
    state.message?.let { Text(it, modifier = Modifier.fillMaxWidth().announced()) }
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
