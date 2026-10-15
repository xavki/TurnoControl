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
        Text(minutesLabel(days.sumOf { it.extraMinutes() }), style = MaterialTheme.typography.headlineMedium)
        Text("Declaradas en la planificación · incluidas en el total programado", style = MaterialTheme.typography.bodySmall)
        if (days.isEmpty()) Text("Todavía no hay intervalos marcados como extras.")
        days.forEach { day ->
            HorizontalDivider()
            TextButton(onClick = { openDay(day.date) }) { Text("${day.date} · ${minutesLabel(day.extraMinutes())}") }
            day.intervals.filter { it.isExtra }.forEach { interval -> Text("${interval.start}${if (interval.startDayOffset == 1) " (+1 día)" else ""} – ${interval.end}${if (interval.endDayOffset == 1) " (+1 día)" else ""}") }
        }
    }
}
