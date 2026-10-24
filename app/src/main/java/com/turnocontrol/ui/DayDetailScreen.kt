@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.data.planned
import com.turnocontrol.domain.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DayDetailScreen(model: CalendarViewModel, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val date = state.selected
    var action by remember(date) { mutableStateOf<String?>(null) }
    val day = state.days.firstOrNull { it.day.date == date.toString() }?.planned()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                ExtendedFloatingActionButton(onClick = { model.openShift() },
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) }, text = { Text("Añadir registro") },
                    modifier = Modifier.semantics { contentDescription = "Añadir registro" })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { model.closeEditor(); back() }, enabled = !state.busy) { Text("Volver al calendario") }
            Text(date?.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM yyyy", Locale.forLanguageTag("es"))) ?: "Selecciona un día", style = MaterialTheme.typography.headlineSmall)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
                Text(when (day?.kind) { DayKind.WORK -> "Trabajo"; DayKind.FREE -> "Libre"; else -> "Sin configurar" },
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            day?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Programadas: ${minutesLabel(it.totalMinutes())}", style = MaterialTheme.typography.titleLarge)
                        Text("Extras declaradas: ${minutesLabel(it.extraMinutes())} · incluidas en el total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (day == null) Text("Añade los intervalos de este día o márcalo como libre.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.days.forEach { stored ->
                val other = stored.planned()
                other.intervals.filter { i -> date != null && other.date < date && other.date.plusDays(i.endDayOffset.toLong()) == date && PlannedScheduleValidator.end(i) > 1440 }.forEach { i ->
                    Text("↳ ${i.start} → ${i.end} · continuación de ${other.date}${if (i.isExtra) " · Extra declarada" else ""}")
                    TextButton(onClick = { model.select(other.date) }, enabled = !state.busy) { Text("Abrir día de inicio") }
                }
            }
            day?.intervals?.forEachIndexed { index, interval ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Intervalo ${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${interval.start}${if (interval.startDayOffset == 1) " · día siguiente" else ""} → ${interval.end}${if (interval.endDayOffset == 1) " · día siguiente" else ""}", style = MaterialTheme.typography.titleLarge)
                        Text("${minutesLabel(interval.durationMinutes())}${if (interval.isExtra) " · Extra declarada" else " · Normal"}", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { model.openShift(index) }, enabled = !state.busy && state.editorReady) { Text("Editar intervalo ${index + 1}") }
                            TextButton(onClick = { action = "interval:$index" }, enabled = !state.busy && state.editorReady) { Text("Eliminar intervalo ${index + 1}") }
                        }
                    }
                }
                day.intervals.getOrNull(index + 1)?.let { next ->
                    val gap = PlannedScheduleValidator.start(next) - PlannedScheduleValidator.end(interval)
                    if (gap > 0) Text("Descanso ${interval.end}–${next.start}: ${minutesLabel(gap)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CalendarRecoveryActions(model, state)
            if (date != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Opciones del día", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { if (day?.kind == DayKind.WORK) action = "free" else model.markFree() }, enabled = !state.busy && state.editorReady) { Text("Marcar libre") }
                if (day != null) TextButton(onClick = { action = "day" }, enabled = !state.busy && state.editorReady) { Text("Eliminar día") }
                if (state.message != null || state.saveOutcome == SaveOutcome.UNKNOWN) TextButton(onClick = { model.select(date) }, enabled = !state.busy) { Text("Recargar día") }
            }
            Text("Planificación · no son fichajes reales", style = MaterialTheme.typography.labelMedium)
        }
    }
    action?.let { pending -> AlertDialog(onDismissRequest = { action = null }, title = { Text(if (pending == "free") "¿Sustituir turnos por libre?" else "¿Eliminar registro?") },
        text = { Text("La continuación de un turno iniciado otro día se conserva.") }, confirmButton = { TextButton(onClick = {
            when { pending == "free" -> model.markFree(); pending == "day" -> model.deleteDay(); else -> model.deleteInterval(pending.substringAfter(':').toInt()) }; action = null
        }) { Text("Confirmar cambio") } }, dismissButton = { TextButton(onClick = { action = null }) { Text("Cancelar cambio") } }) }
    if (state.emptyChoice) AlertDialog(onDismissRequest = model::cancelEmptyChoice, title = { Text("Último intervalo") }, text = { Text("¿Cómo quieres dejar este día?") },
        confirmButton = { TextButton(onClick = model::deleteDay) { Text("Sin configurar") } }, dismissButton = { TextButton(onClick = model::markFree) { Text("Libre") } })
    state.editor?.takeUnless { state.editorHidden }?.let { editor -> ShiftEditorDialog(model, editor, state) }
}

@Composable
private fun ShiftEditorDialog(model: CalendarViewModel, editor: ShiftEditor, state: CalendarState) {
    val busy = state.busy
    val error = state.message
    val editable = !busy && state.reconciliation == null
    var wheel by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    AlertDialog(
        onDismissRequest = model::cancelShift,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (editor.editingIndex == null) "Añadir registro" else "Editar intervalo", style = MaterialTheme.typography.titleLarge)
                state.selected?.let { Text(it.format(DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", Locale.forLanguageTag("es"))), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (editor.editingIndex == null) {
                    Text("Tipo de turno", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(enabled = state.reconciliation == null, selected = !editor.split, onClick = { model.split(false) }, label = { Text("Normal") })
                        FilterChip(enabled = state.reconciliation == null, selected = editor.split, onClick = { model.split(true) }, label = { Text("Partido") })
                    }
                }
                editor.slots.forEachIndexed { index, draft ->
                    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Intervalo ${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { wheel = index to true }, enabled = editable,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Entrada ${index + 1}" }) {
                                Text("Entrada · ${draft.start ?: "Elegir hora"}", style = MaterialTheme.typography.titleLarge)
                            }
                            DayOffset("Día de entrada ${index + 1}", draft.startOffset, editable) { model.slot(index, draft.copy(startOffset = it)) }
                            OutlinedButton(onClick = { wheel = index to false }, enabled = editable,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Salida ${index + 1}" }) {
                                Text("Salida · ${draft.end ?: "Elegir hora"}", style = MaterialTheme.typography.titleLarge)
                            }
                            DayOffset("Día de salida ${index + 1}", draft.endOffset, editable) { model.slot(index, draft.copy(endOffset = it)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.extra, { model.slot(index, draft.copy(extra = it)) }, enabled = editable,
                                    modifier = Modifier.semantics { contentDescription = "Extra intervalo ${index + 1}" })
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Horas extras declaradas", style = MaterialTheme.typography.bodyMedium)
                                    Text("Incluidas en el total programado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                CalendarRecoveryActions(model, state)
            }
        },
        confirmButton = { Button(onClick = model::saveShift, enabled = editable && state.editorReady) { Text("Guardar registro") } },
        dismissButton = { TextButton(onClick = model::cancelShift, enabled = !busy) { Text(if (state.reconciliation?.reviewed == false) "Volver al detalle conservando borrador" else "Cancelar registro") } },
    )
    wheel?.let { (index, start) ->
        val draft = editor.slots[index]
        TimeWheel(if (start) "Hora de entrada" else "Hora de salida", if (start) draft.start else draft.end, { wheel = null }) { time ->
            model.slot(index, if (start) draft.copy(start = time) else draft.copy(end = time)); wheel = null
        }
    }
}

@Composable
private fun DayOffset(label: String, value: Int, enabled: Boolean, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (value == 1) "Día siguiente · +1 día" else "Mismo día", style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = value == 1, onCheckedChange = { change(if (it) 1 else 0) }, enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "$label · Día siguiente"
                stateDescription = if (value == 1) "Día siguiente" else "Mismo día"
            })
    }
}
