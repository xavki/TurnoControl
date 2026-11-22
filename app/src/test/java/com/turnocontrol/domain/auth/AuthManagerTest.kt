package com.turnocontrol.domain.auth

import com.turnocontrol.domain.sync.*
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_A
import com.turnocontrol.domain.auth.FakeAuthProvider.Companion.ACCOUNT_B
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuthManagerTest {
    private var now = Instant.parse("2026-12-01T10:00:00Z")
    private val provider = FakeAuthProvider { now }
    private val auth = AuthManager(provider) { now }
    private fun email(account: String, password: String) = SignInRequest.Email(account, password.toCharArray())
    private val signInA get() = email("ana@example.invalid", "clave-sintetica-a")
    private val signInB get() = email("bea@example.invalid", "clave-sintetica-b")
    private fun AuthState.account() = (this as AuthState.Authenticated).session.identity.accountId

    @Test fun startsAsGuestAndCannotAuthorizeAnything() = runTest {
        assertEquals(AuthState.Guest, auth.state)
        assertNull(auth.authorize())
    }

    @Test fun emailAndGoogleSignInAuthenticateTheServerIdentity() = runTest {
        assertEquals(ACCOUNT_A, auth.signIn(signInA).account())
        assertNotNull(auth.authorize())
        auth.signOut()
        val google = auth.signIn(SignInRequest.Google("google-token-b", rawNonce = "nonce-sintetico"))
        assertEquals(ACCOUNT_B, google.account())
        assertEquals(AuthMethod.GOOGLE, (google as AuthState.Authenticated).session.identity.method)
    }

    @Test fun failuresLeaveTheAppWhereItWasAndClearThePassword() = runTest {
        val wrong = email("ana@example.invalid", "otra")
        assertEquals(AuthState.Failed(AuthError.InvalidCredentials, AuthState.Guest), auth.signIn(wrong))
        assertTrue(wrong.password.all { it == '\u0000' }) // Cleared after the attempt.
        assertEquals(AuthState.Failed(AuthError.Cancelled, AuthState.Guest), auth.signIn(SignInRequest.Google("cancelled", "n")))
        provider.offline = true
        assertEquals(AuthState.Failed(AuthError.Network, AuthState.Guest), auth.signIn(signInA))
        assertNull(auth.authorize())
        provider.offline = false
        assertEquals(ACCOUNT_A, auth.signIn(signInA).account()) // Retry after a failure works.
    }

    @Test fun signingInWhileAuthenticatedIsRejectedWithoutSideEffects() = runTest {
        auth.signIn(signInA)
        val result = auth.signIn(signInB)
        assertEquals(AuthError.InvalidState, (result as AuthState.Failed).error)
        assertEquals(ACCOUNT_A, result.fallback.account())
        assertNotNull(auth.authorize()) // A is still valid.
    }

    @Test fun signOutIsOfflineSafeAndIdempotent() = runTest {
        auth.signIn(signInA)
        provider.offline = true
        assertEquals(AuthState.Guest, auth.signOut())
        assertEquals(AuthState.Guest, auth.signOut()) // Repeated sign-out is a no-op.
        assertEquals(listOf(ACCOUNT_A), provider.signedOut)
        assertNull(auth.authorize())
    }

    @Test fun switchingAccountsReplacesTheSessionOrKeepsTheOriginal() = runTest {
        auth.signIn(signInA)
        val failed = auth.switchAccount(email("bea@example.invalid", "mal"))
        assertEquals(ACCOUNT_A, (failed as AuthState.Failed).fallback.account()) // Failed switch: still A.
        assertEquals(ACCOUNT_B, auth.switchAccount(signInB).account())
        assertEquals(listOf(ACCOUNT_A), provider.signedOut) // A's session ended.
        auth.signOut()
        assertEquals(AuthError.InvalidState, (auth.switchAccount(signInA) as AuthState.Failed).error) // A guest cannot "switch".
    }

    @Test fun expiryAndRevocationStopAuthorizationAndOnlyTheSameAccountRenews() = runTest {
        val session = (auth.signIn(signInA) as AuthState.Authenticated).session
        now = now.plusSeconds(FakeAuthProvider.SESSION_SECONDS)
        assertNull(auth.authorize())
        assertEquals(AuthState.SessionExpired(session.identity, ExpiryReason.EXPIRED), auth.refreshValidity())
        // Renewing with another account is refused (that would be a silent switch) and its session is ended.
        assertEquals(AuthError.InvalidState, (auth.signIn(signInB) as AuthState.Failed).error)
        assertEquals(listOf(ACCOUNT_B), provider.signedOut)
        assertEquals(ACCOUNT_A, auth.signIn(signInA).account())
        provider.revoke((auth.state as AuthState.Authenticated).session)
        assertEquals(ExpiryReason.REVOKED, (auth.refreshValidity() as AuthState.SessionExpired).reason)
        assertNull(auth.authorize())
    }

    @Test fun revokedOrExpiredSessionsCannotAuthorizeSynchronization() = runTest {
        val server = FakeSyncServer { LocalDate.parse("2026-12-01") }
        auth.signIn(signInA)
        val authorized = auth.authorize()!!
        val transport = auth.bind(authorized, server.transport(server.signIn(ACCOUNT_A.value)))
        val replica = SyncEngine.edit(Replica(ACCOUNT_A.value, "dispositivo"), SyncCodecs.dayKey(LocalDate.parse("2026-12-02")),
            SyncCodecs.encodeDay(DayRecord(com.turnocontrol.domain.PlannedDay(LocalDate.parse("2026-12-02"), com.turnocontrol.domain.DayKind.FREE, emptyList())))).replica
        provider.revoke((auth.state as AuthState.Authenticated).session)
        assertEquals(SyncStatus.AUTH_REQUIRED, SyncEngine.sync(replica, transport).status)
        assertTrue(server.received.isEmpty()) // Nothing reached the server.
        // A transport bound to A always reports A as owner, so it cannot be used with B's data.
        auth.signOut(); auth.signIn(signInB)
        val forB = auth.bind(auth.authorize()!!, server.transport(server.signIn(ACCOUNT_B.value)))
        assertEquals(SyncStatus.ACCOUNT_MISMATCH, SyncEngine.sync(replica, forB).status)
        now = now.plusSeconds(FakeAuthProvider.SESSION_SECONDS)
        assertThrows(SyncUnauthorizedException::class.java) { forB.pull(0, 10) }
    }

    @Test fun identifiersAreValidatedAndSecretsNeverAppearInText() = runTest {
        listOf(null, "", "ana", "../../databases/turnocontrol", "00000000-0000-4000-8000-00000000000A", "00000000-0000-4000-8000-00000000000a/..",
            " 00000000-0000-4000-8000-00000000000a").forEach { assertNull(it, AccountId.parse(it)) }
        val session = (auth.signIn(signInA) as AuthState.Authenticated).session
        assertFalse(session.toString().contains(session.handle))
        assertFalse(auth.state.toString().contains("fake-session"))
        auth.signOut()
        auth.signIn(SignInRequest.Google("google-token-a", "nonce-secreto"))
        assertTrue(provider.seen.none { it.contains("clave-sintetica") || it.contains("google-token") || it.contains("nonce-secreto") })
    }
}
