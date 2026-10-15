package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.domain.*

@Composable
fun ScheduleReviewScreen(model: ScheduleReviewViewModel, confirm: () -> Unit, image: android.graphics.Bitmap? = null, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Revisión provisional", style = MaterialTheme.typography.headlineSmall)
            Text("Reconstruye fechas en columnas y trabajadores en filas. Primero se busca el nombre o alias exacto; también puedes confirmar una fila manualmente.")
            Text("Las propuestas permanecen en memoria hasta revisarlas y confirmar su guardado en el calendario.")
            OutlinedTextField(state.name, { model.updateIdentity(name = it) }, label = { Text("Trabajador: nombre completo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.alias, { model.updateIdentity(alias = it) }, label = { Text("Alias exacto (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.month, { model.updateIdentity(month = it) }, label = { Text("Mes de la tabla AAAA-MM (si falta)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Solo indica mes/año si lo conoces. Sin una fecha completa o contexto explícito, la fecha queda pendiente.")
            Button(onClick = { model.interpret() }, enabled = !state.loading) { Text("Interpretar trabajador") }
            if (state.loading) CircularProgressIndicator()
            state.message?.let { Text(it) }
            state.result?.let { result ->
                result.issues.forEach { Text(it.message) }
                if (result.selectedRowId != null) Text("Fila seleccionada: ${result.candidates.firstOrNull { it.id == result.selectedRowId }?.label.orEmpty()}")
                if (result.days.isEmpty()) Text("No hay turnos asignados automáticamente. Revisa las advertencias o añade una corrección manual.")
                if (result.selectedRowId == null) result.candidates.forEach { candidate ->
                    OutlinedButton(onClick = { model.interpret(candidate.id) }, enabled = !state.loading) {
                        Text("Confirmar fila ${candidate.id + 1}: ${candidate.label} (y=${candidate.top}–${candidate.bottom})")
                    }
                }
                OcrRowSelector(image, state.documentWidth, state.documentHeight, result.candidates, state.loading,
                    { model.interpret(it) }, { model.interpret(band = it) })
            }
        }
        itemsIndexed(state.drafts) { index, draft ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val day = draft.source
                    Text("Día ${index + 1} · ${day.date ?: "Fecha pendiente"}", style = MaterialTheme.typography.titleMedium)
                    Text("Cabecera OCR: ${day.header}")
                    Text("Celda OCR original: ${day.sourceText.ifBlank { "(vacía)" }}")
                    Text(when (day.status) {
                        InterpretationStatus.READY -> "Interpretado · pendiente de revisión"
                        InterpretationStatus.NEEDS_REVIEW -> "Requiere revisión"
                        InterpretationStatus.INCOMPLETE -> "Interpretación incompleta"
                        InterpretationStatus.CORRECTED -> "Corregido manualmente · provisional"
                    })
                    if (day.kind == DayKind.FREE) Text("Día libre explícito")
                    if (day.absence == ProvisionalAbsence.VACATION) Text("Vacaciones/ausencia provisional · no se guardará en el calendario")
                    day.intervals.forEach { interval -> Text("${interval.start} (+${interval.startDayOffset} día) → ${interval.end} (+${interval.endDayOffset} día)") }
                    day.issues.forEach { Text(it.message) }
                    if (day.status == InterpretationStatus.CORRECTED && draft.originalIssues.isNotEmpty()) {
                        Text("Advertencias del OCR inicial (antes de corregir):")
                        draft.originalIssues.forEach { Text(it.message) }
                    }
                    OutlinedTextField(draft.dateText, { model.edit(index, date = it) }, label = { Text("Fecha ${index + 1} (AAAA-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.timeText, { model.edit(index, time = it) }, label = { Text("Intervalos ${index + 1} o LIBRE") }, modifier = Modifier.fillMaxWidth())
                    if (draft.dirty) Text("Hay cambios sin aplicar a la revisión.")
                    draft.error?.let { Text(it) }
                    Button(onClick = { model.correct(index) }, enabled = !state.loading) { Text("Aplicar corrección al día ${index + 1}") }
                }
            }
        }
        item {
            OutlinedButton(onClick = model::addManualDay, enabled = !state.loading) { Text("Añadir día manual a revisión") }
            Text("Compara con la imagen y el OCR. Si la interpretación falla, añade días manualmente o vuelve al calendario.")
            if (state.drafts.any { it.source.absence != null }) Text("Las vacaciones quedan excluidas del guardado; no se convierten en días libres.")
            Button(onClick = confirm, enabled = !state.loading && model.confirmableDrafts().isNotEmpty()) { Text("Revisar conflictos y confirmar") }
            TextButton(onClick = back) { Text("Volver a imagen y OCR") }
        }
    }
}
