package com.turnocontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthNames = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
private val fullMonthNames = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

@Composable
fun SummaryScreen(model: SummaryViewModel, calendar: CalendarViewModel, openDay: (LocalDate) -> Unit, openSettings: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val calendarState by calendar.state.collectAsStateWithLifecycle()
    val spanish = Locale.forLanguageTag("es")
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Resumen", onSettings = openSettings)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.moveYear(-1) }, modifier = Modifier.semantics { contentDescription = "Año anterior" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Text(state.year.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { model.moveYear(1) }, modifier = Modifier.semantics { contentDescription = "Año siguiente" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(12) { index ->
                    FilterChip(selected = state.month.monthValue == index + 1, onClick = { model.selectMonth(index + 1) }, label = { Text(monthNames[index]) })
                }
            }
            CalendarRecoveryActions(calendar, calendarState)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionTitle("Mes · ${state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish))}")
            StatGrid(periodStats(state.monthSummary))
            MonthExtrasCard(state.month.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)), state.monthSummary.extraMinutes)
            EarningsCard("Bruto estimado del mes", state.monthSummary, state, openSettings)
            SectionTitle("Año · ${state.year}")
            AnnualExtrasCard(state.year, state.yearSummary.extraMinutes)
            MonthlyExtrasHistory(state.year, state.monthlyExtras, state.month.monthValue, model::selectMonth)
            StatGrid(periodStats(state.yearSummary))
            EarningsCard("Bruto estimado del año", state.yearSummary, state, openSettings)
            SectionTitle("Desglose")
            Breakdown(state)
            SectionTitle("Extras declaradas del mes")
            val extras = state.days.filter { YearMonth.from(it.date) == state.month && it.extraMinutes() > 0 }.sortedBy { it.date }
            if (extras.isEmpty()) Text("Todavía no hay intervalos marcados como extras.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extras.forEach { day ->
                Card(onClick = { openDay(day.date) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${day.date} · ${minutesLabel(day.extraMinutes())}", style = MaterialTheme.typography.titleMedium)
                        day.intervals.filter { it.isExtra }.forEach { interval ->
                            Text("${interval.start}${if (interval.startDayOffset == 1) " (+1 día)" else ""} – ${interval.end}${if (interval.endDayOffset == 1) " (+1 día)" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Declared extras per month of the year; each row selects its month. Readable as a list by a screen reader. */
@Composable
private fun MonthlyExtrasHistory(year: Int, extras: List<Int>, selected: Int, select: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Horas extra por mes · $year", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (extras.all { it == 0 }) Text("Sin horas extra declaradas en $year", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            else extras.forEachIndexed { index, minutes ->
                if (minutes > 0) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected == index + 1, role = Role.Tab, onClick = { select(index + 1) })
                    .padding(horizontal = 16.dp).semantics(mergeDescendants = true) { contentDescription = "${fullMonthNames[index]} de $year: ${minutesLabel(minutes)} de horas extra" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(fullMonthNames[index].replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = if (selected == index + 1) colors.primary else colors.onSurface)
                    Text(minutesLabel(minutes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Breakdown(state: SummaryState) {
    val colors = MaterialTheme.colorScheme
    val month = state.monthSummary
    val totals = state.monthlyTotals
    val max = totals.maxOrNull() ?: 0
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (month.totalMinutes > 0) {
                Text("Horas del mes · normales frente a extras", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small)
                    .semantics { contentDescription = "Normales ${minutesLabel(month.regularMinutes)}, extras ${minutesLabel(month.extraMinutes)}" }) {
                    if (month.regularMinutes > 0) Box(Modifier.weight(month.regularMinutes.toFloat()).fillMaxHeight().background(colors.primary))
                    if (month.extraMinutes > 0) Box(Modifier.weight(month.extraMinutes.toFloat()).fillMaxHeight().background(colors.tertiary))
                }
            }
            if (max == 0) Text("Sin horas en ${state.year}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            else {
                Text("Horas totales por mes · ${state.year}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = totals.mapIndexed { i, m -> "${monthNames[i]} ${minutesLabel(m)}" }.joinToString(", ") },
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    totals.forEachIndexed { index, minutes ->
                        val selected = index + 1 == state.month.monthValue
                        Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.fillMaxWidth().height((88 * minutes / max).coerceAtLeast(if (minutes > 0) 3 else 0).dp)
                                .clip(MaterialTheme.shapes.extraSmall).background(if (selected) colors.primary else colors.primaryContainer))
                            Text(monthNames[index].take(1), style = MaterialTheme.typography.labelSmall, color = if (selected) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
