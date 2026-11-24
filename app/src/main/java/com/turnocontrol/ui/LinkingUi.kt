package com.turnocontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.TurnoDatabase
import com.turnocontrol.data.accounts.*
import com.turnocontrol.domain.*
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.domain.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * First-time account linking UI (phase 5C). Uses the phase 5B engine only (AccountLinker / LinkMergePlanner).
 * NOT reachable from production navigation: without real authentication there is no account to link to. It is built
 * from an injected [LinkingBackend] (tests today; the future account flow later). "Combinar" is the only apply action:
 * there is no "replace everything" option.
 */

interface LinkingBackend {
    suspend fun recover(): LinkRecovery
    suspend fun preview(): LinkPlan
    suspend fun apply(planId: String, decisions: Map<RecordKey, Resolution>, acknowledgeReplacements: Boolean): LinkOutcome
}

/** The real engine for an open account session; [guest] is the guest database opened for reading only. */
class AccountLinkingBackend(private val guest: TurnoDatabase, private val session: StorageSession, private val account: AccountId,
                            private val linker: AccountLinker) : LinkingBackend {
    override suspend fun recover() = linker.recover(session)
    override suspend fun preview() = linker.preview(guest, session)
    override suspend fun apply(planId: String, decisions: Map<RecordKey, Resolution>, acknowledgeReplacements: Boolean) =
        linker.apply(guest, session, account, planId, decisions, acknowledgeReplacements)
}

enum class LinkingPhase { LOADING, READY, APPLYING, COMPLETED, CANCELLED, FAILED, STALE, RECOVERY }

data class LinkingState(
    val phase: LinkingPhase = LinkingPhase.LOADING,
    val plan: LinkPlan? = null,
    val decisions: Map<RecordKey, Resolution> = emptyMap(),
    val replaceConfirmed: Boolean = false,
    val message: String? = null,
) {
    val pending: List<RecordKey> get() = plan?.decisionsNeeded.orEmpty().filter { it !in decisions }
    /** Account records that the current choices would replace (needs the extra confirmation). */
    val replacements: List<RecordKey> get() = plan?.entries.orEmpty().filter { it.kind == LinkKind.CONFLICT && decisions[it.key] == Resolution.KEEP_LOCAL }.map { it.key }
    val canConfirm: Boolean get() = phase == LinkingPhase.READY && plan != null && pending.isEmpty() && (replacements.isEmpty() || replaceConfirmed)
}

/** Survives configuration changes (decisions included); an apply is never replayed by recreating the screen. */
class LinkingViewModel(private val backend: LinkingBackend) : ViewModel() {
    private val mutable = MutableStateFlow(LinkingState())
    val state = mutable.asStateFlow()

    init { load() }

    fun load() {
        if (state.value.phase == LinkingPhase.APPLYING) return
        mutable.value = LinkingState(LinkingPhase.LOADING)
        viewModelScope.launch {
            mutable.value = try {
                val recovered = when (backend.recover()) {
                    LinkRecovery.COMPLETED -> "Se completó una vinculación que se había interrumpido."
                    LinkRecovery.DISCARDED -> "Una vinculación anterior se interrumpió antes de cambiar nada. Puedes repetirla."
                    LinkRecovery.NONE -> null
                }
                LinkingState(LinkingPhase.READY, backend.preview(), message = recovered)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LinkingState(LinkingPhase.FAILED, message = "No se pudo preparar la vista previa. No se ha cambiado nada.") }
        }
    }

    fun decide(key: RecordKey, resolution: Resolution) {
        if (state.value.phase != LinkingPhase.READY) return
        if (resolution == Resolution.CANCEL) { cancel(); return }
        mutable.update { it.copy(decisions = it.decisions + (key to resolution), replaceConfirmed = false, message = null) }
    }

    /** IMPORT entries are added by default; skipping one keeps it only in the guest data. */
    fun include(key: RecordKey, included: Boolean) {
        if (state.value.phase != LinkingPhase.READY) return
        mutable.update { it.copy(decisions = if (included) it.decisions - key else it.decisions + (key to Resolution.KEEP_REMOTE), message = null) }
    }

    fun confirmReplacement(confirmed: Boolean) { if (state.value.phase == LinkingPhase.READY) mutable.update { it.copy(replaceConfirmed = confirmed) } }

    /** Cancels everything before applying: neither database changes. */
    fun cancel() {
        if (state.value.phase == LinkingPhase.APPLYING) return
        mutable.update { it.copy(phase = LinkingPhase.CANCELLED, message = "Vinculación cancelada. No se ha cambiado nada en ningún dispositivo ni en la cuenta.") }
    }

    fun confirm() {
        val current = state.value
        if (!current.canConfirm) return
        mutable.update { it.copy(phase = LinkingPhase.APPLYING, message = null) } // Disables a second confirmation at once.
        viewModelScope.launch {
            val plan = current.plan!!
            val next = try {
                when (val outcome = backend.apply(plan.id, current.decisions, current.replaceConfirmed)) {
                    is LinkOutcome.Applied -> current.copy(phase = LinkingPhase.COMPLETED,
                        message = "Vinculación completada y verificada: ${outcome.written} registro(s) guardado(s) en la cuenta. Los datos sin cuenta siguen intactos en este dispositivo.")
                    LinkOutcome.AlreadyLinked -> current.copy(phase = LinkingPhase.COMPLETED, message = "Esta vinculación ya se había completado. No se ha repetido.")
                    LinkOutcome.PlanChanged -> current.copy(phase = LinkingPhase.STALE, message = "Los datos han cambiado desde la vista previa. Actualízala antes de continuar.")
                    LinkOutcome.Cancelled -> current.copy(phase = LinkingPhase.CANCELLED, message = "Vinculación cancelada. No se ha cambiado nada.")
                    is LinkOutcome.NeedsDecisions -> current.copy(phase = LinkingPhase.READY, message = "Faltan decisiones en algunos conflictos.")
                    is LinkOutcome.NeedsAcknowledgement -> current.copy(phase = LinkingPhase.READY, replaceConfirmed = false, message = "Confirma que se sustituirán datos de la cuenta.")
                    is LinkOutcome.Invalid -> current.copy(phase = LinkingPhase.READY,
                        message = "El resultado combinado no es válido: ${outcome.reason} Desmarca o cambia los días afectados. No se ha cambiado nada.")
                    is LinkOutcome.Refused -> current.copy(phase = LinkingPhase.FAILED, message = "${outcome.reason} No se ha cambiado nada.")
                    is LinkOutcome.BackupFailed -> current.copy(phase = LinkingPhase.FAILED, message = outcome.reason)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                current.copy(phase = LinkingPhase.RECOVERY, message = "La vinculación se interrumpió. Al volver a abrir esta pantalla se comprobará el estado. Los datos sin cuenta de este dispositivo no se han modificado.")
            }
            mutable.value = next
        }
    }
}

/** Readable description of a record. Sick leave shows only its date and "Baja médica"; no internal identifiers. */
fun describeRecord(key: RecordKey, record: LocalRecord?): String {
    if (record == null) return "Sin datos (borrado)"
    return runCatching {
        when (key.entity) {
            SyncEntity.SCHEDULED_DAY -> {
                val day = SyncCodecs.localDay(key, record).day
                val date = "%02d/%02d/%d".format(day.date.dayOfMonth, day.date.monthValue, day.date.year)
                if (day.kind != DayKind.WORK) "$date · ${day.kind.label}" else {
                    val extra = day.extraMinutes().takeIf { it > 0 }?.let { " · ${minutesLabel(it)} de extra declarada" }.orEmpty()
                    "$date · Trabajo · ${day.rangeText()}$extra"
                }
            }
            SyncEntity.PAY_RULE -> SyncCodecs.decodeRate(key, (record as LocalRecord.Shared).payload).let { "Tarifa ${rateLabels[it.type] ?: "base EUR/h"} desde ${it.validFrom}: ${it.amount.toPlainString()} ${it.currency}" }
            SyncEntity.PROFILE -> SyncCodecs.decodeProfile(key, (record as LocalRecord.Shared).payload).let { "Perfil: ${it.displayName}" }
            SyncEntity.CONTRACT -> SyncCodecs.decodeContract(key, (record as LocalRecord.Shared).payload).let { "Contrato ${if (it.type == "FULL") "completo" else "parcial"} desde ${it.validFrom}" }
            SyncEntity.PAYSLIP -> "Nómina de ${key.key}"
        }
    }.getOrElse { "Dato que esta versión no puede mostrar" }
}

@Composable
fun LinkingScreen(model: LinkingViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    LinkingContent(state, onClose, model::decide, model::include, model::confirmReplacement, model::confirm, model::cancel, model::load)
}

@Composable
fun LinkingContent(state: LinkingState, onClose: () -> Unit, onDecide: (RecordKey, Resolution) -> Unit, onInclude: (RecordKey, Boolean) -> Unit,
                   onConfirmReplacement: (Boolean) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Vincular datos con la cuenta", onBack = onClose)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Se copiarán a tu cuenta los datos de este dispositivo que elijas. Los datos sin cuenta se conservan tal cual en este dispositivo, " +
                "y antes de cambiar nada se guarda una copia de seguridad de ambos lados.", style = MaterialTheme.typography.bodyMedium)
            state.message?.let { Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) { Text(it, Modifier.padding(16.dp).testTag("linking-message").announced()) } }
            val plan = state.plan
            when (state.phase) {
                LinkingPhase.LOADING -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Preparando la vista previa…") }
                LinkingPhase.STALE, LinkingPhase.FAILED, LinkingPhase.RECOVERY -> Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("linking-refresh")) { Text("Actualizar vista previa") }
                else -> Unit
            }
            if (plan != null && state.phase in setOf(LinkingPhase.READY, LinkingPhase.APPLYING)) {
                Text("Resumen", style = MaterialTheme.typography.titleMedium)
                Text("Se añadirán a la cuenta: ${plan.count(LinkKind.IMPORT) - plan.entries.count { it.kind == LinkKind.IMPORT && state.decisions[it.key] == Resolution.KEEP_REMOTE }}", modifier = Modifier.testTag("linking-import-count"))
                Text("Ya iguales en ambos lados: ${plan.count(LinkKind.ALREADY_IDENTICAL)}")
                Text("Se conservan los de la cuenta: ${plan.count(LinkKind.KEEP_ACCOUNT)}")
                Text("Requieren tu decisión: ${plan.decisionsNeeded.size}", modifier = Modifier.testTag("linking-conflict-count"))
                if (plan.count(LinkKind.UNSUPPORTED) > 0) Text("No se pueden combinar con esta versión (se dejan intactos): ${plan.count(LinkKind.UNSUPPORTED)}")
                plan.entries.filter { it.kind == LinkKind.CONFLICT || it.kind == LinkKind.DELETED_IN_ACCOUNT }.forEach { entry ->
                    Card(Modifier.fillMaxWidth().testTag("linking-conflict")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Este dispositivo: ${describeRecord(entry.key, entry.guest)}")
                            Text("Cuenta: ${describeRecord(entry.key, entry.account)}", color = colors.onSurfaceVariant)
                            FilterChip(state.decisions[entry.key] == Resolution.KEEP_LOCAL, { onDecide(entry.key, Resolution.KEEP_LOCAL) }, enabled = state.phase == LinkingPhase.READY,
                                label = { Text("Conservar datos de este dispositivo") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(state.decisions[entry.key] == Resolution.KEEP_REMOTE, { onDecide(entry.key, Resolution.KEEP_REMOTE) }, enabled = state.phase == LinkingPhase.READY,
                                label = { Text("Conservar datos de la cuenta") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                plan.entries.filter { it.kind == LinkKind.IMPORT }.forEach { entry ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                        Checkbox(state.decisions[entry.key] != Resolution.KEEP_REMOTE, { onInclude(entry.key, it) }, enabled = state.phase == LinkingPhase.READY,
                            modifier = Modifier.semantics { contentDescription = "Añadir ${describeRecord(entry.key, entry.guest)}" })
                        Text(describeRecord(entry.key, entry.guest), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.replacements.isNotEmpty()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                    Checkbox(state.replaceConfirmed, onConfirmReplacement, modifier = Modifier.testTag("linking-replace-confirm"))
                    Text("Entiendo que se sustituirán ${state.replacements.size} dato(s) de la cuenta. La versión anterior queda en la copia de seguridad.")
                }
                Button(onClick = onConfirm, enabled = state.canConfirm, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("linking-confirm")) {
                    Text(if (state.phase == LinkingPhase.APPLYING) "Combinando…" else "Combinar")
                }
                TextButton(onClick = onCancel, enabled = state.phase == LinkingPhase.READY, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("linking-cancel")) { Text("Cancelar") }
            }
            if (state.phase in setOf(LinkingPhase.COMPLETED, LinkingPhase.CANCELLED)) {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("linking-close")) { Text("Cerrar") }
            }
        }
    }
}
