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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Calendario", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { model.move(-1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana anterior" else "Mes anterior" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Text(if (state.week) "${model.weekStart().format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))} – ${model.weekStart().plusDays(6).format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))}" else state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)),
                style = if (state.week) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { model.move(1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana siguiente" else "Mes siguiente" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.week, onClick = { model.week(false) }, label = { Text("Mes") })
                FilterChip(selected = state.week, onClick = { model.week(true) }, label = { Text("Semana") })
            }
            TextButton(onClick = model::today) { Text("Hoy") }
        }
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        CalendarRecoveryActions(model, state)
        Row { listOf("L", "M", "X", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val first = if (state.week) model.weekStart() else state.month.atDay(1).minusDays((state.month.atDay(1).dayOfWeek.value - 1).toLong())
        val weeks = if (state.week) 1 else ((state.month.atDay(1).dayOfWeek.value - 1 + state.month.lengthOfMonth() + 6) / 7)
        BoxWithConstraints(Modifier.weight(1f)) {
        val availableRowHeight = maxOf(48.dp, (maxHeight - 4.dp * (weeks - 1)) / weeks)
        val rowHeight = if (state.week) minOf(144.dp, availableRowHeight) else availableRowHeight
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
                            shape = MaterialTheme.shapes.small,
                            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = if (today) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).semantics {
                                contentDescription = "$date · $label${if (continues) " · Continuación nocturna" else ""}${if (today) " · Hoy" else ""}"
                                selected = chosen
                            }) {
                            Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium,
                                    color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else if (!state.week && java.time.YearMonth.from(date) != state.month) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (own != null) Box(Modifier.padding(top = 6.dp).size(8.dp).then(
                                    if (own.day.kind == "WORK") Modifier.background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, androidx.compose.foundation.shape.CircleShape)))
                                if (own == null) Text("—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (continues) Text("↳", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        }
        Text("● Trabajo   ○ Libre   — Sin configurar   ↳ Continuación", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
@Composable
internal fun CalendarRecoveryActions(model: CalendarViewModel, state: CalendarState) {
    state.observationError?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = model::retryCalendarObservation, enabled = !state.loading && !state.busy) { Text("Reintentar observación del calendario") }
    }
    val reconciliation = state.reconciliation
    if (state.needsReconciliation || reconciliation != null) {
        TextButton(onClick = model::refreshCalendar, enabled = !state.busy) { Text("Actualizar vista sin volver a guardar") }
    }
    reconciliation?.let { pending ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Comparar guardado de resultado incierto", style = MaterialTheme.typography.titleMedium)
                Text("El borrador se conserva. La coincidencia de datos no confirma qué ocurrió con la operación anterior.")
                val comparisons = pending.comparisons()
                if (comparisons == null) Text("Lee el calendario para consultar lo almacenado antes de decidir.")
                comparisons?.forEach { entry ->
                    Text(entry.date.toString(), style = MaterialTheme.typography.titleSmall)
                    Text("Operación enviada: ${entry.proposed?.rangeText() ?: "Eliminar día (sin configurar)"}")
                    Text("Datos leídos: ${entry.stored?.rangeText() ?: "Sin configurar"}")
                    Text(if (entry.matches) "Los datos leídos coinciden con la propuesta." else "Los datos leídos difieren de la propuesta.")
                }
                if (!pending.reviewed) TextButton(onClick = model::reviewReconciliation, enabled = !state.busy && comparisons != null) {
                    Text("He revisado la comparación")
                } else Text("Comparación revisada. No se repetirá este guardado. Para otra operación, vuelve a abrir el día o vuelve a la revisión OCR.")
                if (state.editor != null && state.editorHidden) TextButton(onClick = { model.openShift() }, enabled = !state.busy) { Text("Ver borrador conservado") }
                if (pending.reviewed && state.editor != null && state.selected != null) {
                    TextButton(onClick = { model.select(state.selected) }, enabled = !state.busy) { Text("Descartar este borrador e iniciar una nueva edición") }
                }
            }
        }
    }
}
@Composable
fun ImportConfirmationScreen(model: CalendarViewModel, back: () -> Unit, calendar: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val editable = !state.busy && state.reconciliation == null
    LaunchedEffect(state.importSaved) { if (state.importSaved) calendar() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirmar días revisados", style = MaterialTheme.typography.headlineSmall)
        Text("Nada se guarda automáticamente. Compara cada propuesta con la imagen y con el calendario; los días no confirmados se conservan sin cambios.")
        Text("El OCR no clasifica horas extras. Sustituir un día reemplaza también sus marcas de extra; conserva el existente o edítalo después en el calendario.")
        if (state.busy) LinearProgressIndicator()
        CalendarRecoveryActions(model, state)
        state.imports.forEachIndexed { index, proposal ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Propuesta ${index + 1}: ${proposal.date.ifBlank { "Fecha pendiente" }}")
                val existing = model.existingImport(proposal.date)
                Text("Existente: ${existing?.rangeText() ?: "Sin configurar"}")
                Text("Propuesto: ${proposal.text.ifBlank { "Incompleto; introduce los intervalos manualmente" }}")
                proposal.warnings.forEach { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.KEEP) }, enabled = editable) { Text("Conservar") }
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.REPLACE) }, enabled = editable) { Text(if (existing == null) "Añadir" else "Sustituir") }
                    TextButton(onClick = { model.editImport(index, choice = ImportChoice.EDIT) }, enabled = editable) { Text("Editar") }
                }
                Text("Decisión: ${when (proposal.choice) { ImportChoice.KEEP -> "Conservar calendario"; ImportChoice.REPLACE -> if (existing == null) "Añadir propuesta" else "Sustituir existente"; ImportChoice.EDIT -> "Guardar propuesta editada" }}")
                if (proposal.choice == ImportChoice.EDIT) {
                    OutlinedTextField(proposal.date, { model.editImport(index, date = it) }, label = { Text("Fecha propuesta ${index + 1} AAAA-MM-DD") }, enabled = editable)
                    OutlinedTextField(proposal.text, { model.editImport(index, text = it) }, label = { Text("Turnos propuestos ${index + 1} o LIBRE") }, enabled = editable)
                }
                Row { Checkbox(proposal.confirmed, { model.editImport(index, confirmed = it) }, enabled = editable && proposal.choice != ImportChoice.KEEP); Text("He revisado este día y confirmo guardarlo") }
            } }
        }
        state.message?.let { Text(it) }
        Button(onClick = model::saveImport, enabled = editable && state.importReady) { Text("Guardar días confirmados") }
        TextButton(onClick = back, enabled = !state.busy) { Text("Volver a revisión") }
        TextButton(onClick = calendar, enabled = !state.busy) { Text("Continuar en calendario manual") }
        Text("Si el calendario ha cambiado, vuelve a revisión y abre de nuevo esta confirmación. Se volverán a comprobar los conflictos dentro de la transacción.")
    }
}
