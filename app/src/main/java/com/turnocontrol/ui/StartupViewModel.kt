package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.BackupRepository
import com.turnocontrol.data.CompatibilityChecker
import com.turnocontrol.data.RecoveryOutcome
import com.turnocontrol.domain.CompatibilityReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface StartupState {
    data object Checking : StartupState
    data object Ready : StartupState
    data class Blocked(val reasons: List<String>) : StartupState
    /** An interrupted restore was found. [needsDecision] means the user must choose; otherwise it is informative. */
    data class Recovery(val message: String, val needsDecision: Boolean, val busy: Boolean = false) : StartupState
}

/** Runs before any screen that reads the calendar: incompatible data is shown, never interpreted or rewritten. */
@HiltViewModel
class StartupViewModel internal constructor(
    private val check: suspend () -> CompatibilityReport,
    private val recover: suspend () -> RecoveryOutcome = { RecoveryOutcome.None },
    private val keepCurrent: suspend () -> Unit = {},
    private val restoreSafety: suspend () -> Unit = {},
) : ViewModel() {
    @Inject constructor(checker: CompatibilityChecker, backup: BackupRepository) : this(
        { withContext(Dispatchers.IO) { checker.check() } }, { backup.recoverInterrupted() }, { backup.keepCurrentData() }, { backup.restoreSafetyCopy() })
    private val mutable = MutableStateFlow<StartupState>(StartupState.Checking)
    val state = mutable.asStateFlow()
    private val replaced = MutableStateFlow(false)
    /** One-shot: stored data may have changed during recovery, after ConfigurationViewModel (created before the gate) loaded its draft. */
    val dataReplaced = replaced.asStateFlow()
    fun consumeDataReplaced() { replaced.value = false }
    init { retry() }
    fun retry() {
        mutable.value = StartupState.Checking
        viewModelScope.launch {
            mutable.value = try {
                when (val report = check()) {
                    is CompatibilityReport.Incompatible -> StartupState.Blocked(report.reasons)
                    // Recovery reads the database through Room, so it runs only after the data was accepted.
                    CompatibilityReport.Compatible -> when (val outcome = recover()) {
                        RecoveryOutcome.None -> StartupState.Ready
                        RecoveryOutcome.RolledForward -> { replaced.value = true; StartupState.Recovery("Se completó una restauración que se había interrumpido. Los datos de la copia están guardados.", false) }
                        RecoveryOutcome.Discarded -> StartupState.Recovery("Una restauración se interrumpió antes de modificar los datos. Se conservan tus datos anteriores.", false)
                        is RecoveryOutcome.NeedsDecision -> StartupState.Recovery(outcome.message, true)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                StartupState.Blocked(listOf("No se pudo comprobar la compatibilidad de los datos locales. No se ha cambiado nada."))
            }
        }
    }
    fun acknowledge() { if ((state.value as? StartupState.Recovery)?.needsDecision == false) mutable.value = StartupState.Ready }
    fun keepCurrentData() = decide(keepCurrent)
    fun restoreSafetyCopy() = decide(restoreSafety)
    private fun decide(action: suspend () -> Unit) {
        val current = state.value as? StartupState.Recovery ?: return
        if (!current.needsDecision || current.busy) return
        mutable.value = current.copy(busy = true)
        viewModelScope.launch {
            try { action(); replaced.value = true; retry() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = current.copy(message = current.message + "\nNo se pudo completar la opción elegida: ${e.message}", busy = false)
            }
        }
    }
}
