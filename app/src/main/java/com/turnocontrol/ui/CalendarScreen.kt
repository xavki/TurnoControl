@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.turnocontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.data.planned
import com.turnocontrol.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(model: CalendarViewModel, summary: SummaryViewModel, openDay: () -> Unit, openSummary: (YearMonth) -> Unit, openSettings: () -> Unit, openImport: (() -> Unit)? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val summaryState by summary.state.collectAsStateWithLifecycle()
    val spanish = Locale.forLanguageTag("es")
    val planned = remember(state.days) { state.days.map { it.planned() } }
    // Indexed once per data change: each cell is then O(1) instead of scanning the whole history on every recomposition.
    val byDate = remember(planned) { planned.associateBy { it.date } }
    val continuations = remember(planned) { continuationDates(planned) }
    val monthSummary = ScheduleSummary.month(planned, state.month)
    val yearSummary = ScheduleSummary.year(planned, state.month.year)
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("TurnoControl", onSettings = openSettings)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.move(-1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana anterior" else "Mes anterior" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Text(if (state.week) "${model.weekStart().format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))} – ${model.weekStart().plusDays(6).format(DateTimeFormatter.ofPattern("d MMM yyyy", spanish))}" else state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)),
                    style = if (state.week) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { model.move(1) }, modifier = Modifier.semantics { contentDescription = if (state.week) "Semana siguiente" else "Mes siguiente" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.week, onClick = { model.week(false) }, label = { Text("Mes") })
                    FilterChip(selected = state.week, onClick = { model.week(true) }, label = { Text("Semana") })
                }
                TextButton(onClick = model::today) { Text("Hoy") }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row { listOf("L", "M", "X", "J", "V", "S", "D").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val first = if (state.week) model.weekStart() else state.month.atDay(1).minusDays((state.month.atDay(1).dayOfWeek.value - 1).toLong())
            val weeks = if (state.week) 1 else ((state.month.atDay(1).dayOfWeek.value - 1 + state.month.lengthOfMonth() + 6) / 7)
            val rowHeight = if (state.week) 120.dp else 52.dp
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(weeks) { week ->
                    // Minimum height, not a fixed one: with a large system font the day number grows and the state
                    // indicator must stay visible below it (it was squashed at 150 % and pushed out at 200 %).
                    Row(Modifier.heightIn(min = rowHeight).height(IntrinsicSize.Min).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(7) { weekday ->
                            val date = first.plusDays((week * 7 + weekday).toLong())
                            val own = byDate[date]
                            val continues = date in continuations
                            DayCell(date, own, continues, today = date == LocalDate.now(), chosen = date == state.focused,
                                dimmed = !state.week && YearMonth.from(date) != state.month, enabled = !state.busy && !state.loading,
                                modifier = Modifier.weight(1f).fillMaxHeight()) { model.select(date); openDay() }
                        }
                    }
                }
            }
            Legend()
            // Same OCR flow as Ajustes → Importar horario; nothing is saved before the review and the final confirmation.
            openImport?.let { OutlinedButton(onClick = it, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Importar horario desde una imagen") } }
            CalendarRecoveryActions(model, state)
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.announced()) }
            SectionTitle("Resumen del mes")
            StatGrid(periodStats(monthSummary))
            SectionTitle("Horas extra")
            MonthExtrasCard(state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)), monthSummary.extraMinutes) { openSummary(state.month) }
            AnnualExtrasCard(state.month.year, yearSummary.extraMinutes) { openSummary(state.month) }
            EarningsCard("Estimación de nómina", monthSummary, summaryState, openSettings)
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, own: PlannedDay?, continues: Boolean, today: Boolean, chosen: Boolean, dimmed: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val kind = own?.kind ?: DayKind.UNKNOWN
    val hasExtras = own != null && own.extraMinutes() > 0
    val tint = kind.tint(MaterialTheme.colorScheme, MaterialTheme.turno)
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (today) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "$date · ${kind.label}${if (hasExtras) " · Con extras" else ""}${if (continues) " · Continuación nocturna" else ""}${if (today) " · Hoy" else ""}"
            selected = chosen
        }) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall,
                color = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Row(Modifier.padding(top = 4.dp).heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                when (kind) {
                    DayKind.WORK -> Box(Modifier.size(10.dp).background(tint, CircleShape))
                    DayKind.FREE -> Box(Modifier.size(10.dp).border(1.5.dp, tint, CircleShape))
                    DayKind.VACATION, DayKind.SICK_LEAVE -> Icon(kind.icon()!!, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                    DayKind.UNKNOWN -> if (continues) Text("↳", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasExtras) Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                if (continues && kind != DayKind.UNKNOWN) Text("↳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    val turno = MaterialTheme.turno
    val muted = colors.onSurfaceVariant
    @Composable fun item(label: String, mark: @Composable () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { mark(); Text(label, style = MaterialTheme.typography.labelSmall, color = muted) }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("Trabajo") { Box(Modifier.size(8.dp).background(colors.primary, CircleShape)) }
        item("Libre") { Box(Modifier.size(8.dp).border(1.5.dp, turno.free, CircleShape)) }
        item("Vacaciones") { Icon(TurnoIcons.Vacation, null, Modifier.size(12.dp), tint = turno.vacation) }
        item("Baja médica") { Icon(TurnoIcons.Medical, null, Modifier.size(12.dp), tint = turno.sick) }
        item("Extras") { Box(Modifier.size(6.dp).background(colors.tertiary, CircleShape)) }
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportConfirmationScreen(model: CalendarViewModel, back: () -> Unit, calendar: () -> Unit, saved: () -> Unit = calendar) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val editable = !state.busy && state.reconciliation == null
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(state.importSaved) { if (state.importSaved) saved() }
    val summary = model.importSummary()
    val blocker = if (state.importReady) model.importBlocker() else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Confirmar y guardar turnos", style = MaterialTheme.typography.headlineSmall)
        Text("Todos los días de esta lista están revisados. Todavía no se ha escrito nada: compara con el calendario y confirma.")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        CalendarRecoveryActions(model, state)
        if (state.importReady) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Se guardarán ${summary.days} día(s) con ${summary.shifts} turno(s)", style = MaterialTheme.typography.titleMedium)
                Text("Nuevos: ${summary.added} · Sustituyen datos existentes: ${summary.replaced}")
                Text("Ya guardados igual (no se duplican): ${summary.unchanged} · Se conservan sin cambios: ${summary.kept}")
                if (summary.unresolved > 0) Text("Pendientes de decidir: ${summary.unresolved}", color = colors.error)
            }
        }
        state.imports.forEachIndexed { index, proposal ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
                border = if (proposal.status == ImportStatus.CONFLICT && proposal.replace == null) BorderStroke(1.dp, colors.error) else null) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(proposal.planned.date.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        when (proposal.status) {
                            ImportStatus.NEW -> ReviewStatusChip("Nuevo", colors.primary, colors.onPrimary)
                            ImportStatus.UNCHANGED -> ReviewStatusChip("Ya guardado", colors.surfaceVariant, colors.onSurfaceVariant)
                            ImportStatus.CONFLICT -> ReviewStatusChip("Conflicto", colors.errorContainer, colors.onErrorContainer)
                        }
                    }
                    Text("Importado: ${proposal.planned.rangeText()}")
                    proposal.existing?.let { Text("Guardado: ${it.rangeText()}", color = colors.onSurfaceVariant) }
                    when (proposal.status) {
                        ImportStatus.UNCHANGED -> Text("El calendario ya tiene exactamente estos datos. No se escribirá ni se duplicará.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        ImportStatus.CONFLICT -> {
                            Text(importDifference(proposal.existing!!, proposal.planned), style = MaterialTheme.typography.bodyMedium)
                            Text("¿Qué quieres hacer con este día?", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(proposal.replace == true, { model.resolveImport(index, true) }, label = { Text("Sustituir por el importado") }, enabled = editable)
                                FilterChip(proposal.replace == false, { model.resolveImport(index, false) }, label = { Text("Conservar el guardado") }, enabled = editable)
                            }
                        }
                        ImportStatus.NEW -> {}
                    }
                }
            }
        }
        blocker?.let { Text(it, color = colors.error, modifier = Modifier.announced()) }
        state.message?.let { Text(it, modifier = Modifier.announced()) }
        Button(onClick = { asking = true }, enabled = editable && state.importReady && blocker == null, modifier = Modifier.fillMaxWidth()) { Text("Confirmar y guardar turnos") }
        TextButton(onClick = back, enabled = !state.busy) { Text("Volver a revisión") }
        TextButton(onClick = calendar, enabled = !state.busy) { Text("Continuar en calendario manual") }
        Text("Se vuelve a comprobar el calendario dentro de la transacción: si ha cambiado, no se guarda nada y puedes volver a revisar.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false },
        title = { Text("¿Guardar los turnos revisados?") },
        text = { Text("Se guardarán ${summary.days} día(s) con ${summary.shifts} turno(s) en el calendario" +
            (if (summary.replaced > 0) ", sustituyendo ${summary.replaced} día(s) existente(s)." else ".") + " Los días ya guardados igual no se duplican.") },
        confirmButton = { TextButton(onClick = { asking = false; model.saveImport() }, enabled = editable) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancelar") } })
}
