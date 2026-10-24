package com.turnocontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.turnocontrol.data.ConfigurationRepository
import com.turnocontrol.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.IOException
import javax.inject.Inject

data class ConfigurationState(val draft: ConfigurationDraft = ConfigurationDraft(), val loading: Boolean = true, val saving: Boolean = false, val message: String? = null, val configured: Boolean = false)
data class ThemeState(val dark: Boolean = false, val loading: Boolean = true, val error: String? = null)
@HiltViewModel
class ConfigurationViewModel @Inject constructor(private val repository: ConfigurationRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ConfigurationState())
    val state = mutable.asStateFlow()
    private val mutableTheme = MutableStateFlow(ThemeState())
    val theme = mutableTheme.asStateFlow()
    private var themeJob: Job? = null
    init { retryThemeRead() }
    fun retryThemeRead() {
        if (themeJob?.isActive == true) return
        mutableTheme.update { it.copy(loading = true, error = null) }
        themeJob = viewModelScope.launch {
            try {
                repository.darkTheme.collect { dark -> mutableTheme.value = ThemeState(dark = dark, loading = false) }
            } catch (e: IOException) {
                // Retain the last value. Never replace unreadable preferences or retry a write.
                mutableTheme.update { it.copy(loading = false, error = "No se pudo leer la apariencia guardada. Puedes volver a intentarlo.") }
            }
        }
    }
    fun setDarkTheme(enabled: Boolean) {
        if (theme.value.loading || theme.value.error != null) return
        viewModelScope.launch {
        try { repository.setDarkTheme(enabled) }
        catch (e: IOException) { mutable.update { it.copy(message = "No se pudo guardar la preferencia.") } }
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
