package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.SyncStore
import com.turnocontrol.domain.rangeText
import com.turnocontrol.domain.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * Synchronization conflicts (phase 0.3.0). Like the first-link screen, NOT reachable from production navigation:
 * without real authentication no database is ever linked, so no conflict can exist. It is built from an injected
 * [ConflictBackend] (tests today; the future account flow later). Nothing is ever decided automatically: each
 * conflict waits for "Conservar este dispositivo", "Conservar el otro dispositivo" or stays pending.
 */

interface ConflictBackend {
    suspend fun conflicts(): List<SyncConflict>
    suspend fun resolve(key: RecordKey, resolution: Resolution): ResolutionResult
}

/** The real store of the open account session. */
class SyncStoreConflictBackend(private val store: SyncStore, private val today: () -> LocalDate = LocalDate::now) : ConflictBackend {
    override suspend fun conflicts() = store.load().conflicts.values.sortedBy { it.key.toString() }
    override suspend fun resolve(key: RecordKey, resolution: Resolution) = store.resolve(key, resolution, today())
}

data class ConflictsState(val loading: Boolean = true, val conflicts: List<SyncConflict> = emptyList(), val busy: Boolean = false, val message: String? = null)

/** Human description of one side of a conflict; only allowlisted, already-synchronized fields are shown. */
internal fun describeSide(key: RecordKey, payload: SyncPayload?): String {
    if (payload == null) return "Eliminado"
    return runCatching {
        when (key.entity) {
            SyncEntity.SCHEDULED_DAY -> SyncCodecs.decodeDay(key, payload).day.rangeText()
            SyncEntity.PAY_RULE -> SyncCodecs.decodeRate(key, payload).let { "${it.amount.toPlainString().replace('.', ',')} €/h" }
            else -> "Versión guardada"
        }
    }.getOrDefault("Versión no legible en esta versión de la aplicación")
}

internal fun conflictTitle(key: RecordKey) = when (key.entity) {
    SyncEntity.SCHEDULED_DAY -> "Día ${key.key}"
    SyncEntity.PAY_RULE -> "Tarifa ${key.key.substringBefore('@')} desde ${key.key.substringAfter('@')}"
    SyncEntity.PROFILE -> "Perfil"
    SyncEntity.CONTRACT -> "Contrato"
    SyncEntity.PAYSLIP -> "Nómina ${key.key}"
}

internal fun conflictReason(kind: ConflictKind) = when (kind) {
    ConflictKind.VERSION -> "Se cambió en este dispositivo y en otro a la vez."
    ConflictKind.INVARIANT -> "Aplicar la versión del otro dispositivo rompería una regla (por ejemplo, turnos solapados)."
    ConflictKind.REJECTED -> "El servidor rechazó el cambio de este dispositivo."
}

class ConflictsViewModel(private val backend: ConflictBackend) : ViewModel() {
    private val mutable = MutableStateFlow(ConflictsState())
    val state = mutable.asStateFlow()
    init { reload() }

    fun reload() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            val loaded = try { backend.conflicts() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            mutable.update { if (loaded == null) it.copy(loading = false, message = "No se pudieron leer los conflictos. No se ha modificado nada.") else it.copy(loading = false, conflicts = loaded) }
        }
    }

    /** One decision at a time; a second tap while one is applied is ignored, never queued. */
    fun resolve(key: RecordKey, resolution: Resolution) {
        if (state.value.busy || resolution == Resolution.CANCEL) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                val result = backend.resolve(key, resolution)
                when (result.outcome) {
                    ResolutionOutcome.KEPT_LOCAL -> "Se conserva la versión de este dispositivo. La otra versión se guarda en este dispositivo durante 90 días."
                    ResolutionOutcome.KEPT_REMOTE -> "Se conserva la versión del otro dispositivo. La de este dispositivo se guarda en él durante 90 días."
                    ResolutionOutcome.ALREADY_IDENTICAL -> "Las dos versiones ya eran iguales; no ha hecho falta cambiar nada."
                    ResolutionOutcome.NO_CONFLICT -> "Este conflicto ya no está pendiente."
                    ResolutionOutcome.REJECTED -> "No se pudo aplicar la decisión: ${result.error ?: "regla no válida"}. No se ha modificado nada."
                    ResolutionOutcome.CANCELLED -> null
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { "No se pudo guardar la decisión. No se ha modificado nada." }
            val remaining = runCatching { backend.conflicts() }.getOrNull()
            mutable.update { it.copy(busy = false, message = message, conflicts = remaining ?: it.conflicts) }
        }
    }
}

@Composable
fun ConflictsScreen(model: ConflictsViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Conflictos de sincronización", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ninguna versión se descarta sin tu decisión. Mientras un conflicto esté pendiente, ese dato no se puede editar.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, modifier = Modifier.testTag("conflicts-message").announced()) }
            if (!state.loading && state.conflicts.isEmpty()) Text("No hay conflictos pendientes.", modifier = Modifier.testTag("conflicts-empty"))
            state.conflicts.forEach { conflict ->
                Card(Modifier.fillMaxWidth().testTag("conflict-${conflict.key}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(conflictTitle(conflict.key), style = MaterialTheme.typography.titleMedium)
                        Text(conflictReason(conflict.kind), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("Este dispositivo: ${describeSide(conflict.key, (conflict.local as? LocalRecord.Shared)?.payload)}")
                        Text("Otro dispositivo: ${describeSide(conflict.key, conflict.remote?.takeUnless { it.deleted }?.payload)}")
                        conflict.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        OutlinedButton(onClick = { model.resolve(conflict.key, Resolution.KEEP_LOCAL) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Conservar este dispositivo") }
                        OutlinedButton(onClick = { model.resolve(conflict.key, Resolution.KEEP_REMOTE) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Conservar el otro dispositivo") }
                    }
                }
            }
        }
    }
}
