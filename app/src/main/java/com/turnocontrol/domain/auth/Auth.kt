package com.turnocontrol.domain.auth

import com.turnocontrol.domain.sync.PullPage
import com.turnocontrol.domain.sync.PushRequest
import com.turnocontrol.domain.sync.PushResult
import com.turnocontrol.domain.sync.SyncTransport
import com.turnocontrol.domain.sync.SyncUnauthorizedException
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * Authentication domain (TurnoControl 0.3.0, phase 4B.1). Pure Kotlin: no Google, Supabase or network dependency.
 * The real provider (Supabase Auth behind Credential Manager / email) implements [AuthProvider] later; tests use an
 * in-memory fake that lives in test sources only. Nothing here is wired into the production app yet.
 *
 * Invariants:
 *  - An account id is the server-issued user id (a UUID), never something the user typed; it is validated on entry.
 *  - Passwords are CharArrays, cleared after use, never stored, never part of toString().
 *  - Only an [AuthState.Authenticated] session that its provider still reports valid can authorize synchronization.
 */

/** Server-issued account identifier: a canonical lowercase UUID. Anything else is rejected. */
@JvmInline value class AccountId private constructor(val value: String) {
    override fun toString() = value
    companion object {
        private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        fun parse(raw: String?): AccountId? = raw?.takeIf { uuid.matches(it) }?.let(::AccountId)
    }
}

enum class AuthMethod { GOOGLE, EMAIL }

/** Who is signed in. [maskedEmail] is for display only (e.g. "a***@e***.com"); it is never used for authorization. */
data class Identity(val accountId: AccountId, val method: AuthMethod, val maskedEmail: String)

/**
 * A provider session. [handle] is an opaque reference the provider understands (later: the encrypted token vault's
 * key), not a token; it is still redacted from [toString] so it never reaches logs.
 */
class AuthSession(val identity: Identity, val handle: String, val expiresAt: Instant) {
    override fun toString() = "AuthSession(identity=$identity, expiresAt=$expiresAt)"
    override fun equals(other: Any?) = other is AuthSession && other.identity == identity && other.handle == handle && other.expiresAt == expiresAt
    override fun hashCode() = 31 * (31 * identity.hashCode() + handle.hashCode()) + expiresAt.hashCode()
}

sealed interface SignInRequest {
    /** Native Google sign-in. The ID token is exchanged by the provider and never kept. */
    class Google(val idToken: String, val rawNonce: String) : SignInRequest { override fun toString() = "Google(***)" }
    /** The password is cleared by [AuthManager] after the attempt. */
    class Email(val email: String, val password: CharArray) : SignInRequest { override fun toString() = "Email(***)" }
}

sealed interface AuthError {
    data object Cancelled : AuthError
    data object InvalidCredentials : AuthError
    data object Network : AuthError
    data object Expired : AuthError
    data object Revoked : AuthError
    /** The operation is not valid in the current state (e.g. signing in while already signed in). */
    data object InvalidState : AuthError
    data class Unknown(val code: String) : AuthError
}

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

enum class SessionValidity { VALID, EXPIRED, REVOKED }
enum class ExpiryReason { EXPIRED, REVOKED }

/** Boundary to the real (later) or fake (tests) identity service. */
interface AuthProvider {
    suspend fun signIn(request: SignInRequest): AuthResult
    /** Best effort and offline-safe: signing out locally never depends on the network. */
    suspend fun signOut(session: AuthSession)
    fun validity(session: AuthSession, now: Instant): SessionValidity
}

/** Where sessions are kept between launches (encrypted; see data/auth). Never consulted for authorization. */
interface SessionPersistence {
    fun save(session: AuthSession)
    fun clear(account: AccountId)
}

/** Explicit states: no Boolean combination can describe an impossible one. */
sealed interface AuthState {
    data object Guest : AuthState
    data class SigningIn(val method: AuthMethod, val previous: AuthState) : AuthState
    data class Authenticated(val session: AuthSession) : AuthState
    data class SwitchingAccount(val from: Identity, val method: AuthMethod) : AuthState
    /** The identity is known but cannot authorize anything until the same account signs in again. */
    data class SessionExpired(val identity: Identity, val reason: ExpiryReason) : AuthState
    /** A failed attempt; [fallback] is the state the app is still in (nothing changed). */
    data class Failed(val error: AuthError, val fallback: AuthState) : AuthState
}

/** A session that may authorize synchronization right now. */
class AuthorizedSession internal constructor(val session: AuthSession) { val accountId get() = session.identity.accountId }

/**
 * Authentication state machine. All transitions are serialized; every one is explicit and tested. Storage switching
 * is coordinated by the caller (see data/accounts), never here.
 */
class AuthManager(private val provider: AuthProvider, private val persistence: SessionPersistence? = null, private val clock: () -> Instant = Instant::now) {
    private val lock = Mutex()
    var state: AuthState = AuthState.Guest
        private set

    /** Settled state: a failure reports its error once and then leaves the app in its fallback state. */
    private val settled: AuthState get() = (state as? AuthState.Failed)?.fallback ?: state

    private fun method(request: SignInRequest) = when (request) { is SignInRequest.Google -> AuthMethod.GOOGLE; is SignInRequest.Email -> AuthMethod.EMAIL }

    private suspend fun attempt(request: SignInRequest): AuthResult = try { provider.signIn(request) } finally {
        if (request is SignInRequest.Email) request.password.fill('\u0000')
    }

    /** From guest, after a failure, or to renew an expired session of the SAME account. */
    suspend fun signIn(request: SignInRequest): AuthState = lock.withLock {
        val current = settled
        if (current is AuthState.Authenticated) { (request as? SignInRequest.Email)?.password?.fill('\u0000'); return@withLock AuthState.Failed(AuthError.InvalidState, current).also { state = it } }
        state = AuthState.SigningIn(method(request), current)
        state = when (val result = attempt(request)) {
            is AuthResult.Failure -> AuthState.Failed(result.error, current)
            is AuthResult.Success -> {
                val expired = current as? AuthState.SessionExpired
                // Renewing an expired session must not silently become another account (that is a switch).
                if (expired != null && expired.identity.accountId != result.session.identity.accountId) {
                    provider.signOut(result.session)
                    AuthState.Failed(AuthError.InvalidState, current)
                } else AuthState.Authenticated(result.session).also { persistence?.save(result.session) }
            }
        }
        state
    }

    /** Signing out never needs the network and is idempotent; afterwards the app is a guest. */
    suspend fun signOut(): AuthState = lock.withLock {
        when (val current = settled) {
            is AuthState.Authenticated -> { runCatching { provider.signOut(current.session) }; persistence?.clear(current.session.identity.accountId) }
            is AuthState.SessionExpired -> persistence?.clear(current.identity.accountId)
            else -> Unit
        }
        state = AuthState.Guest
        state
    }

    /** Authenticated -> another account. On failure the original session stays as it was. */
    suspend fun switchAccount(request: SignInRequest): AuthState = lock.withLock {
        val current = settled as? AuthState.Authenticated
            ?: run { (request as? SignInRequest.Email)?.password?.fill('\u0000'); return@withLock AuthState.Failed(AuthError.InvalidState, settled).also { state = it } }
        state = AuthState.SwitchingAccount(current.session.identity, method(request))
        state = when (val result = attempt(request)) {
            is AuthResult.Failure -> AuthState.Failed(result.error, current)
            is AuthResult.Success -> {
                persistence?.save(result.session)
                if (result.session.identity.accountId != current.session.identity.accountId) {
                    runCatching { provider.signOut(current.session) }
                    persistence?.clear(current.session.identity.accountId)
                }
                AuthState.Authenticated(result.session)
            }
        }
        state
    }

    /** Re-checks the session with the provider (expiry by time, revocation by the server). */
    suspend fun refreshValidity(): AuthState = lock.withLock {
        val current = settled as? AuthState.Authenticated ?: return@withLock state
        state = when (provider.validity(current.session, clock())) {
            SessionValidity.VALID -> current
            SessionValidity.EXPIRED -> AuthState.SessionExpired(current.session.identity, ExpiryReason.EXPIRED)
            SessionValidity.REVOKED -> AuthState.SessionExpired(current.session.identity, ExpiryReason.REVOKED)
                .also { persistence?.clear(current.session.identity.accountId) } // A revoked session is never kept.
        }
        state
    }

    /**
     * App start with a session read back from storage. Only from guest. An expired session waits for renewal of the
     * same account; a revoked one is deleted. Neither authorizes anything.
     */
    suspend fun restore(session: AuthSession): AuthState = lock.withLock {
        if (settled != AuthState.Guest) return@withLock state
        state = when (provider.validity(session, clock())) {
            SessionValidity.VALID -> AuthState.Authenticated(session)
            SessionValidity.EXPIRED -> AuthState.SessionExpired(session.identity, ExpiryReason.EXPIRED)
            SessionValidity.REVOKED -> AuthState.SessionExpired(session.identity, ExpiryReason.REVOKED).also { persistence?.clear(session.identity.accountId) }
        }
        state
    }

    /** The only way to obtain a session that may authorize synchronization. Null unless authenticated and valid now. */
    suspend fun authorize(): AuthorizedSession? = lock.withLock {
        val current = settled as? AuthState.Authenticated ?: return@withLock null
        if (provider.validity(current.session, clock()) != SessionValidity.VALID) return@withLock null
        AuthorizedSession(current.session)
    }

    /** Wraps a transport so every call re-checks that this exact session is still valid (expiry, revocation). */
    fun bind(authorized: AuthorizedSession, transport: SyncTransport): SyncTransport = object : SyncTransport {
        private fun check() {
            if (provider.validity(authorized.session, clock()) != SessionValidity.VALID) throw SyncUnauthorizedException()
        }
        // The owner is the authenticated account, never an id taken from the request or the database.
        override val accountId: String get() { check(); return authorized.accountId.value }
        override fun push(deviceId: String, changes: List<PushRequest>): List<PushResult> { check(); return transport.push(deviceId, changes) }
        override fun pull(since: Long, limit: Int): PullPage { check(); return transport.pull(since, limit) }
    }
}
