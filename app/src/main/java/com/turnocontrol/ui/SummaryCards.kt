package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.turnocontrol.domain.*
import java.math.BigDecimal

@Composable
fun periodStats(summary: PeriodSummary): List<StatItem> {
    val colors = MaterialTheme.colorScheme
    val turno = MaterialTheme.turno
    return listOf(
        StatItem("Horas normales", minutesLabel(summary.regularMinutes), TurnoIcons.Clock, colors.primary),
        StatItem("Horas extras", minutesLabel(summary.extraMinutes), TurnoIcons.Clock, colors.tertiary),
        StatItem("Total", minutesLabel(summary.totalMinutes), TurnoIcons.Summary, colors.secondary),
        StatItem("Días trabajados", summary.workDays.toString(), TurnoIcons.Work, colors.primary),
        StatItem("Días libres", summary.freeDays.toString(), TurnoIcons.Free, turno.free),
        StatItem("Vacaciones", summary.vacationDays.toString(), TurnoIcons.Vacation, turno.vacation),
        StatItem("Baja médica", summary.sickDays.toString(), TurnoIcons.Medical, turno.sick),
    )
}

/** Monthly declared overtime: outlined, secondary to the annual card. Without [onClick] it is informative only. */
@Composable
fun MonthExtrasCard(monthLabel: String, minutes: Int, onClick: (() -> Unit)? = null) {
    val title = "Horas extra del mes"
    val colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
    val modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "$title ($monthLabel): ${minutesLabel(minutes)}${if (onClick != null) ". Abre el resumen" else ""}"
    }
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(TurnoIcons.Clock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(minutesLabel(minutes), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (onClick != null) OutlinedCard(onClick = onClick, colors = colors, border = border, modifier = modifier, content = content)
    else OutlinedCard(colors = colors, border = border, modifier = modifier, content = content)
}

/** Annual accumulated overtime (1 Jan – 31 Dec of the selected year): the prominent card. */
@Composable
fun AnnualExtrasCard(year: Int, minutes: Int, onClick: (() -> Unit)? = null) {
    val title = "Horas extra acumuladas del año $year"
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
    val modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title: ${minutesLabel(minutes)}" }
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TurnoIcons.Summary, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(minutesLabel(minutes), style = MaterialTheme.typography.headlineLarge)
            Text("Del 1 de enero al 31 de diciembre · declaradas al editar cada intervalo", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (onClick != null) Card(onClick = onClick, colors = colors, modifier = modifier, content = content) else Card(colors = colors, modifier = modifier, content = content)
}

/** The estimate values every declared extra at the overtime rate; paid and compensated extras are not modelled. */
internal const val EXTRAS_AS_PAID_NOTE = "Horas planificadas, no fichajes reales. Todas las extras declaradas se estiman como pagadas; no se distinguen extras compensadas con descanso."

private fun rateText(rate: BigDecimal?) = rate?.toPlainString()?.replace('.', ',') ?: "—"

@Composable
fun EarningsCard(title: String, summary: PeriodSummary, state: SummaryState, openSettings: () -> Unit) {
    val estimate = EarningsEstimator.estimate(summary, state.baseRate, state.extraRate)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TurnoIcons.Euro, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("Estimación con las tarifas actuales", style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
            Text(estimate.total?.let(::euros) ?: "No disponible", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            EstimateRow("Horas normales", minutesLabel(summary.regularMinutes), state.baseRate, estimate.regular)
            EstimateRow("Horas extras", minutesLabel(summary.extraMinutes), state.extraRate, estimate.extra)
            state.plusRates.forEach { (type, amount) ->
                Text("${rateLabels[type]}: ${amount.replace('.', ',')} · sin cálculo automático", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Text("Vacaciones y baja médica: sin cálculo", style = MaterialTheme.typography.bodySmall, color = muted)
            Text(EXTRAS_AS_PAID_NOTE, style = MaterialTheme.typography.bodySmall, color = muted)
            if (estimate.missing.isNotEmpty()) {
                estimate.missing.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = openSettings, contentPadding = PaddingValues(0.dp)) { Text("Configurar tarifas") }
            }
            Text("Estimación bruta nominal · no es salario neto", style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun EstimateRow(label: String, hours: String, rate: BigDecimal?, amount: BigDecimal?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$label · $hours × ${rateText(rate)} €/h", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(amount?.let(::euros) ?: "No disponible", style = MaterialTheme.typography.bodyMedium)
    }
}
