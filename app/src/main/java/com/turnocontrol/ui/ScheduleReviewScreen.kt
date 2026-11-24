package com.turnocontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.domain.*

@Composable
fun ReviewStatusChip(text: String, container: Color, content: Color, check: Boolean = false) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (check) Icon(TurnoIcons.Check, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ScheduleReviewScreen(model: ScheduleReviewViewModel, confirm: (List<PlannedDay>) -> Unit, image: android.graphics.Bitmap? = null, back: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Revisión de turnos detectados", style = MaterialTheme.typography.headlineSmall)
            Text("Comprueba cada día con la imagen. Si es correcto, márcalo como revisado; edita solo lo que esté mal. Nada se guarda hasta la confirmación final.")
            OutlinedTextField(state.name, { model.updateIdentity(name = it) }, label = { Text("Trabajador: nombre completo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.alias, { model.updateIdentity(alias = it) }, label = { Text("Alias exacto (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.month, { model.updateIdentity(month = it) }, label = { Text("Mes de la tabla AAAA-MM (si falta)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Solo indica mes/año si lo conoces. Sin una fecha completa o contexto explícito, la fecha queda pendiente.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Button(onClick = { model.interpret() }, enabled = !state.loading) { Text("Interpretar trabajador") }
            if (state.loading) CircularProgressIndicator()
            state.result?.let { result ->
                result.issues.forEach { Text(it.message) }
                if (result.selectedRowId != null) Text("Fila seleccionada: ${result.candidates.firstOrNull { it.id == result.selectedRowId }?.label.orEmpty()}")
                if (result.days.isEmpty()) Text("No hay turnos asignados automáticamente. Revisa las advertencias o añade un día manualmente.")
                if (result.selectedRowId == null) result.candidates.forEach { candidate ->
                    OutlinedButton(onClick = { model.interpret(candidate.id) }, enabled = !state.loading) {
                        Text("Confirmar fila ${candidate.id + 1}: ${candidate.label} (y=${candidate.top}–${candidate.bottom})")
                    }
                }
                OcrRowSelector(image, state.documentWidth, state.documentHeight, result.candidates, state.loading,
                    { model.interpret(it) }, { model.interpret(band = it) })
            }
            if (state.drafts.isNotEmpty()) {
                val included = state.drafts.filter { it.included }
                Spacer(Modifier.height(8.dp))
                Text("Revisados ${included.count { it.status == ReviewStatus.REVIEWED }} de ${included.size} días incluidos", style = MaterialTheme.typography.titleMedium)
            }
        }
        itemsIndexed(state.drafts) { index, draft -> ReviewDayCard(model, index, draft, state.loading) }
        item {
            OutlinedButton(onClick = model::addManualDay, enabled = !state.loading) { Text("Añadir día manual") }
            Text("Los días excluidos no se guardan. Las vacaciones (VAC) empiezan excluidas: no se convierten en días libres.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            state.message?.let { message ->
                Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("review-message")) {
                    Text(message, Modifier.padding(12.dp).announced())
                }
            }
            Button(onClick = { model.readyForSave()?.let(confirm) }, enabled = !state.loading && state.drafts.any { it.included }, modifier = Modifier.fillMaxWidth().testTag("review-confirm")) {
                Text("Confirmar y guardar turnos")
            }
            Text("Antes de escribir se comprueban duplicados y conflictos con el calendario, y se pide una confirmación final.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            TextButton(onClick = back) { Text("Volver a imagen y OCR") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewDayCard(model: ScheduleReviewViewModel, index: Int, draft: ReviewDayDraft, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    val day = draft.source
    val reviewed = draft.included && draft.status == ReviewStatus.REVIEWED
    Card(Modifier.fillMaxWidth().testTag("review-day-$index"),
        colors = CardDefaults.cardColors(containerColor = when {
            !draft.included -> colors.surfaceContainerLow
            reviewed -> colors.primaryContainer.copy(alpha = .45f)
            else -> colors.surfaceContainer
        }),
        border = if (reviewed) BorderStroke(1.5.dp, colors.primary) else if (draft.included && draft.status == ReviewStatus.INVALID) BorderStroke(1.dp, colors.error) else null) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Día ${index + 1} · ${draft.dateText.ifBlank { "Fecha pendiente" }}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when {
                    !draft.included -> ReviewStatusChip("Excluido", colors.surfaceVariant, colors.onSurfaceVariant)
                    draft.status == ReviewStatus.REVIEWED -> ReviewStatusChip(draft.status.label, colors.primary, colors.onPrimary, check = true)
                    draft.status == ReviewStatus.INVALID -> ReviewStatusChip(draft.status.label, colors.errorContainer, colors.onErrorContainer)
                    else -> ReviewStatusChip(draft.status.label, colors.tertiaryContainer, colors.onTertiaryContainer)
                }
            }
            val planned = draft.planned
            if (planned != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                planned.kind.icon()?.let { Icon(it, contentDescription = null, tint = planned.kind.tint(colors, MaterialTheme.turno), modifier = Modifier.size(20.dp)) }
                Text(if (planned.kind == DayKind.FREE) "Día libre" else planned.rangeText(), style = MaterialTheme.typography.bodyLarge)
            } else Text("Detectado: ${draft.timeText.replace('\n', ' ').ifBlank { "(sin horario)" }}", style = MaterialTheme.typography.bodyLarge)
            if (planned?.intervals?.size == 2) Text("Turno partido", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (planned?.intervals?.any { it.endDayOffset > it.startDayOffset } == true) Text("Termina al día siguiente (turno nocturno)", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("Cabecera OCR: ${day.header} · celda: ${day.sourceText.replace("\n", " | ").ifBlank { "(vacía)" }}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (draft.included) draft.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            if (draft.edited) Text("Corregido manualmente", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val warnings = (if (draft.edited) draft.originalIssues else day.issues).filter { it != ScheduleIssue.MANUAL_CORRECTION }
            if (warnings.isNotEmpty() && draft.included) {
                Text(if (draft.edited) "Advertencias del OCR original:" else "Advertencias del OCR:", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                warnings.forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            draft.editor?.let { editor -> DayEditorForm(model, index, editor, loading) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (draft.included) {
                    if (draft.status == ReviewStatus.REVIEWED) OutlinedButton(onClick = { model.markPending(index) }, enabled = !loading, modifier = Modifier.testTag("pending-$index")) { Text("Marcar como pendiente") }
                    else Button(onClick = { model.markReviewed(index) }, enabled = !loading && draft.canReview, modifier = Modifier.testTag("review-$index")) { Text("Marcar como revisado") }
                    if (draft.editor == null) TextButton(onClick = { model.startEdit(index) }, enabled = !loading, modifier = Modifier.testTag("edit-$index")) { Text("Editar") }
                }
                TextButton(onClick = { model.setIncluded(index, !draft.included) }, enabled = !loading, modifier = Modifier.testTag("include-$index")) { Text(if (draft.included) "Excluir" else "Incluir") }
            }
        }
    }
}

@Composable
private fun DayEditorForm(model: ScheduleReviewViewModel, index: Int, editor: DayEditor, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Editar día ${index + 1}", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(editor.date, { model.editDate(index, it) }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().testTag("edit-date-$index"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(editor.free, { model.editFree(index, it) }, modifier = Modifier.testTag("edit-free-$index"))
                Text("Día libre (sin horas)")
            }
            if (!editor.free) {
                editor.intervals.forEachIndexed { slot, interval ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(interval.start, { model.editInterval(index, slot, start = it) }, label = { Text("Entrada") }, placeholder = { Text("HH:mm") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f).testTag("edit-start-$index-$slot"))
                        OutlinedTextField(interval.end, { model.editInterval(index, slot, end = it) }, label = { Text("Salida") }, placeholder = { Text("HH:mm") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.weight(1f).testTag("edit-end-$index-$slot"))
                        TextButton(onClick = { model.removeInterval(index, slot) }, enabled = !loading) { Text("Quitar") }
                    }
                }
                TextButton(onClick = { model.addInterval(index) }, enabled = !loading && editor.intervals.size < 4, modifier = Modifier.testTag("edit-add-$index")) { Text("Añadir intervalo (turno partido)") }
                Text("Si la salida es anterior a la entrada, el turno termina al día siguiente.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.applyEdit(index) }, enabled = !loading, modifier = Modifier.testTag("edit-apply-$index")) { Text("Aplicar cambios") }
                TextButton(onClick = { model.cancelEdit(index) }, enabled = !loading) { Text("Cancelar") }
            }
            Text("Aplicar no marca el día como revisado: revísalo después.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
