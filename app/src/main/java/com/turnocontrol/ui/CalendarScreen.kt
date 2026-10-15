package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.data.planned
import com.turnocontrol.domain.PlannedScheduleValidator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(model: CalendarViewModel, openDay: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val spanish = Locale.forLanguageTag("es")
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { model.move(-1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana anterior" else "Mes anterior" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Text(if (state.week) "${model.weekStart().format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))} – ${model.weekStart().plusDays(6).format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))}" else state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.move(1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana siguiente" else "Mes siguiente" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.week, onClick = { model.week(false) }, label = { Text("Mes") })
                FilterChip(selected = state.week, onClick = { model.week(true) }, label = { Text("Semana") })
            }
            TextButton(onClick = model::today) { Text("Hoy") }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row { listOf("L", "M", "X", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
        val first = if (state.week) model.weekStart() else state.month.atDay(1).minusDays((state.month.atDay(1).dayOfWeek.value - 1).toLong())
        val weeks = if (state.week) 1 else ((state.month.atDay(1).dayOfWeek.value - 1 + state.month.lengthOfMonth() + 6) / 7)
        BoxWithConstraints(Modifier.weight(1f)) {
        val rowHeight = maxOf(48.dp, (maxHeight - 4.dp * (weeks - 1)) / weeks)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(weeks) { week ->
                Row(Modifier.height(rowHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { weekday ->
                        val date = first.plusDays((week * 7 + weekday).toLong())
                        val own = state.days.firstOrNull { it.day.date == date.toString() }
                        val continues = state.days.any { stored -> val day = stored.planned(); day.intervals.any { i ->
                            val offset = java.time.temporal.ChronoUnit.DAYS.between(day.date, date).toInt()
                            offset > 0 && PlannedScheduleValidator.start(i) < (offset + 1) * 1440 && PlannedScheduleValidator.end(i) > offset * 1440
                        } }
                        val label = when (own?.day?.kind) { "WORK" -> "Trabajo"; "FREE" -> "Libre"; else -> "Sin configurar" }
                        val today = date == LocalDate.now()
                        val chosen = date == state.focused
                        Surface(onClick = { model.select(date); openDay() }, enabled = !state.busy && !state.loading,
                            shape = MaterialTheme.shapes.medium,
                            color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            border = if (today) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).semantics {
                                contentDescription = "$date · $label${if (continues) " · Continuación nocturna" else ""}${if (today) " · Hoy" else ""}"
                                selected = chosen
                            }) {
                            Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium,
                                    color = if (!state.week && java.time.YearMonth.from(date) != state.month) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (own != null) Box(Modifier.padding(top = 6.dp).size(8.dp).then(
                                    if (own.day.kind == "WORK") Modifier.background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, androidx.compose.foundation.shape.CircleShape)))
                                if (continues) Text("↳", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
fun ImportConfirmationScreen(model: CalendarViewModel, back: () -> Unit, calendar: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.importSaved) { if (state.importSaved) calendar() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirmar días revisados", style = MaterialTheme.typography.headlineSmall)
        Text("Nada se guarda automáticamente. Compara cada propuesta con la imagen y con el calendario; los días no confirmados se conservan sin cambios.")
        Text("El OCR no clasifica horas extras. Sustituir un día reemplaza también sus marcas de extra; conserva el existente o edítalo después en el calendario.")
        if (state.busy) LinearProgressIndicator()
        state.imports.forEachIndexed { index, proposal ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Propuesta ${index + 1}: ${proposal.date.ifBlank { "Fecha pendiente" }}")
                val existing = model.existingImport(proposal.date)
                Text("Existente: ${existing?.rangeText() ?: "Sin configurar"}")
                Text("Propuesto: ${proposal.text.ifBlank { "Incompleto; introduce los intervalos manualmente" }}")
                proposal.warnings.forEach { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.KEEP) }, enabled = !state.busy) { Text("Conservar") }
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.REPLACE) }, enabled = !state.busy) { Text(if (existing == null) "Añadir" else "Sustituir") }
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.EDIT) }, enabled = !state.busy) { Text("Editar") }
                }
                Text("Decisión: ${when (proposal.choice) { ImportChoice.KEEP -> "Conservar calendario"; ImportChoice.REPLACE -> if (existing == null) "Añadir propuesta" else "Sustituir existente"; ImportChoice.EDIT -> "Guardar propuesta editada" }}")
                if (proposal.choice == ImportChoice.EDIT) {
                    OutlinedTextField(proposal.date, { model.editImport(index, date = it) }, label = { Text("Fecha propuesta ${index + 1} AAAA-MM-DD") }, enabled = !state.busy)
                    OutlinedTextField(proposal.text, { model.editImport(index, text = it) }, label = { Text("Turnos propuestos ${index + 1} o LIBRE") }, enabled = !state.busy)
                }
                Row { Checkbox(proposal.confirmed, { model.editImport(index, confirmed = it) }, enabled = !state.busy && proposal.choice != ImportChoice.KEEP); Text("He revisado este día y confirmo guardarlo") }
            } }
        }
        state.message?.let { Text(it) }
        Button(onClick = model::saveImport, enabled = !state.busy && state.importReady) { Text("Guardar días confirmados") }
        TextButton(onClick = back, enabled = !state.busy) { Text("Volver a revisión") }
        TextButton(onClick = calendar, enabled = !state.busy) { Text("Continuar en calendario manual") }
        Text("Si el calendario ha cambiado, vuelve a revisión y abre de nuevo esta confirmación. Se volverán a comprobar los conflictos dentro de la transacción.")
    }
}
