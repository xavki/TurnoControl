package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.CompatibilityChecker
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
}

/** Runs before any screen that reads the calendar: incompatible data is shown, never interpreted or rewritten. */
@HiltViewModel
class StartupViewModel internal constructor(private val check: suspend () -> CompatibilityReport) : ViewModel() {
    @Inject constructor(checker: CompatibilityChecker) : this({ withContext(Dispatchers.IO) { checker.check() } })
    private val mutable = MutableStateFlow<StartupState>(StartupState.Checking)
    val state = mutable.asStateFlow()
    init { retry() }
    fun retry() {
        mutable.value = StartupState.Checking
        viewModelScope.launch {
            mutable.value = try {
                when (val report = check()) {
                    CompatibilityReport.Compatible -> StartupState.Ready
                    is CompatibilityReport.Incompatible -> StartupState.Blocked(report.reasons)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                StartupState.Blocked(listOf("No se pudo comprobar la compatibilidad de los datos locales. No se ha cambiado nada."))
            }
        }
    }
}
