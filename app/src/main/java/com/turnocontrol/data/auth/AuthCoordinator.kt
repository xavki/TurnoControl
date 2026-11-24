package com.turnocontrol.data.auth

import com.turnocontrol.data.accounts.StorageNamespace
import com.turnocontrol.data.accounts.StorageSessionHolder
import com.turnocontrol.data.accounts.SwitchOutcome
import com.turnocontrol.domain.auth.AccountId
import com.turnocontrol.domain.auth.AuthError
import com.turnocontrol.domain.auth.AuthManager
import com.turnocontrol.domain.auth.AuthMethod
import com.turnocontrol.domain.auth.AuthState
import com.turnocontrol.domain.auth.SignInRequest
import com.turnocontrol.ui.AccountUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Phase 5E.1: the application-level bridge between AuthManager (provider + session state machine), EncryptedSessionVault
 * (persistence) and StorageSessionHolder (which database is active). Nothing in AuthManager or StorageSessionHolder
 * changed: this class only sequences calls to them so the two layers can never disagree about which account is active.
 *
 * Ordering rules, chosen deliberately, not incidentally:
 *  - Sign-in / switch-account: identity must be confirmed by the provider before a storage target is even known, so
 *    auth happens first. If the storage switch then fails or is refused, the coordinator undoes the auth side (see
 *    below) rather than leave AuthManager claiming an account the storage layer never actually opened.
 *  - Sign-out: the storage target (guest) is already known, so storage switches FIRST. AuthManager's state is only
 *    updated once storage confirms the switch; a busy/failed storage switch leaves the user fully authenticated and
 *    untouched, exactly as AccountStorageCoordinator already guarantees for storage alone.
 *  - A fresh sign-in's rollback on storage failure is a plain AuthManager.signOut() (nothing else was ever touched:
 *    storage never left Guest, per AccountStorageCoordinator's own Busy/Failed contract).
 *  - An account-to-account switch's rollback on storage failure is NOT "pretend to still be A": AuthManager.switchAccount
 *    already discarded A's provider session and persisted B's by the time storage is attempted (see AuthManager.switchAccount).
 *    There is no clean path back to "still A" without resurrecting a session AuthManager has already revoked. Treating
 *    it as "still A" would also risk the coordinator reporting Authenticated(B)'s identity while storage silently kept
 *    A's data, which is exactly the cross-account exposure this phase must prevent. So the coordinator falls all the
 *    way back to Guest at both layers, the same safe terminal fallback AccountStorageCoordinator.start() already uses
 *    when an account database cannot be trusted. This is a deliberate, conservative trade-off: a failed account-to-account
 *    switch costs an extra sign-in, never a data leak.
 *  - That guest-fallback attempt can itself be refused (the same busy gate that blocked the real target also blocks
 *    Guest). publish() never trusts AuthManager's state alone for exactly this reason: it always re-checks the real
 *    storage binding, so this degrades to "sign in again for this account" (storage never moved, still A's data,
 *    AuthManager already moved on to Guest) rather than silently showing "Sin cuenta" over A's still-visible data or
 *    claiming B's identity storage was never switched to. See AuthCoordinatorTest.
 *    failedStorageSwitchDuringAccountSwitchFallsBackAllTheWayToGuest for the exact sequence.
 */

/** Outcome of a coordinator operation, for the UI to turn into a message. Mirrors SwitchOutcome's shape on purpose. */
sealed interface AuthCoordinatorOutcome {
    data object Success : AuthCoordinatorOutcome
    data class Busy(val reason: String) : AuthCoordinatorOutcome
    data class Failed(val error: AuthError) : AuthCoordinatorOutcome
}

private fun AuthMethod.label() = when (this) { AuthMethod.GOOGLE -> "Google"; AuthMethod.EMAIL -> "correo electrónico" }

class AuthCoordinator(
    private val manager: AuthManager,
    private val vault: EncryptedSessionVault,
    private val storage: StorageSessionHolder,
) {
    private val mutable = MutableStateFlow<AccountUiState>(AccountUiState.Guest)
    val state = mutable.asStateFlow()

    /**
     * Current UI state from AuthManager's state, plus, when authenticated, this session's own pending-sync counts.
     * AuthManager.state alone cannot distinguish "no account at all" from "this device's active storage IS an
     * account's data, but there is no readable session for it" (missing or corrupted session file): AuthManager only
     * knows about providers and sessions, never about which database is open. So when AuthManager reports Guest, the
     * coordinator still checks the active storage's own binding before deciding what to show: if it is bound to an
     * account, the data on screen already belongs to that account, and the user must see "sign in again", never
     * "Sin cuenta" (which would misrepresent whose data is actually being displayed).
     *
     * [explicit], when given, is published instead of re-reading manager.state: AuthManager.state is one shared
     * field, and a second operation that gets rejected (e.g. a concurrent sign-in finding the app already
     * authenticated) still writes its own Failed(InvalidState, fallback) into that same field as a side effect. If
     * commitNewAuthentication's success branch re-read the live field after its OWN storage switch finished, a
     * rejected concurrent attempt's write that lands in between could make it publish the REJECTED attempt's view
     * instead of its own confirmed success. Passing the exact AuthState this call already obtained avoids that race
     * entirely; only call sites with no such value in hand (restoreOnStart, refreshValidity, the rollback branches,
     * signOut) read the live field, which is correct there since nothing else just contended for it.
     */
    private suspend fun publish(explicit: AuthState? = null) {
        val boundToAccount = runCatching { storage.scope().sync.linkedAccount() }.getOrNull() != null
        mutable.value = when (val s = explicit ?: manager.state) {
            AuthState.Guest -> if (boundToAccount) AccountUiState.Expired("esta cuenta") else AccountUiState.Guest
            is AuthState.SigningIn -> mutable.value // Unchanged; the ViewModel's own busy flag covers this.
            is AuthState.SwitchingAccount -> mutable.value
            is AuthState.Authenticated -> {
                val replica = runCatching { storage.scope().sync.load() }.getOrNull()
                AccountUiState.Authenticated(s.session.identity.maskedEmail, s.session.identity.method.label(),
                    pendingChanges = replica?.outbox?.size, conflicts = replica?.conflicts?.size)
            }
            is AuthState.SessionExpired -> AccountUiState.Expired(s.identity.maskedEmail)
            is AuthState.Failed -> mutable.value // The failure is surfaced via the operation's own return value, not here.
        }
    }

    /**
     * App start, before anything is shown. Only ever looks at the already-committed storage namespace (no switch): if
     * it is an account, its binding gives the account id, and the encrypted session for exactly that id is restored.
     * Never touches another account's session file, never changes the committed namespace.
     */
    suspend fun restoreOnStart() {
        val scope = storage.scope()
        val boundRaw = scope.sync.linkedAccount()
        val account = boundRaw?.let { AccountId.parse(it) }
        if (account != null) restoreStoredSession(manager, vault, account)
        // NoSession / SignInAgain leave manager.state at Guest; publish() still shows "sign in again" for a bound
        // account by checking the storage binding directly, not just manager.state (see publish()'s own comment).
        publish()
    }

    /** Guest -> Authenticated. A failed or refused storage switch leaves both layers at Guest, untouched. */
    suspend fun signIn(request: SignInRequest): AuthCoordinatorOutcome {
        val result = manager.signIn(request)
        return commitNewAuthentication(result)
    }

    /** Authenticated(A) -> Authenticated(B). On storage failure, falls back to Guest at both layers (see file header). */
    suspend fun switchAccount(request: SignInRequest): AuthCoordinatorOutcome {
        val result = manager.switchAccount(request)
        return commitNewAuthentication(result, fallbackAllTheWayToGuestOnStorageFailure = true)
    }

    private suspend fun commitNewAuthentication(result: AuthState, fallbackAllTheWayToGuestOnStorageFailure: Boolean = false): AuthCoordinatorOutcome {
        val authenticated = result as? AuthState.Authenticated ?: run {
            publish()
            return (result as? AuthState.Failed)?.let { AuthCoordinatorOutcome.Failed(it.error) } ?: AuthCoordinatorOutcome.Success
        }
        val id = authenticated.session.identity.accountId
        return when (val outcome = storage.switchTo(StorageNamespace.forAccount(id), id)) {
            is SwitchOutcome.Switched -> { publish(authenticated); AuthCoordinatorOutcome.Success }
            is SwitchOutcome.Busy -> {
                if (fallbackAllTheWayToGuestOnStorageFailure) storage.switchTo(StorageNamespace.Guest, null)
                manager.signOut()
                publish()
                AuthCoordinatorOutcome.Busy(outcome.reason)
            }
            is SwitchOutcome.Failed -> {
                if (fallbackAllTheWayToGuestOnStorageFailure) storage.switchTo(StorageNamespace.Guest, null)
                manager.signOut()
                publish()
                AuthCoordinatorOutcome.Failed(AuthError.Unknown("storage: ${outcome.reason}"))
            }
        }
    }

    /**
     * Authenticated -> Guest. Storage switches first: a busy/failed storage switch leaves the user untouched. Always
     * attempted, never gated on manager.state directly: AuthManager can leave state at Failed(error, fallback) after
     * a failed operation (fallback not yet "settled" into state until the next call), so a raw type check here could
     * miss a still-authenticated fallback. Both underlying calls are already safe no-ops when there is nothing to
     * sign out of (switchTo(Guest) returns Switched immediately if already Guest; AuthManager.signOut() resolves the
     * same fallback internally and does nothing beyond setting Guest).
     *
     * Returns the plain [SwitchOutcome], not [AuthCoordinatorOutcome]: from the UI's perspective sign-out IS exactly
     * the storage switch's outcome (AccountViewModel's existing signOutAction is already typed this way, tested
     * against a real AccountStorageCoordinator in AccountUiTest; there is no separate auth-level outcome worth adding
     * a second, UI-facing type for here).
     */
    suspend fun signOut(): SwitchOutcome {
        val outcome = storage.switchTo(StorageNamespace.Guest, null)
        if (outcome is SwitchOutcome.Switched) manager.signOut()
        publish()
        return outcome
    }

    /** Re-checks the stored session with the provider now (expiry by time, revocation by the server). Data untouched. */
    suspend fun refreshValidity() { manager.refreshValidity(); publish() }
}
