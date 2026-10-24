package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { model.openShift() }, modifier = Modifier.semantics { contentDescription = "Añadir registro" }) { Text("+", style = MaterialTheme.typography.headlineMedium) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { model.closeEditor(); back() }, enabled = !state.busy) { Text("Volver al calendario") }
            Text(date?.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM yyyy", Locale.forLanguageTag("es"))) ?: "Selecciona un día", style = MaterialTheme.typography.headlineSmall)
            Text(when (day?.kind) { DayKind.WORK -> "Trabajo"; DayKind.FREE -> "Libre"; else -> "Sin configurar" }, style = MaterialTheme.typography.titleMedium)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            day?.let {
                Text("Programadas: ${minutesLabel(it.totalMinutes())}", style = MaterialTheme.typography.titleLarge)
                Text("Extras declaradas: ${minutesLabel(it.extraMinutes())} · incluidas en el total")
            }
            state.days.forEach { stored ->
                val other = stored.planned()
                other.intervals.filter { i -> date != null && other.date < date && other.date.plusDays(i.endDayOffset.toLong()) == date && PlannedScheduleValidator.end(i) > 1440 }.forEach { i ->
                    Text("↳ ${i.start} → ${i.end} · continuación de ${other.date}${if (i.isExtra) " · Extra declarada" else ""}")
                    TextButton(onClick = { model.select(other.date) }, enabled = !state.busy) { Text("Abrir día de inicio") }
                }
            }
            day?.intervals?.forEachIndexed { index, interval ->
                HorizontalDivider()
                Text("${interval.start}${if (interval.startDayOffset == 1) " · día siguiente" else ""} → ${interval.end}${if (interval.endDayOffset == 1) " · día siguiente" else ""}", style = MaterialTheme.typography.titleMedium)
                Text("${minutesLabel(interval.durationMinutes())}${if (interval.isExtra) " · Extra declarada" else " · Normal"}")
                Row {
                    TextButton(onClick = { model.openShift(index) }, enabled = !state.busy && state.editorReady) { Text("Editar intervalo ${index + 1}") }
                    TextButton(onClick = { action = "interval:$index" }, enabled = !state.busy && state.editorReady) { Text("Eliminar intervalo ${index + 1}") }
                }
                day.intervals.getOrNull(index + 1)?.let { next ->
                    val gap = PlannedScheduleValidator.start(next) - PlannedScheduleValidator.end(interval)
                    if (gap > 0) Text("Descanso ${interval.end}–${next.start}: ${minutesLabel(gap)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CalendarRecoveryActions(model, state)
            if (date != null) {
                TextButton(onClick = { if (day?.kind == DayKind.WORK) action = "free" else model.markFree() }, enabled = !state.busy && state.editorReady) { Text("Marcar libre") }
                if (day != null) TextButton(onClick = { action = "day" }, enabled = !state.busy && state.editorReady) { Text("Eliminar día") }
                if (state.message != null || state.saveOutcome == SaveOutcome.UNKNOWN) TextButton(onClick = { model.select(date) }, enabled = !state.busy) { Text("Recargar día") }
            }
            Text("Planificación · no son fichajes reales", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(80.dp))
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
    AlertDialog(onDismissRequest = model::cancelShift, title = { Text(if (editor.editingIndex == null) "Añadir registro" else "Editar intervalo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editor.editingIndex == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(enabled = state.reconciliation == null, selected = !editor.split, onClick = { model.split(false) }, label = { Text("Normal") })
                FilterChip(enabled = state.reconciliation == null, selected = editor.split, onClick = { model.split(true) }, label = { Text("Partido") })
            }
            editor.slots.forEachIndexed { index, draft ->
                Text("Intervalo ${index + 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { wheel = index to true }, enabled = editable, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Entrada ${index + 1}" }) { Text("Entrada · ${draft.start ?: "Elegir hora"}") }
                DayOffset("Día de entrada ${index + 1}", draft.startOffset, editable) { model.slot(index, draft.copy(startOffset = it)) }
                OutlinedButton(onClick = { wheel = index to false }, enabled = editable, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Salida ${index + 1}" }) { Text("Salida · ${draft.end ?: "Elegir hora"}") }
                DayOffset("Día de salida ${index + 1}", draft.endOffset, editable) { model.slot(index, draft.copy(endOffset = it)) }
                Row { Checkbox(draft.extra, { model.slot(index, draft.copy(extra = it)) }, enabled = editable, modifier = Modifier.semantics { contentDescription = "Extra intervalo ${index + 1}" }); Text("Horas extras declaradas") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            CalendarRecoveryActions(model, state)
        }
    }, confirmButton = { TextButton(onClick = model::saveShift, enabled = editable && state.editorReady) { Text("Guardar registro") } }, dismissButton = { TextButton(onClick = model::cancelShift, enabled = !busy) { Text(if (state.reconciliation?.reviewed == false) "Volver al detalle conservando borrador" else "Cancelar registro") } })
    wheel?.let { (index, start) ->
        val draft = editor.slots[index]
        TimeWheel(if (start) "Hora de entrada" else "Hora de salida", if (start) draft.start else draft.end, { wheel = null }) { time ->
            model.slot(index, if (start) draft.copy(start = time) else draft.copy(end = time)); wheel = null
        }
    }
}

@Composable
private fun DayOffset(label: String, value: Int, enabled: Boolean, change: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0 to "Mismo día", 1 to "Día siguiente").forEach { (offset, text) ->
            FilterChip(selected = value == offset, onClick = { change(offset) }, enabled = enabled, label = { Text(text) }, modifier = Modifier.semantics { contentDescription = "$label · $text" })
        }
    }
}
