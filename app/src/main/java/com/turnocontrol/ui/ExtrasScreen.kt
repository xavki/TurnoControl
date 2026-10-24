package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turnocontrol.data.planned
import com.turnocontrol.domain.*
import java.time.LocalDate

@Composable
fun ExtrasScreen(model: CalendarViewModel, openDay: (LocalDate) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val days = state.days.map { it.planned() }.filter { it.intervals.any { i -> i.isExtra } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Horas extras", style = MaterialTheme.typography.headlineSmall)
        CalendarRecoveryActions(model, state)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(minutesLabel(days.sumOf { it.extraMinutes() }), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                Text("Declaradas en la planificación · incluidas en el total programado", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (days.isEmpty()) Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Todavía no hay intervalos marcados como extras.", style = MaterialTheme.typography.titleMedium)
            Text("Puedes marcarlos al editar un intervalo del calendario.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        days.forEach { day ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openDay(day.date) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                        Text("${day.date} · ${minutesLabel(day.extraMinutes())}", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    day.intervals.filter { it.isExtra }.forEach { interval -> Text("${interval.start}${if (interval.startDayOffset == 1) " (+1 día)" else ""} – ${interval.end}${if (interval.endDayOffset == 1) " (+1 día)" else ""}", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
