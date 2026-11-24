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
import com.turnocontrol.data.accounts.StorageBusyException
import com.turnocontrol.data.accounts.SwitchOutcome
import com.turnocontrol.data.auth.AuthCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Optional-account interface (phase 4B.4). Honest by construction: there is no real identity provider yet, so the
 * Google and e-mail actions are visibly unavailable and the production ViewModel only ever reports "Sin cuenta".
 * Authenticated and expired presentations exist for the future provider and are exercised with synthetic states in
 * tests only. Nothing here switches databases.
 */

/** What the account screen shows. Synthetic states are only constructed in tests until a real provider exists. */
sealed interface AccountUiState {
    data object Guest : AccountUiState
    /** Only values the local data actually provides; no remote status or synchronization time is shown. */
    data class Authenticated(val maskedEmail: String, val provider: String, val pendingChanges: Int?, val conflicts: Int? = null) : AccountUiState
    data class Expired(val maskedEmail: String) : AccountUiState
}

enum class AccountProblem(val message: String) {
    AUTH_FAILED("No se pudo iniciar sesión. Comprueba los datos e inténtalo de nuevo."),
    SESSION_EXPIRED("Tu sesión ha caducado. Tus datos siguen en este dispositivo; inicia sesión de nuevo para sincronizar."),
    NETWORK("No hay conexión. Puedes seguir usando la app; vuelve a intentarlo más tarde."),
    SWITCH_BLOCKED("No se puede cambiar de cuenta ahora. Tus datos actuales no se han modificado."),
    PENDING_OPERATION("Espera a que termine el guardado, la restauración o la sincronización en curso y vuelve a intentarlo."),
    STORAGE("No se pudieron abrir los datos de la cuenta. Se mantienen los datos actuales."),
}

/** Pure presentation, so every text and enabled state is testable on the JVM. */
data class AccountPresentation(
    val status: String, val explanation: String, val providersEnabled: Boolean, val providerNote: String?,
    val showContinueWithoutAccount: Boolean, val showSignOut: Boolean, val details: List<String>,
)

const val PROVIDERS_UNAVAILABLE = "Todavía no disponible: el inicio de sesión se activará cuando la conexión segura esté lista. " +
    "Mientras tanto, TurnoControl funciona sin cuenta y sin Internet."

fun AccountUiState.presentation(providersAvailable: Boolean): AccountPresentation = when (this) {
    AccountUiState.Guest -> AccountPresentation("Sin cuenta",
        "Tus datos se guardan en este dispositivo. Crear una cuenta es opcional y servirá para sincronizarlos con otros dispositivos.",
        providersAvailable, PROVIDERS_UNAVAILABLE.takeUnless { providersAvailable }, showContinueWithoutAccount = true, showSignOut = false, details = emptyList())
    is AccountUiState.Authenticated -> AccountPresentation("Sesión iniciada como $maskedEmail",
        "Los datos de esta cuenta se guardan aparte de los datos sin cuenta.", providersEnabled = false, providerNote = null,
        showContinueWithoutAccount = false, showSignOut = true,
        details = listOfNotNull("Acceso con $provider", "Sincronización: todavía no disponible", pendingChanges?.let { "Cambios pendientes de sincronizar: $it" },
            conflicts?.takeIf { it > 0 }?.let { "Conflictos sin resolver: $it" }))
    is AccountUiState.Expired -> AccountPresentation("Sesión caducada ($maskedEmail)", AccountProblem.SESSION_EXPIRED.message,
        providersAvailable, PROVIDERS_UNAVAILABLE.takeUnless { providersAvailable }, showContinueWithoutAccount = false, showSignOut = true, details = emptyList())
}

/**
 * [restartRequired] is a one-shot event: storage moved to another namespace, so every Activity-scoped ViewModel still
 * holds the previous scope's repositories (now closed). The Activity must restart the task (AccountSwitchRestart).
 */
data class AccountScreenState(val account: AccountUiState = AccountUiState.Guest, val providersAvailable: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val restartRequired: Boolean = false)

@HiltViewModel
class AccountViewModel internal constructor(
    initial: AccountUiState,
    providersAvailable: Boolean,
    /** Future: switches storage to the guest namespace. Null while no account system is active. */
    private val signOutAction: (suspend () -> SwitchOutcome)?,
) : ViewModel() {
    /**
     * Production (phase 5E.1): the real AuthCoordinator is wired end-to-end (sign-out really switches storage back to
     * guest, restoreOnStart() really re-derives the state from whatever is on disk), but `providersAvailable` stays
     * false because no Google/email provider exists yet (AuthModule's AuthCoordinator can never actually reach
     * Authenticated in production: its AuthProvider always fails). This is why this can keep saying "Sin cuenta" by
     * construction today, while already being the exact path a future real provider plugs into without any further
     * ViewModel or UI change.
     */
    @Inject constructor(coordinator: AuthCoordinator) : this(AccountUiState.Guest, providersAvailable = false, signOutAction = coordinator::signOut) {
        viewModelScope.launch { coordinator.restoreOnStart() }
        viewModelScope.launch { coordinator.state.collect { account -> mutable.update { it.copy(account = account) } } }
    }

    private val mutable = MutableStateFlow(AccountScreenState(initial, providersAvailable))
    val state = mutable.asStateFlow()

    fun dismissMessage() = mutable.update { it.copy(message = null) }
    fun consumeRestart() = mutable.update { it.copy(restartRequired = false) }

    /** Sign-out keeps the account's data on disk and returns to the guest data; a refusal changes nothing. */
    fun signOut() {
        val action = signOutAction ?: return
        if (state.value.busy || state.value.account == AccountUiState.Guest) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                when (val outcome = action()) {
                    is SwitchOutcome.Switched -> { mutable.update { it.copy(account = AccountUiState.Guest, restartRequired = true) }; "Has cerrado la sesión. Los datos de la cuenta siguen guardados en este dispositivo." }
                    is SwitchOutcome.Busy -> "${AccountProblem.SWITCH_BLOCKED.message} ${outcome.reason}"
                    is SwitchOutcome.Failed -> AccountProblem.STORAGE.message
                }
            } catch (e: CancellationException) { throw e }
            catch (e: StorageBusyException) { AccountProblem.PENDING_OPERATION.message }
            catch (e: Exception) { AccountProblem.STORAGE.message }
            mutable.update { it.copy(busy = false, message = message) }
        }
    }
}

/** Short status card for Settings; opens the account screen. */
@Composable
fun AccountSummaryCard(model: AccountViewModel, onOpen: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val presentation = state.account.presentation(state.providersAvailable)
    Card(Modifier.fillMaxWidth().testTag("account-summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cuenta", style = MaterialTheme.typography.titleMedium)
            Text(presentation.status, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Opciones de cuenta") }
        }
    }
}

@Composable
fun AccountScreen(model: AccountViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    AccountContent(state, onBack = onBack, onSignOut = model::signOut, onDismiss = model::dismissMessage)
}

@Composable
fun AccountContent(state: AccountScreenState, onBack: () -> Unit, onSignOut: () -> Unit, onDismiss: () -> Unit) {
    val presentation = state.account.presentation(state.providersAvailable)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        TurnoTopBar("Cuenta", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(presentation.status, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("account-status"))
            Text(presentation.explanation, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            presentation.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.message?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, modifier = Modifier.testTag("account-message"))
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Entendido") }
                    }
                }
            }
            if (state.account !is AccountUiState.Authenticated) {
                val signIn = if (state.account is AccountUiState.Expired) "Iniciar sesión de nuevo" else "Iniciar sesión"
                Button(onClick = {}, enabled = presentation.providersEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-signin")
                    .semantics { contentDescription = if (presentation.providersEnabled) signIn else "$signIn, todavía no disponible" }) { Text(signIn) }
                if (state.account == AccountUiState.Guest) OutlinedButton(onClick = {}, enabled = presentation.providersEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-create")
                    .semantics { contentDescription = if (presentation.providersEnabled) "Crear cuenta" else "Crear cuenta, todavía no disponible" }) { Text("Crear cuenta") }
                presentation.providerNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.testTag("account-provider-note")) }
            }
            if (presentation.showContinueWithoutAccount) {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-guest")) { Text("Continuar sin cuenta") }
            }
            if (state.account is AccountUiState.Authenticated) {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-switch")
                    .semantics { contentDescription = "Cambiar de cuenta, todavía no disponible" }) { Text("Cambiar de cuenta") }
            }
            if (presentation.showSignOut) {
                OutlinedButton(onClick = onSignOut, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-signout")) { Text("Cerrar sesión") }
                Text("Al cerrar sesión, los datos de la cuenta se quedan guardados en este dispositivo y vuelves a los datos sin cuenta.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}
