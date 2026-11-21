package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StartupCheckingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun InterruptedRestoreScreen(state: StartupState.Recovery, keepCurrent: () -> Unit, restoreSafetyCopy: () -> Unit, acknowledge: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("Restauración interrumpida", style = MaterialTheme.typography.headlineSmall)
            Text(state.message)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.needsDecision) Button(onClick = acknowledge) { Text("Continuar") }
            else {
                Text("Elige qué hacer. Ninguna opción se aplica sin tu confirmación.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = restoreSafetyCopy, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Volver a mis datos anteriores a la restauración") }
                OutlinedButton(onClick = keepCurrent, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Conservar los datos tal como están ahora") }
            }
        }
    }
}

/** No action here writes data: the only options are to retry the read-only check or to update the app. */
@Composable
fun IncompatibleDataScreen(reasons: List<String>, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(32.dp))
            Text("No se pueden abrir tus datos con esta versión", style = MaterialTheme.typography.headlineSmall)
            Text("TurnoControl ha detectado datos que esta versión no sabe interpretar con seguridad. Para no dañarlos, no se muestran ni se modifican.")
            reasons.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            Text("Tus datos siguen intactos en el dispositivo. Instala la versión de TurnoControl con la que se guardaron (o una más reciente). " +
                "No desinstales la aplicación ni borres sus datos: eso sí los eliminaría.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = retry) { Text("Volver a comprobar") }
        }
    }
}
