package com.turnocontrol.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun TimeWheel(title: String, initial: LocalTime?, cancel: () -> Unit, confirm: (LocalTime) -> Unit) {
    var hour by remember { mutableIntStateOf(initial?.hour ?: 0) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(String.format(Locale.ROOT, "%02d:%02d", hour, minute), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                WheelColumn("Hora", 24, hour) { hour = it }
                WheelColumn("Minuto", 60, minute) { minute = it }
            }
        }
    }, confirmButton = { TextButton(onClick = { confirm(LocalTime.of(hour, minute)) }) { Text("Confirmar hora") } },
        dismissButton = { TextButton(onClick = cancel) { Text("Cancelar hora") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(label: String, count: Int, initial: Int, change: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val scope = rememberCoroutineScope()
    val selected by remember {
        derivedStateOf {
            val layout = state.layoutInfo
            layout.visibleItemsInfo.filter { it.index in 2 until count + 2 }
                .minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - (layout.viewportStartOffset + layout.viewportEndOffset) / 2) }
                ?.index?.minus(2) ?: initial
        }
    }
    LaunchedEffect(selected) { change(selected) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        LazyColumn(state = state, flingBehavior = rememberSnapFlingBehavior(state), modifier = Modifier.width(88.dp).height(240.dp)
            .semantics { contentDescription = "Rueda $label"; stateDescription = selected.toString(); customActions = listOf(
                CustomAccessibilityAction("Aumentar $label") { scope.launch { state.animateScrollToItem((selected + 1).coerceAtMost(count - 1)) }; true },
                CustomAccessibilityAction("Reducir $label") { scope.launch { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }; true }) }) {
            items((0 until count + 4).toList()) { index ->
                val value = index - 2
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    if (value in 0 until count) Surface(color = if (value == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize().clickable { scope.launch { state.animateScrollToItem(value) } }
                            .semantics { contentDescription = "$label ${String.format(Locale.ROOT, "%02d", value)}"; this.selected = value == selected }) {
                        Box(contentAlignment = Alignment.Center) { Text(String.format(Locale.ROOT, "%02d", value), style = if (value == selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
        Row {
            TextButton(onClick = { scope.launch { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) } }, modifier = Modifier.semantics { contentDescription = "Reducir $label" }) { Text("−") }
            TextButton(onClick = { scope.launch { state.animateScrollToItem((selected + 1).coerceAtMost(count - 1)) } }, modifier = Modifier.semantics { contentDescription = "Aumentar $label" }) { Text("+") }
        }
    }
}
