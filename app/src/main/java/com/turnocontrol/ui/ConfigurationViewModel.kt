package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ConfigurationState(val draft: ConfigurationDraft = ConfigurationDraft(), val loading: Boolean = true, val saving: Boolean = false, val message: String? = null, val configured: Boolean = false)
@HiltViewModel
class ConfigurationViewModel @Inject constructor(private val repository: ConfigurationRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ConfigurationState())
    val state = mutable.asStateFlow()
    val darkTheme = repository.darkTheme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun setDarkTheme(enabled: Boolean) { viewModelScope.launch {
        try { repository.setDarkTheme(enabled) }
        catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(message = "No se pudo guardar la preferencia.") } }
    } }
    init { viewModelScope.launch {
        try {
            val profile = repository.profile.first()
            mutable.value = ConfigurationState(draft = profile?.let { repository.load(it) } ?: ConfigurationDraft(), loading = false, configured = profile != null)
        } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(loading = false, message = "No se pudo leer la configuración local.") } }
    } }
    fun update(draft: ConfigurationDraft) { mutable.update { it.copy(draft = draft, message = null) } }
    fun save() {
        val draft = state.value.draft
        draft.validate()?.let { error -> mutable.update { it.copy(message = error) }; return }
        viewModelScope.launch {
            mutable.update { it.copy(saving = true) }
            try {
                repository.save(draft)
                mutable.update { it.copy(saving = false, configured = true, message = "Configuración guardada en este dispositivo.") }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(saving = false, message = "No se pudo guardar. Reintenta.") } }
        }
    }
}
